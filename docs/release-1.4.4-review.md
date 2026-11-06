# Release 1.4.4 review

Version 1.4.4 uses versionCode 41, above 1.4.2's 40. It fixes stocktake-group merge
consistency, stale web editor writes, ledger-only reset scope, adopted valuation ownership,
concurrent refresh-token rotation, web target-weight validation and deleted conflict archives.
It also includes the client-scoped web login throttling already on main.

Validation:

- `scripts/dev-test.sh --compile` and `scripts/dev-test.sh --all` passed: 824 Android tests,
  all 153 screenshots unchanged, release lint and 73 web tests.
- `go test ./...` passed for the sync service; `npm run check` passed for the web client.
- Six Android and fourteen web regression tests cover the fixes. Forty shared merge cases
  check Kotlin/JavaScript agreement, commutativity and idempotence.
- The format-17 fixture parses and self-merges without changing its canonical hash.
  `scripts/compare-backups.py` reports identical ledger content and unchanged health/food rows
  for that fixture round trip. Frozen backup fixtures were not regenerated.
- The web fixture-generation helper was updated for the editor's loaded-body check and tested
  in an isolated temporary tree, preserving the checked-in fixture.

No Android device was connected (`adb devices -l` was empty), so a physical-device upgrade
and before/after comparison of a real user's backups were not performed. The fixture check
above is a compatibility check, not a replacement for that device validation.

Database schema 15 and backup format 17 are unchanged. The release script builds the signed
APK, verifies its certificate against the established release key, and uploads the APK and
R8 mapping together before the release is considered published.
