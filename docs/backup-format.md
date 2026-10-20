# 备份与恢复

备份是这个 App 的一级需求：没有云同步，手机丢了或卸载了，数据就只剩这个文件。

相关代码：`data/BackupManager.kt`、`scripts/compare-backups.py`
对应测试：`RestoreTest`、`BackupReplaceTest`、`MigrationTest`

## 文件

- 一个 JSON 文件 `asset-board-backup.json`，写到你通过系统文件选择器指定的文件夹。
- **每次备份覆盖同一个文件**，目录里不会越积越多。每天自动备份一次（WorkManager），与手动备份写同一份。
- 恢复前会把当前数据另存为恢复前副本，可以选择回到任意一次恢复之前的状态。

## 格式

```jsonc
{
  "formatVersion": 4,      // 备份格式版本，读取时校验
  "schemaVersion": 4,      // 数据库 schema 版本
  "exportedAt": 1760000000000,
  "groups": [...],
  "accounts": [...],
  "snapshots": [...],      // 盘点
  "valuations": [...],     // 每个账户每次估值
  "snapshotItems": [...],
  "flows": [...],          // 流水
  "todoLists": [...],
  "todoSections": [...],
  "todos": [...],
  "checklistItems": [...],
  "diaryEntries": [...],
  "diaryDrafts": [...],    // 保存冲突时留下的草稿
  "habitChecks": [...],
  "healthProfile": {...},  // 性别、生日、身高、目标体重；可为 null
  "bodyMeasurements": [...] // 每天至多一条：体重（克）、体脂率（万分之一，可为 null）
}
```

- 版本 1 只有资产部分；版本 2 增加待办与日记；版本 3 增加每日指标；版本 4 增加健康档案与身体数据。**新版本能读旧版本**，旧版本拒绝新版本（`formatVersion` 超出范围时直接报错，且不改动数据库）。
- 导出的所有表在**一个只读事务**里读取，保证文件内部一致：不会出现「盘点已写、条目还没写」的半个快照。
- 备份包含回收站和日记草稿——用户删掉的东西在 30 天内还在回收站里，它也应该被备份。

## 原子替换

系统文件提供者不保证 `rename` 可用，也不保证写入不中断。`replaceInFolder` 的顺序是：

1. 先写 `*.partial.json`。
2. 旧文件改名为 `*.previous.json`。
3. 把 partial 改名为正式文件名。
4. 成功则删除 previous；任一步失败都尽量把旧文件放回原位。

最坏情况下，目录里留下的是**旧文件或一份完整的侧文件**，不会出现被截断的备份。

## 恢复

恢复是**整库覆盖**，不做合并。流程：

1. 校验 `formatVersion` 与 `schemaVersion`（不匹配直接拒绝，库不变）。
2. 导出当前数据为恢复前副本。
3. 把编辑器里未保存的日记草稿交给恢复协调器落盘。
4. 合上写入闸门，拒绝所有新写入（见 [写入闸门与数据纪元](write-gate.md)）。
5. 在一个事务里清空并重建全部表；任何一步失败整体回滚。
6. 纪元 +1，重排提醒，刷新小组件。
7. 打开闸门。

## 升级前后的一致性检查

`scripts/compare-backups.py` 用来回答一个很实际的问题：升级 App 之后，账本数据有没有被悄悄改动？

```bash
# 升级前
# App 内导出备份，并把 asset-board-backup.json 另存为 before.json（下次备份会覆盖它）
# 升级后（adb install -r）
python3 scripts/compare-backups.py before.json asset-board-backup.json
```

脚本比较两次导出里账本相关表的内容：**必须完全一致**。新增的待办或日记不影响结论，账本部分不允许有任何差异。健康表另列一组比对结果，旧备份里没有健康表时不算失败。这个脚本是发布前的最后一道人工检查。
