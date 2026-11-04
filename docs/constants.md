# 常量地图

想改某个地址、默认值或迁移对照值时，动哪几处。都是编译期常量，改完要重新构建发布，用户侧的自定义值（设置页填写的服务器地址、AI key、模型）会覆盖默认值，不受这里影响。

| 想改什么 | 动哪里 | 说明 |
|---|---|---|
| 应用内升级读的仓库（fork 必改） | `app/build.gradle.kts` 的 `buildConfigField "UPDATE_REPO"` | 「检查更新」读这个仓库的最新非预发布 Release 里第一个 `.apk` |
| 同步 / 账号服务的默认地址（迁移服务器） | `app/build.gradle.kts` 的 `buildConfigField "SYNC_BASE_URL"` | 服务端部署见 [`server/README.md`](../server/README.md)；登录页允许用户填别的地址 |
| 旧服务器地址的升级清洗 | `data/sync/SyncServer.kt` 的 `LEGACY_SYNC_SERVER_URL` | `ProfileStore` 与 `SettingsStore` 引用它，把老安装里存的旧地址搬到 `SYNC_BASE_URL`；只有服务器搬家时才有意义 |
| AI 助手默认端点与模型 | `data/SettingsStore.kt` 的 `DEFAULT_AI_BASE_URL` / `DEFAULT_AI_MODEL` | OpenAI 兼容端点；设置页可覆盖，key 也存在设置里（Keystore 加密） |
| 备份格式版本 | `data/backup/BackupManager.kt` 的 `FORMAT_VERSION` | 与 `AppDatabase.SCHEMA_VERSION` 是两个独立版本号，改动流程见 CLAUDE.md「容易踩的坑」 |

同步服务的端口、证书、部署与排障见 [`server/README.md`](../server/README.md)。
