# Security policy

## Reporting

Please **do not open a public issue** for a security problem. Use GitHub's
[private vulnerability reporting](https://github.com/konghw-git/OpenAssetBoard/security/advisories/new)
instead. Include what you did, what you observed, and the app version.

## Scope

The app is entirely local: there is no server, no account, and no data leaves the phone except the
two optional requests described in the README (weather lookup and holiday updates). The parts worth
attacking are therefore:

- The backup file: a plain JSON file with no encryption. Anyone who can read it can read everything.
  This is a deliberate trade-off for portability, not a vulnerability.
- The restore path: imported files are validated before they touch the database.
- Exported components: `AlarmActivity`, receivers and widget providers are declared in the manifest.

Reports about missing encryption of the on-device database or the backup file are known trade-offs;
reports about the restore path accepting data it should reject are not.
