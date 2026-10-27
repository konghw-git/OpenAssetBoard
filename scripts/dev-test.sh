#!/usr/bin/env bash
# The one way to check a change: runs the tests named, prints one line per test class and the
# first lines of each failure, and leaves Gradle's full report where it always is.
#
#   scripts/dev-test.sh WeekStatsTest                       # a class, by its simple name
#   scripts/dev-test.sh RowGestureTest 'TodoNestTest.aShortSlideStaysARow'   # several; a method too
#   scripts/dev-test.sh --shots 'ModuleScreenshotTest.todoList'   # and compare its screenshots
#   scripts/dev-test.sh --fast                              # everything but the screenshot classes
#   scripts/dev-test.sh --all                               # the gate, before a commit and a release
#   scripts/dev-test.sh --accept                            # the last run's screenshots become the baselines
#   scripts/dev-test.sh --brand                             # redraw the README's banner and icon
#   scripts/dev-test.sh --compile                           # only compile the app and the tests
#
# --all is every test with every screenshot compared, and lint; scripts/publish-release.sh runs the
# very same command, so content that passed it is not tested twice, before a commit or after it
# (only *.md and docs/ may change in between). Screenshots are compared, never recorded: a change lists the images that
# differ (new and old side by side in app/build/outputs/roborazzi/*_compare.png), and --accept
# takes them as the new baselines without running anything again.
set -euo pipefail

PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$HOME/.local/share/dev-dl/android-env.sh"
# shellcheck disable=SC1090
[ -f "$ENV_FILE" ] && source "$ENV_FILE"
cd "$PROJECT"

offline=--offline
rerun=
args=()
filters=()
mode=tests
for a in "$@"; do
  case "$a" in
    --shots) args+=(-Proborazzi.test.compare=true) ;;
    --fast) args+=(-PskipShots); mode=fast ;;
    --all) mode=all ;;
    --accept) mode=accept ;;
    --brand) mode=brand ;;
    --compile) mode=compile ;;
    --online) offline= ;;
    # Run them even when nothing changed: Gradle otherwise hands back the cached results.
    --rerun) filters+=(--rerun); rerun=1 ;;
    -*) echo "unknown option: $a" >&2; exit 2 ;;
    *) filters+=(--tests "*$a") ;;
  esac
done
[ -n "$offline" ] && args+=("$offline")

results="app/build/test-results/testDebugUnitTest"
full_run="app/build/test-results/roborazzi/full-run"
passed="build/gate-passed"

# What the gate checks, as one hash: every file git would commit but the prose (*.md, docs/), and
# the baselines. Committing changes the build itself (BuildConfig carries the commit), so Gradle's
# cache cannot carry a pass across a commit; this does, for the very same content.
fingerprint() {
  python3 -I - <<'PY'
import hashlib, os, subprocess
listed = subprocess.run(["git", "ls-files", "-z", "-co", "--exclude-standard"], capture_output=True, check=True).stdout
files = sorted({f for f in listed.decode().split("\0") if f and not f.endswith(".md") and not f.startswith("docs/")})
shots = sorted(os.path.join("app/screenshots", n) for n in os.listdir("app/screenshots")) if os.path.isdir("app/screenshots") else []
h = hashlib.sha1()
for p in files + shots:
    if os.path.isfile(p):
        h.update(p.encode() + b"\0")
        with open(p, "rb") as f:
            h.update(hashlib.sha1(f.read()).digest())
print(h.hexdigest())
PY
}
case "$mode" in
  compile) exec ./gradlew "${args[@]}" -q compileDebugUnitTestKotlin ;;
  accept) exec python3 -I scripts/shots.py accept ;;
  brand)
    # Drawn straight into app/screenshots/ from the baselines there, then by hand to docs/images/.
    ./gradlew "${args[@]}" -q testDebugUnitTest --tests '*BrandShotTest' --rerun -Pbrand -Proborazzi.test.record=true
    ls -la app/screenshots/brand-*.png
    exit 0 ;;
  tests)
    if [ ${#filters[@]} -eq 0 ]; then
      sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
      exit 2
    fi ;;
esac

start=$(date +%s)
status=0
if [ "$mode" = all ]; then
  tree=$(fingerprint)
  if [ -z "$rerun" ] && [ "$(cat "$passed" 2>/dev/null)" = "$tree" ]; then
    echo "gate passed: it already passed on exactly this content (--rerun to run it again)"
    exit 0
  fi
  rm -f "$passed"
  # The app reads the time only through AppClock, which the screenshots pin (PinnedClock).
  if grep -rnE 'System\.currentTimeMillis\(\)|System::currentTimeMillis|(LocalDate|LocalDateTime|LocalTime|YearMonth)\.now\(\)' \
      app/src/main --include='*.kt' | grep -v 'domain/AppClock.kt'; then
    echo "read the time through AppClock (domain/AppClock.kt), or screenshots go stale by the day"
    exit 1
  fi
  # Nothing is deleted first: an unchanged tree's results come back from Gradle's cache.
  # Task options (--tests, --rerun) bind to the task just before them, so they follow
  # testDebugUnitTest; after lintRelease they would silently do nothing to the tests.
  ./gradlew "${args[@]}" testDebugUnitTest "${filters[@]}" -Proborazzi.test.compare=true lintRelease \
    > /tmp/asset-board-test.log 2>&1 || status=$?
  # The cache brings back the results, not the images drawn (too big to keep): any left here are
  # another run's, so --accept must not take them.
  grep -q '^> Task :app:testDebugUnitTest FROM-CACHE' /tmp/asset-board-test.log && rm -rf app/build/outputs/roborazzi
else
  rm -rf "$results" "app/build/outputs/roborazzi" "$full_run"
  ./gradlew "${args[@]}" testDebugUnitTest "${filters[@]}" > /tmp/asset-board-test.log 2>&1 || status=$?
fi

# Compile errors and the like: no results to read, so show what Gradle said.
if [ ! -d "$results" ] || [ -z "$(ls -A "$results" 2>/dev/null)" ]; then
  grep -E '^e: |error:|What went wrong' -A3 /tmp/asset-board-test.log | head -40 || tail -40 /tmp/asset-board-test.log
  echo "no tests ran (full log: /tmp/asset-board-test.log)"
  exit 1
fi

# The gate lists only the classes that failed; a named run lists every class it ran.
failed=0
python3 -I - "$results" "$mode" <<'PY' || failed=1
import glob, os, sys, xml.etree.ElementTree as ET
total = failed = 0
for p in sorted(glob.glob(os.path.join(sys.argv[1], "*.xml"))):
    s = ET.parse(p).getroot()
    n, bad = int(s.get("tests")), int(s.get("failures")) + int(s.get("errors"))
    total += n; failed += bad
    if bad or sys.argv[2] != "all":
        print(f"{'FAIL' if bad else 'ok  '} {s.get('name').split('.')[-1]:34} {n:>4} tests {float(s.get('time')):>7.1f}s")
    for case in s.iter("testcase"):
        for f in list(case.findall("failure")) + list(case.findall("error")):
            lines = (f.text or f.get("message") or "").strip().splitlines()
            print(f"     ✗ {case.get('name')}")
            for line in lines[:6]:
                print(f"       {line}")
print(f"{total} tests, {failed} failed")
sys.exit(1 if failed else 0)
PY

if [ "$mode" = all ]; then
  if [ "$status" != 0 ] && grep -q 'lint' /tmp/asset-board-test.log; then
    grep -iE 'lint found|errors? found|Wrote HTML report' /tmp/asset-board-test.log | head -5 || true
  fi
  # Stale baselines are only known when every test has drawn what it draws.
  if [ "$failed" = 0 ]; then
    touch "$full_run"
    python3 -I scripts/shots.py report --full || failed=1
  else
    python3 -I scripts/shots.py report || failed=1
  fi
elif [[ " ${args[*]} " == *" -Proborazzi.test.compare=true "* ]]; then
  python3 -I scripts/shots.py report || failed=1
fi
[ "$status" != 0 ] && failed=1

echo "took $(( $(date +%s) - start ))s (full log: /tmp/asset-board-test.log)"
if [ "$mode" = all ]; then
  if [ "$failed" = 0 ]; then
    mkdir -p build && echo "$tree" > "$passed"
    echo "gate passed"
  else
    echo "gate failed"
  fi
fi
exit $failed
