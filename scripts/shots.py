#!/usr/bin/env python3
"""The screenshots of the last test run against the baselines in app/screenshots/.

  shots.py report [--full]   what changed, what is new, and with --full (a whole-suite run) which
                             baselines no test drew any more; exits 1 if there is any of it
  shots.py accept            takes what the last run drew as the new baselines and moves the stale
                             ones out, both kept under build/screenshots-replaced/<time>/

Reads Roborazzi's results-summary.json, written in its compare mode (scripts/dev-test.sh). The
brand-*.png images are drawn on request (--brand) and are no baselines.
"""
import json
import shutil
import sys
import time
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
BASELINES = PROJECT / "app/screenshots"
SUMMARY = PROJECT / "app/build/test-results/roborazzi/debug/results-summary.json"
FULL_RUN = PROJECT / "app/build/test-results/roborazzi/full-run"


def load():
    if not SUMMARY.exists():
        sys.exit("no screenshot results: run scripts/dev-test.sh --all (or --shots) first")
    return json.loads(SUMMARY.read_text())["results"]


def name(path):
    return Path(path).name


def stale(results):
    drawn = {name(r["golden_file_path"]) for r in results}
    return sorted(p for p in BASELINES.glob("*.png") if p.name not in drawn and not p.name.startswith("brand-"))


def report(full):
    results = load()
    changed = sorted((r for r in results if r["type"] == "changed"), key=lambda r: name(r["golden_file_path"]))
    added = sorted(name(r["golden_file_path"]) for r in results if r["type"] == "added")
    old = stale(results) if full else []
    print(f"screenshots: {len(results)} drawn, {len(changed)} changed, {len(added)} new" + (f", {len(old)} stale" if full else ""))
    for r in changed:
        print(f"  changed {name(r['golden_file_path'])}  ({r.get('diff_percentage', 0) * 100:.2f}% of pixels)")
    for n in added:
        print(f"  new     {n}")
    for p in old:
        print(f"  stale   {p.name}  (no test draws it)")
    if changed or added or old:
        print("  compare: app/build/outputs/roborazzi/<name>_compare.png; if intended: scripts/dev-test.sh --accept")
        return 1
    return 0


def accept():
    results = load()
    take = [r for r in results if r["type"] in ("changed", "added")]
    old = stale(results) if FULL_RUN.exists() else []
    missing = [r for r in take if not Path(r["actual_file_path"]).exists()]
    if missing:
        sys.exit(f"{len(missing)} drawn images are not here (e.g. {name(missing[0]['actual_file_path'])}; a cached result "
                 "brings back none): scripts/dev-test.sh --all --rerun, then --accept")
    if not take and not old:
        print("nothing to accept: the baselines match the last run")
        return 0
    keep = PROJECT / "build/screenshots-replaced" / time.strftime("%Y%m%d-%H%M%S")
    keep.mkdir(parents=True)
    BASELINES.mkdir(exist_ok=True)
    for r in take:
        golden = Path(r["golden_file_path"])
        if golden.exists():
            shutil.copy2(golden, keep / golden.name)
        shutil.copy2(r["actual_file_path"], golden)
    for p in old:
        shutil.move(p, keep / p.name)
    names = sorted(name(r["golden_file_path"]) for r in take)
    listed = f" ({', '.join(names[:8])}{' …' if len(names) > 8 else ''})" if names else ""
    print(f"accepted {len(take)}{listed}, removed {len(old)} stale; the old images are in {keep.relative_to(PROJECT)}")
    if not FULL_RUN.exists():
        print("stale baselines are only found after a whole-suite run (--all)")
    print("run scripts/dev-test.sh --all again to confirm")
    return 0


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "report":
        sys.exit(report("--full" in sys.argv))
    if cmd == "accept":
        sys.exit(accept())
    sys.exit(__doc__)
