# Security policy

## Reporting

Please **do not open a public issue** for a security problem. Use GitHub's
[private vulnerability reporting](https://github.com/konghw-git/OpenAssetBoard/security/advisories/new)
instead. Include what you did, what you observed, and the app version.

## Scope

The app works entirely locally by default. The optional account and sync server lives in `server/`
and is self-hosted; signing in uploads the whole backup to that server over HTTPS, where it is stored
unencrypted. Without signing in, no data leaves the phone except the optional requests described in
the README (weather, holidays and update checks). The parts worth attacking are therefore:

- The backup file and the on-device database: both are unencrypted. Anyone who can read them can read
  everything. This is a deliberate trade-off for portability, not a vulnerability.
- The restore path: imported files, including snapshots downloaded from the server, are validated
  before they touch the database.
- The refresh token: encrypted with the Android Keystore before it is written to preferences. Reports
  about the access token living only in memory are not vulnerabilities.
- The self-hosted server: authentication, token rotation and per-user isolation. Snapshot bodies are
  opaque to the server but stored plaintext by design; reports about missing end-to-end encryption are
  known trade-offs.
- Exported components: `AlarmActivity`, receivers and widget providers are declared in the manifest.

Reports about missing encryption of the on-device database, the backup file or server snapshots are
known trade-offs; reports about the restore path accepting data it should reject, or one account
reading another's snapshot, are not.
