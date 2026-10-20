# 贡献指南

感谢你愿意花时间。这个项目的目标很小：**把一次盘点做到 60 秒以内，把待办和日记做得足够本地、足够可靠**。改动请围绕这个目标。

## 提 issue 之前

- 先在 [已有 issue](https://github.com/konghw-git/OpenAssetBoard/issues) 里搜一下。
- 提醒不准时，请先看 App 内的「设置 → 提醒设置检查」，并把清单状态写进 issue。
- **不要**在 issue 里贴真实的备份文件或截图里的真实余额。用一个编造的小例子即可。

## 提 PR 之前

```bash
./gradlew testDebugUnitTest
```

全部通过。迭代时可以用 `--offline` 节省时间。

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

1. 更新 `CHANGELOG.md` 与 `app/build.gradle.kts` 里的 `versionCode` / `versionName`。
2. 跑全量测试，并用 `scripts/compare-backups.py` 对比升级前后的账本。
3. `./gradlew assembleRelease`，在真机上覆盖安装验证。
4. `scripts/publish-release.sh`：打 tag、推送、创建 Release 并上传 `OpenAssetBoard-x.y.z.apk`（需要 `gh auth login`）。应用内升级读的是最新一个非预发布 Release 里第一个 `.apk`，tag 必须是 `v` + `versionName`。
