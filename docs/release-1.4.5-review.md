# Release 1.4.5 review

Version 1.4.5 uses versionCode 42, above 1.4.4's 41. It adds date-and-hour deadline
distances, streams the AI writing actions and connection check, removes the list/topic
deck's delayed reveal, and separates saving a todo from completing it. Checklist edits
and unsubmitted new items are saved on leaving; newly created lists wait for their data
before navigation.

Validation:

- `scripts/dev-test.sh --compile` and `scripts/dev-test.sh --all` passed: 836 Android tests,
  all 153 screenshot comparisons, release lint and 73 web tests.
- Fourteen intended screenshot changes were inspected and accepted using `--accept`;
  no baselines were re-recorded. Old images remain in
  `build/screenshots-replaced/20261106-191338/`. The following full gate reports zero
  changed, new or stale screenshots.
- Regression tests cover saving without completing, retaining checklist edits and a new
  item when returning, new-list navigation, readable deadlines on a narrow phone, elapsed
  hours across midnight and daylight saving, streaming before response completion,
  cancellation, interrupted replies, single-step undo and stale diary sessions.
- The unchanged format-17 fixture parses and self-merges without changing its canonical
  hash. `scripts/compare-backups.py` reports identical ledger content and unchanged
  health/food rows for that round trip. Frozen fixtures were not regenerated.
- `assembleRelease` succeeded. The APK metadata reports `com.assetboard`, version 1.4.5
  and versionCode 42; its signature matches the established release certificate
  `150609abe6d702ca6c254d3b413dd51c361884b9367461ab40f2c1a756d6f95d`.

No Android device was connected (`adb devices -l` was empty), so a physical-device upgrade
and before/after comparison of real user backups were not performed. AI streaming was
checked with a local SSE server and UI tests, not a user's configured live provider.

Database schema 15 and backup format 17 are unchanged. OkHttp 5.5.1 is the new runtime
dependency for streaming HTTP and cancellation. The release script verifies the APK's
certificate against the established release key and uploads its matching R8 mapping
before the release is considered published.
