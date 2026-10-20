#!/usr/bin/env python3
"""Step-0 upgrade gate: the ledger part of two backups must match field by field.

Usage: scripts/compare-backups.py before.json after.json
Export one backup in the old version, upgrade in place, export again, then compare.
"""
import json
import sys

LEDGER = ["groups", "accounts", "snapshots", "valuations", "snapshotItems", "flows"]
# Reported but never failing: a backup from before format 4 has no health tables at all.
HEALTH = ["bodyMeasurements"]


def key(table, row):
    if table == "snapshotItems":
        return (row["snapshotId"], row["accountId"])
    return row["id"]


def main(a_path, b_path):
    a, b = (json.load(open(p, encoding="utf-8")) for p in (a_path, b_path))
    problems = 0
    for t in LEDGER:
        ra = {key(t, r): r for r in a.get(t, [])}
        rb = {key(t, r): r for r in b.get(t, [])}
        for k in sorted(set(ra) | set(rb), key=str):
            if ra.get(k) != rb.get(k):
                problems += 1
                print(f"{t} {k}:\n  before {ra.get(k)}\n  after  {rb.get(k)}")
        print(f"{t}: {len(ra)} / {len(rb)} rows")
    for t in HEALTH:
        if t not in a:
            print(f"{t}: absent before (older format), {len(b.get(t, []))} rows after")
            continue
        ra = {r["id"]: r for r in a.get(t, [])}
        rb = {r["id"]: r for r in b.get(t, [])}
        differ = sum(1 for k in set(ra) | set(rb) if ra.get(k) != rb.get(k))
        print(f"{t}: {len(ra)} / {len(rb)} rows, {differ} differing (informational)")
    print("OK: ledger identical" if problems == 0 else f"FAIL: {problems} differing rows")
    return 0 if problems == 0 else 1


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
