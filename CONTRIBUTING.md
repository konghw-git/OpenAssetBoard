# 贡献指南

感谢你愿意花时间。这个项目的目标很小：**把一次盘点做到 60 秒以内，把待办和日记做得足够本地、足够可靠**。改动请围绕这个目标。

## 提 issue 之前

- 先在 [已有 issue](https://github.com/konghw-git/OpenAssetBoard/issues) 里搜一下。
- 提醒不准时，请先看 App 内的「设置 → 提醒设置检查」，并把清单状态写进 issue。
- **不要**在 issue 里贴真实的备份文件或截图里的真实余额。用一个编造的小例子即可。

## 改动时怎么验证

所有检查都走 `scripts/dev-test.sh`：每个类一行结果，失败时列出前几行堆栈（完整日志在 `/tmp/asset-board-test.log`）。由近及远，前四步只跑改动碰到的部分，最后一步是唯一的门禁：

| 步骤 | 命令 | 本机大约耗时 |
|---|---|---|
| 先编译 | `scripts/dev-test.sh --compile` | 几秒 |
| 改动所在的测试类，可以点到方法 | `scripts/dev-test.sh WeekStatsTest 'TodoNestTest.slidRightItGoesIntoTheTodoAbove'` | 纯 JVM 类约 8 秒，Robolectric 类约 12 秒 |
| 看界面：渲染并比对相关截图 | `scripts/dev-test.sh --shots 'ModuleScreenshotTest.todoList'` | 约 20 秒 |
| 除截图类以外的全部 | `scripts/dev-test.sh --fast` | 约 40 秒 |
| **门禁**：全部测试、全部截图比对、lint | `scripts/dev-test.sh --all` | 约 4 分钟；同一棵树再跑几秒 |

- **先写能复现问题的测试，在改动前跑一次确认它失败**，再改代码让它通过。计算规则放 `domain/` 的纯 JVM 测试（最快），仓储放 `data/` 的 Robolectric 测试，手势和布局用 Compose UI 测试断言位置、焦点、数据结果，而不是只看截图。
- **门禁只有一个**：提交前是 `--all`，`scripts/publish-release.sh` 里跑的也是它。Gradle 构建缓存按输入命中，同一棵树过了一次门禁，发布时再跑直接拿回结果（实测 9 秒）。换了参数（点名、`--shots`、`--fast`）就是另一组输入，各自缓存。要强制重跑加 `--rerun`。
- **截图只比对，不重录**。门禁列出变化的、新增的、以及不再有测试画的（过期）基准图，新旧对比在 `app/build/outputs/roborazzi/<名字>_compare.png`。确认是有意改动后跑 `scripts/dev-test.sh --accept`：这次画出的图收为基准，过期的移走，旧图留在 `build/screenshots-replaced/<时间>/`。不用再跑全量录制，之后再跑一次 `--all` 确认即可。刚克隆时没有基准图（`app/screenshots/` 不入库），先 `--accept` 一次。
- **截图里的时间是固定的**：`PinnedClock` 把「今天」定在 2026-10-25（周日）20:30，测试时区固定为 Asia/Shanghai，所以基准图不会隔天过期。应用代码一律经 `domain/AppClock` 读时间，门禁会拦下直接调用 `System.currentTimeMillis()`、`LocalDate.now()` 的代码。截图测试里造演示数据也用 `AppClock`。
- **一个画面一张基准图**：要交互的截图测试用 `show()` 打开页面，不要再拍一张和页面本身一样的图。行为由 Compose UI 测试断言，截图测试只负责画面。
- README 的横幅和图标由基准图拼成，不是基准图，需要时用 `scripts/dev-test.sh --brand` 重画。
- 截图类本身也有断言，`--fast` 只用于中途迭代，收尾必须过 `--all`。
- 并行多个测试 JVM 实测几乎不提速（73 秒变 65 秒左右），4 个并行时还出现过偶发失败，所以保持单进程。

## 提 PR 之前

`scripts/dev-test.sh --all`（或 `./gradlew testDebugUnitTest`）全部通过。

### 改动的边界

| 改动 | 要求 |
|---|---|
| 计算规则（余额、净投入、盈亏、趋势） | 写在 `domain/` 的纯函数里，并在同目录的测试中新增用例；不要在 UI 里算 |
| 数据写入 | 必须经过 `data/` 的仓储方法与 `WriteGate.write`，不要直接调 DAO |
| 数据模型 / 备份格式 | 同时更新 `app/schemas/`、`Migrations.kt`、迁移测试；备份改动还要考虑旧版本兼容 |
| 界面 | 跟随现有 Compose 写法与 `ui/components` 里的共享控件；不要在页面里自造颜色和间距 |
| 新的依赖 | 先说明为什么现有代码不够；优先能减小 APK 或已有等价实现的方案 |

### 代码风格

- 沿用周围代码的命名、注释密度和写法。注释解释「为什么」，不复述「做了什么」。
- 注释与提交信息使用英文；界面文案是中文。
- 提交信息用一句话说明改了什么，例如 `Fix restore rejecting a stale epoch`。

## 界面文字

目前界面是中文硬编码，没有做 i18n，也不打算在近期做。README 有中英两版，这是唯一的例外。

## 发布流程（维护者）

1. 更新 `CHANGELOG.md` 与 `app/build.gradle.kts` 里的 `versionCode` / `versionName`。版本号会编进 BuildConfig，所以先改版本号再过门禁，第 4 步才能直接拿到缓存结果。
2. `scripts/dev-test.sh --all` 通过，并用 `scripts/compare-backups.py` 对比升级前后的账本。
3. `./gradlew assembleRelease`，在真机上覆盖安装验证。
4. 提交后运行 `bash scripts/publish-release.sh`：重跑同一道门禁（树没变就命中缓存），然后签名构建、校验证书、打 tag、推送、创建 Release 并上传 `OpenAssetBoard-x.y.z.apk`（需要 `gh auth login`）。应用内升级读的是最新一个非预发布 Release 里第一个 `.apk`，tag 必须是 `v` + `versionName`。
