# 0.9.13 release review — 2026-10-25

A fix release for the update check, from a phone that read "请求太频繁" on a manual check.
VersionCode is **23**. Database version **12** and backup format **13** are unchanged.

## What went wrong

The check read the latest release from GitHub's API without signing in, which allows 60 requests
an hour per public address. On mobile data many phones share one carrier address, so the hour is
often spent by others: one tap got 403. Every 403 or 429 also read "请求太频繁", a mirror turning a
download down included, and the check had no other way to the release. The phone's diagnostics had
nothing on it, since failed updates were not logged.

## What changed

- The check falls back to github.com when the API refuses or fails: `releases/latest` redirects to
  the newest tag (drafts and pre-releases left out, as the API does), the APK's URL follows from
  the name `scripts/publish-release.sh` gives it (a test reads the script to hold the two
  together), and the notes are the tag's CHANGELOG section. Neither counts against the quota.
- Only GitHub's spent quota (403/429 with `X-RateLimit-Remaining: 0`) reads as such, with the time
  it resets; any other refusal names the host and the code.
- A download that fails through the mirror is tried straight from GitHub; progress uses the
  response's length when the release's size is not known.
- Failed checks and downloads go into the diagnostics. The exits already seen are now saved to disk
  before they are written: a crash on start used to come back before the asynchronous save landed,
  and every start wrote the same exits again (the phone's log had them six times over).

## Verification

- Gate (`scripts/dev-test.sh --all`, after the version bump): 541 tests, 0 failed, 130 screenshots
  unchanged, lint passed. `ReleasePageTest` covers the redirect, the APK name against the publish
  script, the notes and the wording of each refusal.
- The API at its limit for this network (403, remaining 0, spent on purpose from the host), on the
  Android 15 emulator: a debug build named 0.9.11 found 0.9.12 from the release page on its daily
  check, with the 0.9.12 notes in full, and downloaded it with progress (2% to 87%, then complete);
  the install was refused as a debug build must be. The signed 0.9.13 answered 当前 0.9.13 就是最新的
  both with the quota spent and with it back, so the minified build takes the fallback too.
- Upgrade in place on the same emulator: the published 0.9.12 with the last review's data (one bill
  included) exported, the signed 0.9.13 went on with `adb install -r`, opened to the same net worth
  (6,015.50) and exported again. `scripts/compare-backups.py` reports the ledger identical (groups
  2, accounts 2, snapshots 1, valuations 4, snapshotItems 2, flows 1). No crash in logcat.
- Not checked on the phone that reported it: its 0.9.11 has the old check, so it needs one update
  by another way (another network, an hour's wait, or the APK from the release page in a browser).
