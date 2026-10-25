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
  "formatVersion": 9,      // 备份格式版本，读取时校验
  "schemaVersion": 8,      // 数据库 schema 版本
  "exportedAt": 1760000000000,
  "groups": [...],
  "accounts": [...],
  "snapshots": [...],      // 盘点
  "valuations": [...],     // 每个账户每次估值
  "snapshotItems": [...],
  "flows": [...],          // 流水；记账的支出（EXPENSE）、收入（INCOME）也在这里，只有一边账户，category 是分类键（food、salary……）
  "todoLists": [...],
  "todoSections": [...],
  "todos": [...],
  "checklistItems": [...],
  "diaryEntries": [...],
  "diaryDrafts": [...],    // 保存冲突时留下的草稿
  "habitChecks": [...],
  "healthProfile": {...},  // 性别、生日、身高、目标体重；可为 null
  "bodyMeasurements": [...], // 每天至多一条：体重（克）、体脂率（万分之一，可为 null）
  "foodEntries": [...],    // 吃了什么，一天任意条：包装的份量与吃了多少（或份数、整份）、能量（cal = 1/1000 kcal）、营养素（mg）
  "noteTopics": [...],     // 笔记主题，一层
  "notes": [...],          // 笔记，含回收站里的；position 是手动排序的位置，小的在前
  "noteSources": [...],    // 笔记摘自哪里（note_id, kind, ref）：DAY 是某天的日记，WEEK 是某周的复盘（ref 为该周周一）
  "noteDrafts": [...],     // 笔记保存冲突时留下的草稿
  "weeklyReviews": [...],  // 周复盘，week_start 为该周周一；未删除的每周至多一篇
  "weeklyDrafts": [...],   // 周复盘保存冲突时留下的草稿
  "netWorthGoal": 100000000, // 目标资产（分）；没设为 null
  "habits": [...]          // 自定义的每日打卡（名称、图标、颜色、顺序、起止日）；没改过时为空，读作内置的五项
}
```

- 版本 1 只有资产部分；版本 2 增加待办与日记；版本 3 增加每日指标；版本 4 增加健康档案与身体数据；版本 5 增加笔记（主题、笔记、来源、草稿）；版本 6 增加笔记的手动排序（`position`）与周复盘（复盘、草稿）；版本 7 增加目标资产（`netWorthGoal`），读旧版本时目标被清空；版本 8 增加自定义的每日打卡（`habits`），读旧版本时回到内置的五项；版本 9 增加饮食记录（`foodEntries`），读旧版本时饮食记录被清空；版本 10 增加日记的心情与打卡的休息日；版本 11 增加打卡标准（`rubric`）；版本 12 增加待办的提前提醒（`todos[].remindBefore`，提醒时刻前多少分钟，0 为准时），读旧版本时都是准时提醒；版本 13 增加记账（`flows[].kind` 可为 `EXPENSE` / `INCOME`，`flows[].category` 为分类键，其余流水为 null），读旧版本时没有记账。读旧版本时缺的表为空，确认框会提示当前笔记或周复盘将被清空；版本 5 的笔记没有 `position`，恢复时按更新时间从新到旧排好。**新版本能读旧版本**，旧版本拒绝新版本（`formatVersion` 超出范围时直接报错，且不改动数据库）。
- 导出的所有表在**一个只读事务**里读取，保证文件内部一致：不会出现「盘点已写、条目还没写」的半个快照。
- 备份包含回收站和日记草稿——用户删掉的东西在 30 天内还在回收站里，它也应该被备份。

## 原子替换

系统文件提供者不保证 `rename` 可用，也不保证写入不中断。`replaceInFolder` 的顺序是：

1. 先写一份独有的 `*.partial-<随机>.json`（完整的新内容）。
2. 旧文件改名为 `*.previous-<随机>.json`。
3. 把 partial 改名为正式文件名。
4. 成功则删除 previous；任一步失败都尽量把旧文件放回原位。

侧文件名带随机后缀，两次备份不会互相覆盖对方留下的文件；备份之间串行执行，同一时刻只有一个在写。最坏情况下，目录里留下的是一份**旧文件或完整的新侧文件**，不会出现被截断或缺失的备份。上一次失败留下的侧文件，会在下一次备份成功后清理。

## 恢复

恢复是**整库覆盖**，不做合并。流程：

1. 校验 `formatVersion` 与 `schemaVersion`（不匹配直接拒绝，库不变）。
2. 导出当前数据为恢复前副本。
3. 把编辑器里未保存的日记、笔记和周复盘文字交给恢复协调器落盘（版本号对得上写进正文，对不上存成草稿）。
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
