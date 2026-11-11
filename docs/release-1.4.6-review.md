# Release 1.4.6 review

Version 1.4.6 uses versionCode 43, above 1.4.5's 42. It turns the diary's generate action into a
generate-or-update pass that carries the editor's latest text and the day's own records, keeps one
streaming preview with a live character count and a stop action, collapses the diary and note
formatting bars to one row, and connects note formatting with the outline's headings.

Validation:

- `scripts/dev-test.sh --all --rerun` passed: 846 Android tests, all 155 screenshot comparisons,
  release lint against its baseline, and 73 web tests.
- Ten intended screenshot changes were inspected and accepted using `--accept`; no baselines were
  re-recorded. The seven images from the live work are in
  `build/screenshots-replaced/20261111-134031/`; the three diary header images, whose AI action
  changed from a separate wand to the shared sparkle icon, are in
  `build/screenshots-replaced/20261111-134944/`. The final full gate reports zero changed, new or
  stale screenshots.
- The diary screenshots wait for the weather request to finish before capture. In the full suite
  the initial `Idle` state could otherwise stand in for the final "no location" state and move the
  chip row; the comparison threshold alone did not surface it until another change pushed the
  difference over 0.5%. Module and round-two diary shots now wait for the same terminal chip.
- Regression tests cover generate-on-empty versus update-on-existing requests carrying the editor
  text, single progress feedback with a live preview, stop before completion, undo in one step,
  stale-session protection, outline-driven heading organization, and rejecting edits that change
  links, code or checklist states. The pre-existing 846-test suite is otherwise unchanged.
- Database schema 15 and backup format 17 are unchanged; no data model or migration was touched.
  The format-17 fixture restore test runs in the gate.
- `assembleRelease` succeeded. The APK metadata reports `com.assetboard`, version 1.4.6 and
  versionCode 43; its signature matches the established release certificate
  `150609abe6d702ca6c254d3b413dd51c361884b9367461ab40f2c1a756d6f95d`.

No Android device was connected (`adb devices -l` was empty), so a physical-device upgrade and a
before/after comparison of real user backups were not performed. AI streaming and the update pass
were checked with deterministic test gateways, not a user's configured live provider, so the
model's actual prose and the on-device keyboard experience still need a hands-on check.
