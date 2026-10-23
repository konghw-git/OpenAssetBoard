#!/usr/bin/env bash
# The quick loop for checking a change: runs only the tests named, prints one line per test class
# and the first lines of each failure, and leaves Gradle's full report where it always is.
#
#   scripts/dev-test.sh WeekStatsTest                       # a class, by its simple name
#   scripts/dev-test.sh RowGestureTest 'ModuleScreenshotTest.todoToday'   # several; a method too
#   scripts/dev-test.sh --shots 'ModuleScreenshotTest.todoToday'          # and render its shots
#   scripts/dev-test.sh --fast                              # everything but the screenshot classes
#   scripts/dev-test.sh --all                               # the whole suite, as before a release
#   scripts/dev-test.sh --compile                           # only compile the app and the tests
#
# --shots renders in Roborazzi's compare mode: the new image and its diff against the baseline go
# to app/build/outputs/roborazzi/ (*_actual.png, *_compare.png), and the baselines in
# app/screenshots/ stay as they are. Recording new baselines stays a deliberate step (README).
set -euo pipefail

PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$HOME/.local/share/dev-dl/android-env.sh"
# shellcheck disable=SC1090
[ -f "$ENV_FILE" ] && source "$ENV_FILE"
cd "$PROJECT"

offline=--offline
args=()
filters=()
mode=tests
for a in "$@"; do
  case "$a" in
    --shots) args+=(-Proborazzi.test.compare=true) ;;
    --fast) args+=(-PskipShots); mode=all ;;
    --all) mode=all ;;
    --compile) mode=compile ;;
    --online) offline= ;;
    # Run them even when nothing changed: Gradle otherwise hands back the cached results.
    --rerun) filters+=(--rerun) ;;
    -*) echo "unknown option: $a" >&2; exit 2 ;;
    *) filters+=(--tests "*$a") ;;
  esac
done
[ -n "$offline" ] && args+=("$offline")

if [ "$mode" = compile ]; then
  exec ./gradlew "${args[@]}" -q compileDebugUnitTestKotlin
fi
if [ "$mode" = tests ] && [ ${#filters[@]} -eq 0 ]; then
  sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi

results="app/build/test-results/testDebugUnitTest"
shots="app/build/outputs/roborazzi"
rm -rf "$results" "$shots"
start=$(date +%s)
status=0
./gradlew "${args[@]}" testDebugUnitTest "${filters[@]}" > /tmp/asset-board-test.log 2>&1 || status=$?

# Compile errors and the like: no results to read, so show what Gradle said.
if [ ! -d "$results" ] || [ -z "$(ls -A "$results" 2>/dev/null)" ]; then
  grep -E '^e: |error:|What went wrong' -A3 /tmp/asset-board-test.log | head -40 || tail -40 /tmp/asset-board-test.log
  echo "no tests ran (full log: /tmp/asset-board-test.log)"
  exit 1
fi

python3 -I - "$results" <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET
total = failed = 0
for p in sorted(glob.glob(os.path.join(sys.argv[1], "*.xml"))):
    s = ET.parse(p).getroot()
    n, bad = int(s.get("tests")), int(s.get("failures")) + int(s.get("errors"))
    total += n; failed += bad
    print(f"{'FAIL' if bad else 'ok  '} {s.get('name').split('.')[-1]:34} {n:>4} tests {float(s.get('time')):>7.1f}s")
    for case in s.iter("testcase"):
        for f in list(case.findall("failure")) + list(case.findall("error")):
            lines = (f.text or f.get("message") or "").strip().splitlines()
            print(f"     ✗ {case.get('name')}")
            for line in lines[:6]:
                print(f"       {line}")
print(f"{total} tests, {failed} failed")
PY
if [ -d "$shots" ]; then
  echo "rendered: $(ls "$shots" | grep -c _actual || true) shots in $shots"
fi
echo "took $(( $(date +%s) - start ))s (full log: /tmp/asset-board-test.log)"
exit $status
