# asset-board

A personal balance-sheet dashboard for Android: net worth, asset allocation, periodic stocktakes,
and investment flows with cumulative P&L. Everything stays on the phone; backups are JSON files in a
folder you pick.

Design notes and the calculation rules live in the Obsidian note
`2026-10-18 资产看板 App - 需求对齐与数据模型.md`. Section 9 of that note is mirrored by
`app/src/test/.../LedgerTest.kt` and `RepositoryTest.kt`.

## Build

```bash
scripts/setup-toolchain.sh          # JDK 21, Android SDK 36, Gradle; accepts the SDK licenses
scripts/create-keystore.sh          # release key in ~/.config/asset-board (back it up!)
source ~/.local/share/dev-dl/android-env.sh

./gradlew testDebugUnitTest         # calculation and repository rules
./gradlew assembleRelease           # app/build/outputs/apk/release/app-release.apk
```

Behind a proxy, pass it to Gradle explicitly, e.g.
`./gradlew -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 assembleRelease`.

## Install and upgrade

Always install the **release** build and upgrade in place (`adb install -r`, or open the new APK on
the phone). The debug build uses a separate package (`com.assetboard.debug`) so it can never replace
the real install. Never sign with a different key: Android would refuse the upgrade, and uninstalling
wipes the local database.

## Layout

| Path | What |
|---|---|
| `domain/Ledger.kt` | Pure calculation rules: balance, net invested, P&L, stocktake totals |
| `domain/LedgerData.kt` | In-memory copy of the database with indexes |
| `domain/Rules.kt` | Constraints Room cannot express |
| `data/LedgerRepository.kt` | Every write: validation, sign handling, one transaction each |
| `data/BackupManager.kt` | JSON export, full-overwrite restore, daily backup worker |
| `ui/` | Compose screens; `ui/components` holds the charts and shared widgets |
| `widget/` | Glance home-screen widget |
