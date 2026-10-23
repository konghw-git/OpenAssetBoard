# CLAUDE.md

日盈（asset-board）：Kotlin + Jetpack Compose 的本地 Android App。规则与约定以 `CONTRIBUTING.md`、`docs/` 为准，这里只记 AI 会话里验证改动的做法和容易踩的坑。

## 验证改动

- 用 `scripts/dev-test.sh`（用法见 `CONTRIBUTING.md`「改动时怎么验证」）：先 `--compile`，再只跑相关测试类，看界面用 `--shots`，收尾 `--all`。脚本会自己加载 `~/.local/share/dev-dl/android-env.sh`（JDK 21、Android SDK），不要用系统的 Java 11。
- 修 bug 先写复现测试，改动前跑一次确认失败。想对照原来的代码，可以临时把文件换成 `git show HEAD:<path>` 的内容跑测试，再换回来，不要用 stash 或 checkout 动用户的工作区。
- 截图只看 `app/build/outputs/roborazzi/*_actual.png`，没有输出就是和基准一致。不要擅自重录基准（`-Proborazzi.test.record=true`），基准是用户本机比对用的。

## 容易踩的坑

- 从 Windows 侧调用时，PowerShell 会吃掉 `wsl bash -lc "…"` 里的 `$`、引号和 `rm -rf` 之类；超过一行的命令写成脚本文件再 `wsl bash script.sh`，源码修改用编辑工具而不是 sed。
- 通过 `\\wsl.localhost\…` 路径编辑会丢掉可执行位，改完 `scripts/*.sh` 要重新 `chmod +x`。
- 撤销条（snackbar）测试要手动推时钟（`mainClock.autoAdvance = false` 后逐帧推进）：等待空闲会把 5 秒的撤销时限一下跑完。
- 可点击的卡片会合并子节点的语义，量子元素位置要用 `useUnmergedTree = true`。
- 行在 `HorizontalPager` 的页里：焦点或 bring-into-view 落到页外、或文本框自己的横向滚动溢出，都会把页面带着横移（资产页改余额抖动就是这个原因，见 `RowGestureTest`）。
- 改数据模型要同时升 `AppDatabase.SCHEMA_VERSION`、`Migrations`、迁移测试、`BackupManager.FORMAT_VERSION`，并用 `WRITE_BACKUP_FIXTURE=1` 生成新格式的备份样本（见 `BackupFixtureTest`）；编译会自动导出 `app/schemas/` 的新 schema。
