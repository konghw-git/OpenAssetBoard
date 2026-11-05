# Release 1.4.2 review

The release fixes asset privacy masking, sync version ordering, offline replacement, delayed uploads
and health-profile deletion. Backup format 17 marks health-profile deletion with a tombstone; older clients must
upgrade before syncing it. The server accepts the new format without a change.

The maintainer explicitly requested skipping the physical-device upgrade and before/after backup
comparison. `scripts/dev-test.sh --all` passed: 818 Android tests with no failures, all 153
screenshots unchanged, lint, and 57 web tests. The release script verifies the signing certificate.
