# 合并语义与不变量（M0 设计产出）

状态：**M0 完成稿**（2026-11-05）
本文档是 [`sync-merge-plan.md`](sync-merge-plan.md) v2 §6.0 的 M0 交付物汇总，也是 M1（数据模型/写路径）与 M2（双端 merge 实现）的**唯一语义依据**。凡本文档与方案正文冲突，以本文档为准（并回改方案）。

事实基线：三路并行代码盘点（Android 数据层 / 校验与账本 / 网页+服务端+同步管线），2026-11-05，矩阵标注 `file:line` 证据。

---

## 0. 决策记录

### 0.1 §9 六项待确认，按推荐默认拍板（2026-11-05 开工时）

| # | 决策 | 取值 |
|---|---|---|
| 1 | 界面偏好 | 主题/隐藏金额留 localStorage（网页）；写明为 R2 的例外 |
| 2 | 周期档位 | 5（仅前台）/ 15 / 30 / 60 分钟 / 关闭，默认 15；5 分钟档文案注明仅前台 |
| 3 | 编辑后防抖 | 安卓 10s 静默上传；可见性 SLA = 30 秒（两端前台），替换 v1 的「5 秒」 |
| 4 | 历史策略 | 服务端 30 份 + 网页保存节奏合并防抖 + 安卓新增历史版本 UI；按时间保留需改服务端，不做 |
| 5 | 账本口径 | 全并集（流水/估值/盘点按行合并）+ 合并后「口径漂移体检」徽标；不阻塞、不改数据 |
| 6 | 败者归档 | 文本三表（diaryEntries/notes/weeklyReviews）进草稿；其余表靠服务端历史 + 合并前副本 |

### 0.2 M0 细化决定（v2 方案未写死的部分）

| # | 决定 | 内容 |
|---|---|---|
| A | **三元组比较器** | `(changedAt, kind, changeId)`，kind 等级 `repair > tombstone > row`；全同再比 canonical 内容哈希。解决：修复派生行对陈旧副本的支配性（收敛）、去重墓碑与败者行同版本的平局（墓碑胜） |
| B | **修复派生戳** | 合并修复改动的行必须重新打戳：`changedAt = max(迫使修复的各行戳, 被改行原戳)`，`changeId = "R:" + hash(规则名, 输入行键+版本)`。禁止「改内容不改戳」——否则第三端陈旧副本合并时收敛不了 |
| C | **changeId 前缀约定** | 普通变更：随机唯一串（UUID/ULID，不得以 `R:`/`T:` 开头）；修复派生：`R:`；墓碑（含自然键去重）：`T:`；升级回填：`legacy:`（按普通行参与比较）。比较时先比 kind 等级再比字典序 |
| D | **replace 的离线语义** | 恢复/导入 = replace，须能离线执行（本地立即替换）+ 记录 `pendingReplace{T}`；下次同步拉到远端后补墓碑（见 §5.2） |
| E | **升级前硬删除的首并复活** | 无法区分「升级前删除」与「从未存在」，如实接受并写入已知限制（§6.4）：首并可能复活，用户再删一次即写墓碑；pre-merge 副本兜底 |
| F | **父删子活 = 比新旧** | 子行内容戳晚于父墓碑 ⇒ 父行以 `R:` 戳复活；否则子行随父删（细则 §3.2-R10） |
| G | **同刻流水被锚点吸收** | flow.at == valuation.at 的合并产物保留双行，但流水不进余额（锚点语义：估值已含同刻及以前流水），记 adjustments 如实告知，不改数据（§3.2-R7） |

### 0.3 代码盘点确认的事实基线（与 v1 直觉不同处）

- **表数**：备份 JSON = 24 表（含 countdowns）+ `netWorthGoal` 标量；Room 共 27 表（另 holidays、holiday_years、app_meta 为设备本地）。`docs/backup-format.md` 的 jsonc 示例漏列 `countdowns`（M5 修）。
- **时间戳列很不齐**：有 `updated_at` 的只有 8 张（diaryEntries/weeklyReviews/notes/countdowns/foodEntries/bodyMeasurements/healthProfile/holiday_years）；**account_groups 与 habits 完全没有时间戳列**；todo 族用 `revision`/`state_seq` 计数器替代；ledger 六表只有 `created_at`/`at`。
- **validate 只在恢复/同步下载路径跑**（BackupManager.kt:547-555,578-581），且**不查**两条写入侧约束：valuation 的 `at >= baselineAt`、flow 与 valuation 同刻互斥（仅 LedgerRepository.kt:261-268 / Rules.kt:36-40 写入侧查）⇒ **合并层必须自己保证**（§3.2-R6/R7）。
- **现 contentHash**：exportedAt 置 0 + 对象键按声明序（不重排）+ 数组按元素 `toString()` 排序 + SHA-256（BackupManager.kt:213-226）；web PUT 不带 X-Content-Hash → 服务端存原始体哈希（api.go:593-596）⇒ 双端口径不一（现存 bug，M2 按 §7 重实现）。
- **同步现状**：周期同步 6 小时（AssetBoardApp.kt:223-228）；安卓无编辑后上传；web 60s 全量轮询（app.js:220）；web 把整个 state 存 IndexedDB `gain-web`（store.js:9-18，M4 移除）。
- **服务端已有**：`ASSETBOARD_HISTORY` 环境变量（main.go:102，默认 10）、`GET /v1/sync/revisions[/n]`（api.go:632-677）、PUT 校 If-Match 单整数 revision（api.go:760-775）。BFF 响应白名单不含 `Date`（server.mjs:95）。
- **草稿 id 现为 UUID**（newId()），草稿表是 TEXT 主键，可承载确定性字符串 id。

---

## 1. 行键规范与字段分类

### 1.1 rowKey 规范（全表统一）

- **rowKey 规范字符串** = 备份 JSON 字段名（表名）+ `#` + 主键各分量按**声明顺序**用 `|` 连接；分量值为 JSON 标量的规范串（字符串原样、整数十进制、null 写 `~`）；分量内出现 `|`、`#`、`%` 时百分号转义（`%7C`/`%23`/`%25`）。
  - 例：`habitChecks#2026-11-05|meditation`、`snapshotItems#<snapshotUuid>|<accountUuid>`、`healthProfile#self`、`notes#<uuid>`。
- 表名统一用 **BackupFile 字段名**（如 `habitChecks`，不用 Room 表名 `habit_checks`）；行内字段是 **camelCase**（Kotlin 属性名，BackupManager.kt:82-113），不是 snake_case。
- 墓碑、草稿归档、修复派生的 changeId 里引用行时，一律用 rowKey 规范字符串。

### 1.2 字段分类（写路径打戳规则）

原则：**内容字段**变化推 `(changedAt, changeId)`；**状态字段**变化只推 `(stateAt, stateId)`，不抢内容版本。只有 4 张表有状态戳：

| 表 | 状态字段（只推状态戳） | 说明 |
|---|---|---|
| diaryEntries | `weather`, `tempMin`, `tempMax`, `city`, `lat`, `lon`, `weatherAt`, `weatherRev` | 写天气不抢正文（writeWeather 不碰 updatedAt，TodoDao.kt:154-158）；**mood 是内容字段**（setMood 推 updatedAt，TodoDao.kt:147-148） |
| notes | `reviewedAt`, `pinned` | 「打开看了一下」不抢正文（setReviewed/setPinned 明示不动 updatedAt/bodyRev，NoteEntities.kt:173-176） |
| todos | `remindedPeriod`, `snoozeAt` | 提醒投递不抢任务版本（markReminded 故意不 bump revision/stateSeq，TodoRepository.kt:580-587） |
| countdowns | `pinned` | 置顶不抢正文 |

其余 20 张同步表无状态戳，整行只有一个内容戳。软删标记 `deletedAt`/`deleteBatch`/`removedOn` 属于**内容字段**（删除与编辑统一比新旧）。`bodyRev`/`revision`/`stateSeq` 是并发计数器，**不参与比较**，合并胜者行保留其值、修复重打戳时随行取胜者值。

顶层 `netWorthGoal` 伴随新元数据 `netWorthGoalAt` + `netWorthGoalChangeId`（app_meta 新 key）：值为 **null 也是带戳的值**（清空目标是可合并变更；现 GoalRepository.set(null) 直接删键，M1 改为值置空+推戳，GoalRepository.kt:13-23）。

**批量写回先 diff**（habits 整表 upsert、moveGroup、reorderTopics、reorderIn 全量重排等）：逐行比对内容字段，**无变化不推戳**。

### 1.3 全表矩阵（行键 / 主键 / 外键 / 时间戳 / 自然键 / 首升回填源）

「回填」= Room 迁移 14→15 与读旧备份时该行 `changedAt` 取哪些列的 **max**（§6.1）。

| # | 备份字段 | rowKey 尾段 | 主键 | 外键（动作） | 时间戳列 | 自然键 | 回填 changedAt / stateAt |
|---|---|---|---|---|---|---|---|
| 1 | groups | id | id | — | **无** | 无（允许同名，UI 不限） | `0`（无源，弱点：首并任意决胜，见 §6.4） |
| 2 | accounts | id | id | groupId→groups **RESTRICT** (Entities.kt:28-33) | createdAt | 无 | createdAt |
| 3 | snapshots | id | id | — | **at（UNIQUE，Entities.kt:62）** | **at** | at |
| 4 | valuations | id | id | accountId→accounts RESTRICT；snapshotId→snapshots **CASCADE** (Entities.kt:73-86) | at, createdAt | **(accountId, at) UNIQUE** (Entities.kt:87) | max(at, createdAt) |
| 5 | snapshotItems | `snapshotId\|accountId` | 复合 (snapshot_id, account_id) (Entities.kt:104) | snapshotId→snapshots CASCADE；accountId→accounts RESTRICT；valuationId→valuations CASCADE (Entities.kt:105-124) | 无 | 同主键 | 随父快照戳（snapshot.at） |
| 6 | flows | id | id | from/to→accounts **RESTRICT** (Entities.kt:163-176) | at, createdAt | 无（同刻唯一仅写入侧） | max(at, createdAt) |
| 7 | todoLists | id | id | — | archivedAt, createdAt, deletedAt (TodoEntities.kt:21-24) | 无 | max(三者) |
| 8 | todoSections | id | id | listId→todoLists **CASCADE**（跨批次，TodoEntities.kt:31-32） | deletedAt（无 createdAt） | 无 | deletedAt ?? `0`（弱点） |
| 9 | todos | id | id | listId→todoLists SET_NULL；sectionId→todoSections SET_NULL；repeatSourceId→todos SET_NULL (TodoEntities.kt:50-54) | createdAt, completedAt, deletedAt, snoozeAt, repeatEndedAt | 完成记录 **(repeatSourceId, planDate)** | max(五者) / stateAt = snoozeAt ?? 0（弱点） |
| 10 | checklistItems | id | id | todoId→todos **CASCADE** (TodoEntities.kt:103) | 无 | 无 | 随父 todo 戳 |
| 11 | diaryEntries | id | id | — | createdAt, updatedAt, deletedAt；weatherAt(状态) | **date**（仅未删，每日至多一篇活的） | max(created, updated, deleted) / weatherAt ?? 0 |
| 12 | diaryDrafts | id | id | diaryId→diaryEntries SET_NULL (TodoEntities.kt:142) | createdAt | 无 | createdAt |
| 13 | habitChecks | `date\|habit` | 复合 (date, habit) (HabitRepository.kt:28) | —（habit 是 habits.key 字符串，无 FK） | checkedAt | 同主键 | checkedAt |
| 14 | habits | key | key（固定串如 `supplements`，Habit.kt:44-49） | — | **无**（仅 addedOn/removedOn 日期串） | key | (removedOn ?? addedOn) 当地 00:00（弱点） |
| 15 | healthProfile | self | id 恒 'self' (HealthEntities.kt:18) | — | updatedAt | self 单行 | updatedAt |
| 16 | bodyMeasurements | id | id | — | measuredAt, createdAt, updatedAt | **date UNIQUE** (HealthEntities.kt:36) | max(三者) |
| 17 | foodEntries | id | id | — | eatenAt, createdAt, updatedAt | 无（一天多条） | max(三者) |
| 18 | noteTopics | id | id | — | createdAt | **name**（trim+忽略大小写，仅未删；NoteRepository.kt:316-317） | createdAt |
| 19 | notes | id | id | topicId→noteTopics SET_NULL (NoteEntities.kt:35) | createdAt, updatedAt, deletedAt；reviewedAt(状态) | 无 | max(created, updated, deleted) / reviewedAt ?? 0（pinned 无时间列，弱点） |
| 20 | noteSources | `noteId\|kind\|ref` | 复合 (note_id, kind, ref) (NoteEntities.kt:59) | noteId→notes **CASCADE** (NoteEntities.kt:60) | createdAt | 同主键 | createdAt |
| 21 | noteDrafts | id | id | noteId→notes SET_NULL (NoteEntities.kt:74) | createdAt | 无 | createdAt |
| 22 | weeklyReviews | id | id | — | createdAt, updatedAt, deletedAt | **weekStart**（仅未删，每周至多一篇活的） | max(三者) |
| 23 | weeklyDrafts | id | id | reviewId→weeklyReviews SET_NULL (WeekEntities.kt:41) | createdAt | 无 | createdAt |
| 24 | countdowns | id | id | — | createdAt, updatedAt | 无 | max(createdAt, updatedAt)（state 同源，弱点） |

**设备本地表**（holidays、holiday_years、app_meta 其余 key）：不进快照、不写墓碑、不参与合并。app_meta 中 `net_worth_goal` 参与同步（`data_epoch`、`note_review_pick` 本地）。

**无自然键的表**（groups、todoLists、foodEntries、notes、checklistItems、三草稿表、countdowns、flows）：两端各建的同名/同义记录**并存**，不去重；重复流水不删（事实并集），用户可手动删。

---

## 2. 败者归档（R9 的落点）

### 2.1 文本三表 → 草稿（零丢失）

LWW 或自然键去重的败者来自 **diaryEntries / notes / weeklyReviews**，且败者内容字段与胜者**有任何差异**（canonical 内容序列化不等，含仅 mood/位置等单字段差异——宁多勿丢）时：

1. 整行写入对应草稿表；草稿表实际列（TodoEntities.kt:139-152、NoteEntities.kt:71-83、WeekEntities.kt:38-51）：
   - `diaryDrafts(id, date, diaryId, text, reason, createdAt)`——date 取败者行 date；
   - `noteDrafts(id, noteId, text, reason, createdAt)`；
   - `weeklyDrafts(id, weekStart, reviewId, text, reason, createdAt)`——weekStart 取败者行；
2. **草稿 ID 确定性派生**：`syncLose:<rowKey>:<败者 changeId>`；受长度约束时取该串 SHA-256 前 32 位十六进制（TEXT 主键可承载任意串，现有 UUID 不受影响）；
3. 元数据确定性：`reason = '同步合并保留败者版本'`；`createdAt = 败者 changedAt`；草稿自身的 `(changedAt, changeId) = (败者 changedAt, "R:" + hash("archive", rowKey, 败者 changeId))`；
4. 双端各自合并算出**同一行草稿**（同 ID 同内容）→ 不重复、随快照同步传播；
5. 幂等：同一 (rowKey, 败者 changeId) 只归档一次；完全相同的败者（纯重复）直接丢弃，不进草稿。

### 2.2 其余 21 张表 → 历史 + 副本（拍板：不做全表归档）

- 服务端历史 30 份 + 合并前副本（Android 本地独立文件；web replace/合并前的 PUT 即入服务端历史）；
- 合并调整清单（toast「合并调整 N 处」）如实计数，双端同内容（合并纯函数的输出，天然一致）。

### 2.3 归档行的生命周期

- 草稿是普通数据行：随快照同步、在「草稿箱」可见/可恢复/可删（删除照常写墓碑）；
- `draftToNote` 转正（NoteRepository.kt:122-130）等现有草稿操作照旧；
- 修复/归档派生戳（§0.2-B）保证产物对陈旧副本的支配性。

---

## 3. 自然键与跨行不变量修复表

### 3.1 自然键矩阵

| 表 | 自然键 | 仅限未删？ | 去重败者处理 |
|---|---|---|---|
| diaryEntries | `date` | 是 | 草稿（§2.1） |
| weeklyReviews | `weekStart` | 是 | 草稿 |
| noteTopics | `name`（trim + 忽略大小写） | 是 | 笔记重指 + 败者主题行墓碑（§3.2-R1） |
| bodyMeasurements | `date` | 否（硬删表，墓碑参与） | 历史 + 副本 |
| habitChecks | `(date, habit)` | 否 | 历史 + 副本 |
| habits | `key` | 否 | 历史 + 副本 |
| snapshots | `at` | 否 | **盘点组**原子去重（§3.2-R8） |
| valuations | `(accountId, at)` | 否 | 历史 + 副本 |
| snapshotItems | `(snapshotId, accountId)` | 否 | 随盘点组 |
| noteSources | `(noteId, kind, ref)` | 否 | 历史 + 副本 |
| todos（完成记录） | `(repeatSourceId, planDate)` | 是（软删） | **完成组**原子去重（§3.2-R9） |
| healthProfile | `self`（单行） | — | 整行 LWW |
| 其余表 | 无自然键 | — | 不去重（并存） |

### 3.2 跨行不变量修复矩阵

修复是 `merge` 的**纯函数步骤**，在逐行合并、自然键去重之后执行，迭代到 fixpoint。凡修复改动行，一律按 §0.2-B 重打戳（`R:` 派生）。

| # | 触发条件 | 确定性修复 | 记 adjustments | 证据 |
|---|---|---|---|---|
| R1 | 两个未删 noteTopics 同名（trim+lowercase） | 三元组高者留行；败者主题行 → 墓碑（`T:`，版本=败者版本）；败者主题下笔记 `topicId` 重指到胜者（重打 `R:` 戳） | 是 | NoteRepository.kt:316-317 |
| R2 | countdowns `pinned=true` 多于 1 | **状态戳**三元组最高者保留置顶，其余 `pinned=false`（重打 `R:` 状态戳） | 是 | CountdownRepository.kt:80（写入侧 unpinOthers :39,50） |
| R3 | habits active 数 > MAX_ACTIVE（=7） | 内容戳三元组降序保留前 7 个 active，其余置 inactive：`removedOn = max(addedOn, UTC 日期(修复戳))`（ISO 日期串，**不得取设备本地「今天」**，否则两端时区/日期不同即发散），重打 `R:` 戳 | 是 | BackupManager.kt:520-534、Habit.kt:52-54 |
| R4 | active habits = 0 且 habits 非空 | **复活内容戳三元组最高的一项**（`removedOn = null`，重打 `R:` 戳）；habits 为空列表时维持「内置五项」语义，不修数据 | 是（复活时） | 同上；`remove` 本地禁止降到 0，但双端各删一项合并可达 |
| R5 | todo.sectionId 指向不存在、或 section.listId ≠ todo.listId | `todo.sectionId = null`（重打 `R:` 戳） | 是 | TodoInvariants.kt:54-67 |
| R6 | **flow.at ≤ baselineAt** 或 **valuation.at < baselineAt**（validate 不查，合并层必须管） | 期初下移：`baselineAt' = min(baselineAt, min(valuation.at), min(flow.at) - 1ms)`；期初估值行（note=「期初」）`at` 同移到 baselineAt'，与既有 valuation 撞 (accountId,at) 时逐 1ms 前移到无碰撞；**若碰撞把期初估值移到 baselineAt' 之前，账户 baselineAt 跟随到该落点**（否则下一轮又见估值早于期初，来回移动、不收敛）；期初成本 baselineCost 不动 | 是 | Rules.kt:30（flow 严格 >）、Rules.kt:36-40（valuation ≥）、Entities.kt:87 |
| R7 | 同一账户同一时刻 flow 与 valuation 并存（合并产物） | **不修不删**；该 flow 被锚点吸收（估值已含同刻及以前流水，Ledger.kt:12-13,41-46）⇒ 余额不动，adjustments 记「N 条流水与估值同刻，未计入余额」（**fixpoint 结束后只计一次**，不随修复轮次累加） | 是（计数） | LedgerRepository.kt:262-268 仅写入侧互斥 |
| R8 | 盘点组（snapshot + snapshotItems + 归属 valuations）部分存活 | **组原子**：组共享 `(changedAt, changeId)`，整体胜/败；败组子行逐行墓碑 | 是 | Entities.kt:82-124 CASCADE + validate BackupManager.kt:500-510 |
| R9 | 完成记录自然键去重 | 败者完成记录 + 其 checklistItems **随组删**（逐行墓碑）；源待办的推进字段（planDate/occurrenceCount 等）取胜者组 | 是 | TodoInvariants.kt:77-121 |
| R10 | **父删 vs 子活**（账户 vs 流水/估值、分组 vs 账户、清单 vs 待办/分区、盘点 vs 条目/估值、笔记 vs 来源） | **比新旧**：任一「必须随父」子行（RESTRICT/CASCADE 类）内容戳**晚于**父墓碑 ⇒ 父行以 `R:` 戳复活（changedAt = max(子行戳, 墓碑戳)，行取被删前版本），子行保留；否则子行随父删，逐行墓碑（版本=子行自身版本），文本子行进草稿。SET_NULL 类子行（todos→list、notes→topic、drafts→父、completions→source）不触发复活，父删则引用置空（重打 `R:` 戳）；**但若父已因更晚的 CASCADE 子行复活，completion 的 `repeatSourceId` 保留** | 是 | FK 动作见 §1.3；validate 引用检查 BackupManager.kt:489-510 |
| R11 | 败者行的子行（去重/合并败者的 checklistItems、noteSources 等） | 随败者组删，逐行墓碑 | 是 | TodoInvariants.kt / validate |
| R12 | 任何残余孤儿（修复表有洞的兜底） | 能置空的引用置空（`R:` 戳）；NOT NULL 引用随删 + 墓碑；进 adjustments | 是 | validate 引用检查 |

**R10 与同行删改的区分**：单行的删除 vs 编辑（验收场景 4）是**同 rowKey** 的墓碑/行比较（比较器自然处理，编辑新⇒复活该行）；R10 是**跨行** FK 场景。

**变更组**：一次用户操作触碰的全部行共享 `(changedAt, changeId)`（如盘点= snapshot+items+valuations、重复完成=完成记录+checklist 副本+源待办推进）。组去重/组修复按组判定，不拆组。组定义是 M1 写路径审计的逐操作产出。

盘点兼容旧写入路径：旧版本修改余额时可能只推进估值戳。同行合并前，若同一盘点在两份输入中不同，先分别取该输入的盘点、明细、所属或沿用估值的最大内容版本；组内版本不齐时，按完整组的 canonical 内容派生同一 `R8` 戳，再让各行参与 LWW。完整组版本相同而内容不同也按完整组派生戳决胜，避免日期和余额分别取自两端。相同组与自合并保留原始字节。盘点墓碑的版本传播给明细和所属估值，避免旧删除路径逐行生成的相邻时间戳使组部分存活；沿用的独立估值不参与删除级联。派生草稿（同行冲突与自然键去重）也必须先与该草稿行的墓碑比较，用户已丢弃的草稿不再重新生成。

### 3.3 validate 分层（防「两端同结果、一端过一端不过」）

| 层 | 内容 | 失败行为 |
|---|---|---|
| L1 结构/类型 | formatVersion/schemaVersion 门槛（BackupManager.kt:413-414）、字段格式（mood、日期、金额、长度范围） | 报错不落库 |
| L2 引用/唯一/跨行 | FK 存在、自然键唯一、R1–R12 修复后残余违例 | **理论上不可达**（修复表已处理）；仍失败 = 修复表有洞 → 报错不落库 + 日志，视为 bug |
| L3 设备相关 | `Recurrence.validate` 的「10 年内能找到下一次」依赖设备节假日缓存（holidays 是设备本地表）；`checkProfile` 恢复时用 `LocalDate.MAX` 当今天（生日永不失败，BackupManager.kt:482-486） | **降级为提示**，不阻塞落库；两端日历不同不得一端过一端不过 |

---

## 4. 删除 / 级联 / purge 墓碑矩阵

### 4.1 墓碑结构

```
tombstone { table: <BackupFile 字段名>, rowKey: <§1.1 规范串>, deletedAt: Long, changeId: "T:<...>" }
```

- 顶层 `tombstones` 数组进快照，随同步传播；不物理清除（个人量级可忽略）；
- **墓碑版本**：被动删除（去重/级联/purge）= 被删行的原版本戳（确定性）；主动删除 = `deletedAt=now` + 新 changeId（`T:` + 随机后缀）；
- purge 的 `deletedAt` **保留原删除时刻**（软删行的 deletedAt），不是 purge 当刻；
- 比较器（§0.2-A）保证：同版本行 vs 墓碑 → 墓碑胜。

### 4.2 逐表删除路径矩阵

「软删」= 打 deletedAt/deleteBatch（内容变更，**不写墓碑**）；「物理删」= 删行（**写墓碑**）。web 端对应写点在 `domain.js` remove/complete、`app.js` 各处，M2 起按同一矩阵写墓碑。

| 表 | 删除路径（Android 证据） | 级联子行 | 墓碑写法 |
|---|---|---|---|
| groups | deleteGroup（RESTRICT 守卫，LedgerRepository.kt:94-98） | 无（有账户时禁删） | 主动删 |
| accounts | deleteAccount :186-193（先手动删 baseline 估值）；web 引用中→isDisabled 不删（domain.js:204） | baseline valuation 手动删；canDelete 守卫：0 flows、≤1 valuation、0 items | 账户行 + baseline 估值行各写墓碑 |
| snapshots | deleteSnapshot :330-332 | **FK CASCADE**：全部 snapshotItems；snapshotId 指向它的 valuations（收养的 snapshotId=null 估值**不**随删，LedgerRepository.kt:293-299） | snapshot + 被级联子行逐行墓碑（版本=各行原版本） |
| valuations | deleteValuation :247-253（itemCountForValuation 守卫） | 无 | 主动删 |
| snapshotItems | 随 snapshot/valuation 级联 | 无 | 级联墓碑 |
| flows | deleteFlow 硬删 | 无 | 主动删 |
| todoLists | 软删（级联标 todos+todoSections）；purge 物理删（TodoRepository.kt:1013-1021） | purge 物理删 → todoSections **FK CASCADE（跨批次带走其他批次的分区，全库唯一，TodoEntities.kt:31-32）** | 软删=内容变更；purge=逐行墓碑（deletedAt 保留原值） |
| todoSections | 软删；purge 物理删；随 list CASCADE | 无 | 同上 |
| todos | 软删；purge 物理删；**uncomplete 物理删完成记录** :522,528；gather/ungather/dissolve/extract 的**立即 purge 软删批次** :662,744,841（purge 即墓碑点） | 物理删 → checklistItems **FK CASCADE**（TodoEntities.kt:103）；完成记录 repeatSourceId **FK SET_NULL**（:53，完成记录留存但失去自然键） | 物理删路径逐行墓碑（含级联 checklist） |
| checklistItems | 手动删（deleteChecklistItem）；随 todo CASCADE；restoreChecklistItem 回插 | 无 | 墓碑 |
| diaryEntries | 软删（DiaryRepository.kt:127-132）；purge 物理删 | purge → diaryDrafts **FK SET_NULL**（草稿留存） | 软删=内容变更；purge=墓碑（保留原 deletedAt） |
| diaryDrafts | discardDraft 硬删 | 无 | 主动删 |
| habitChecks | **uncheck 物理删**（HabitRepository.kt:104,122）；habits.remove 时 deleteChecksOf | 无 | 墓碑（否则「取消打卡」永远同步不出去） |
| habits | remove：无 check→硬删 deleteHabit+deleteChecksOf；有 check→`removedOn=今天` 软标记（内容变更）；clearHabits（reset 路径） | deleteChecksOf 逐行删 habitChecks | 硬删 → 墓碑（被删 checks 各自墓碑）；removedOn 走普通内容戳 |
| healthProfile | save(overwrite) 先删旧行再插入（HealthRepository.kt:94-98） | 无 | **不写墓碑**——归为自然键 `self` 的同一行更新 |
| bodyMeasurements | delete 硬删；save 同日覆盖=同行更新 | 无 | 墓碑（仅真删） |
| foodEntries | delete 硬删 | 无 | 墓碑 |
| noteTopics | deleteTopic（NoteRepository.kt:254-259） | notes.topicId **FK SET_NULL**（笔记留存，推内容戳） | 主题行墓碑；笔记不动 |
| notes | 软删（NoteRepository.kt:189-205）；purge 物理删 | purge → noteSources **FK CASCADE**、noteDrafts **FK SET_NULL** | 软删=内容变更；purge=墓碑 |
| noteSources | 随 note CASCADE；extract 重组时手动删 | 无 | 墓碑 |
| noteDrafts | discardDraft / draftToNote 删 | 无 | 墓碑 |
| weeklyReviews | 软删（WeekRepository.kt:91-96）；purge 物理删 | purge → weeklyDrafts SET_NULL | 同 diaryEntries |
| weeklyDrafts | discardDraft | 无 | 墓碑 |
| countdowns | delete 硬删（CountdownDao.kt:40） | 无 | 墓碑 |
| netWorthGoal（app_meta） | set(null)：现状删键 → M1 改值置空+推戳 | 无 | 不用墓碑（单值 LWW，null 是合法值） |

**purgeExpired（30 天，MaintenanceWorker.kt:13-29 → TodoRepository.kt:1029-1034）**：按 delete_batch 删 todos/sections/lists/diary/notes/weeks → 每个物理删行写墓碑，**deletedAt 保留原删除时刻**；SET_NULL 存活的草稿/完成记录不写墓碑。

### 4.3 墓碑与去重的确定性

- 自然键去重墓碑：`changeId = "T:" + hash("dedup", rowKey, 败者 changeId)`，`deletedAt = 败者 changedAt` → 双端独立生成同一墓碑；
- 级联墓碑逐行生成（不写「组墓碑」占位），版本 = 各子行自身版本；
- 同一 rowKey 多次产生墓碑 → 保留三元组更高者；
- 撤销条/回收站/账户防误删规则照旧（R7）。

---

## 5. merge / replace / reset 细则

### 5.1 merge（同步默认）

```
compare(x, y) = (x.changedAt, kindRank(x), x.changeId) vs (y.changedAt, kindRank(y), y.changeId)
  kindRank: repair=2 > tombstone=1 > row=0（legacy: 前缀按 row）
  全同 → canonical 内容哈希字典序（保底确定性）
merge(a, b) -> (merged, adjustments[])
  1. 逐表行键并集：内容字段 ← 内容戳胜者；状态字段 ← 状态戳胜者（无状态戳表整行 LWW）
  2. 自然键去重（§3.1）→ 败者墓碑 + 归档（§2）
  3. 不变量修复 fixpoint（§3.2），修复行重打 R: 戳
  4. validate L1/L2（L3 降级提示）
```

性质验收（M2）：交换律 `merge(a,b)==merge(b,a)`、幂等、确定性；**陈旧副本支配性**（repaired/tombstone vs stale → 派生行必胜）；双端同输入逐字节同输出（含哈希）。

### 5.2 replace（恢复备份 / 导入 / 恢复历史版本）

**语义**：把全部数据替换为文件（或历史版本）内容，且**意图要传播**到云端与另一端。服务端零改动，纯 merge 协议表达：

```
replace(F, T=now):
  1. R := 当前本地快照；fileHash := canonical hash(F)
  2. F 内所有行重新打戳：changedAt=T、changeId="R:" + hash("replace", fileHash, rowKey, 内容)；
     netWorthGoal 同理推 netWorthGoalAt/ChangeId；tombstones 数组取 F 自带的（若有）
  3. rows(R) \ F 每行 → 墓碑 {deletedAt=T, changeId="T:" + hash("replace", fileHash, rowKey, 旧版本)}
  4. 在线：拉远端 R'，对 rows(R') \ F 且 changedAt ≤ T 的行补墓碑（同规则）；
     changedAt > T 的远端行保留（替换之后的新改动比 replace 新）
     离线：本地先落库（2–3），存 pendingReplace{T}（本地标记，不同步）；
     下次同步拉到远端后执行第 4 步，完成即清标记
  5. 之后照常 merge 上传
```

- `fileHash` = 文件内容 canonical 哈希（§7），保证多端/多次 replace 同输入同输出；
- Android：沿用「恢复前副本」`asset-board-prerestore.json`（本地用户兜底）；MergeWriter 的「合并前副本」是另一个文件，互不覆盖；
- Web：replace 前先把当前状态 PUT 上云（服务端历史即 pre-replace 副本）；**导入 = replace**（现状 store.import 仅标 dirty，M4 改）；**恢复历史版本 = replace(该 revision 正文)**。

**确认文案**：
- Android 恢复备份：`恢复将整库替换为备份内容。当前数据会先存为恢复前副本；不在备份里的记录将被删除（登录用户可从「历史版本」找回）。`
- Web 导入/恢复历史：`导入会替换云端与手机上的数据：不在文件里的记录会被删除，可从「历史版本」找回。确定导入？`
- 文案最终以 M3/M4 UI 实现为准，语义不得弱于此处。

### 5.3 reset（恢复出厂 / 清空）

= `replace(空文件)`：全部现存行 → 墓碑，netWorthGoal 置 null 且推戳，tombstones/草稿一并清空后上传。现状 `reset()` 构造空 BackupFile 走 replaceAll（BackupManager.kt:562-570，不清墓碑概念、意图不同步）——M3 改为上述语义。

- 确认文案：`清空全部数据（含云端与手机）？可从「历史版本」找回最近 30 次同步前的状态。`（本地用户去掉括号）

### 5.4 undo / revive / restore 重新打戳

所有「写回旧行对象」的路径**视为新变更**：`changedAt=now`（§6.5 时钟公式）、新随机 changeId、状态字段含则重打状态戳；软删恢复同时清 deletedAt/deleteBatch（内容变更）。否则远端较新的删除/修改会赢、撤销同步不出去。

Android 清单（盘点证实现状**均不重打**，M1 全部改造）：TodoRepository.reviveTodo/restoreBatch/undoSkip/uncomplete（TodoRepository.kt:967-990,539-559）、LedgerRepository.restoreAccount（LedgerRepository.kt:196-201，原行原样回插）、CountdownRepository.restore（:59-63）、FoodRepository.restore/putBack（:122-125,111-114）、NoteRepository.restore（:208-212）、DiaryRepository.restore（:144-154）、WeekRepository.restore（:108-117）；web `mutate` undo（app.js:25，`Object.assign(d, before)` 连旧元数据还原）、restoreBatch（domain.js:212，不回补 revision——与 remove 的 mark() 不对称，M4 对齐）。

### 5.5 resetLedgerHistory = replace 的局部变体

现状（BackupManager.kt:578-581,869-885）：每账户 baselineAt=now、清 flows、生成新「期初」估值 + 「数据初始化」盘点。合并语义：flows 全部 → 墓碑（版本=原版本）；账户/baseline 估值重打戳；新估值/盘点为新行；其余表不动。走 §5.2 同款 pendingReplace 处理远端残留。

---

## 6. 迁移与首升

### 6.1 Room 迁移 14→15（+ FORMAT 15→16）

- 每表加 4 列：`changed_at INTEGER NOT NULL DEFAULT 0`、`change_id TEXT NOT NULL DEFAULT ''`、（4 张状态表）`state_at`/`state_id`；app_meta 增 `net_worth_goal_at`/`net_worth_goal_change_id` keys；新表 `tombstones`；
- 回填 UPDATE 逐表执行，源列见 §1.3 末列（**取 max，不是优先级链**）；注意补 `deletedAt/completedAt/reviewedAt/weatherAt/snoozeAt`（v1 漏项）；
- `changeId = "legacy:" + SHA-256(canonical(内容字段投影))` 前 32 位十六进制；投影 = 除 4 个戳列外全部字段（排除自身防循环）；stateId 同法（状态字段投影）；
- 读旧备份（formatVersion 1..15）同一套回填；回填行 `legacy:` 前缀按普通行参与比较，意义在识别升级前数据。

### 6.2 isEmpty 与 goal 元数据

- `isEmpty()`（BackupManager.kt:228-237）追加：tombstones 非空、netWorthGoalAt 存在 → **非空**。防「本地只剩删除痕迹时首次登录被 DOWNLOAD 覆盖、删除丢失」；
- `GoalRepository.set(null)` 改值置空 + 推 netWorthGoalAt/ChangeId（GoalRepository.kt:13-23）。

### 6.3 首并策略

- **升级后第一次合并不做特殊流程**（不做全量对齐/二选一）：merge 常规执行，pre-merge 副本 + 调整清单 + 历史兜底；
- 验证（M1）：真实旧库（schema 14）+ 旧备份 fixture 跑迁移与首并测试（对照 `MigrationTest`、`RestoreTest`、`BackupReplaceTest` 既有锚点）。

### 6.4 首并已知限制（如实告知，写入用户可见说明）

1. **升级前的硬删除没有墓碑**：首并无法区分「升级前删除」与「从未存在」，被删行可能复活；再删一次即正常同步。pre-merge 副本 + 历史兜底；
2. 回填戳是本地时间字段的估计值（todos 无 updatedAt、groups/habits 无时间列、pinned 无时间列），升级前双端各自修改的行，首并胜负可能不符直觉；文本三表败者进草稿保内容，其余表靠历史/副本；
3. 同一操作在两端各录一遍的**无自然键记录**（如同一笔流水两个 UUID）不去重，用户手动删。

### 6.5 时钟规则（打戳公式）

`changedAt/stateAt = max(设备墙钟 + offset, 本设备上次发戳 + 1)`；`offset` 每次同步校准：

- Android：`Http.Response` 需暴露响应头（现只有 status/body，Http.kt:14）——取 `Date`（Go 自动带）或 `X-Updated-At`（Unix 秒，api.go:751-758）；`SyncApi.download` 改返回 `(text, revision, hash)`（修 meta/GET 竞态，AccountManager.kt:429-434）；
- Web：BFF 响应白名单补 `date`（server.mjs:95，web 侧代码不属 R10 的「服务端」）或用已透传的 `x-updated-at`；
- 极端偏移仍可能错胜错负——风险表如实记录，历史 30 份兜底。

---

## 7. canonical JSON / contentHash 规范（v1）

目的：同一份数据在 Kotlin 与 JS 两端算出**同一个** contentHash。用于 SyncDecision 的变更判断与 `X-Content-Hash` 头；**不是**传输格式约束。

### 7.1 规范化序列化规则

1. UTF-8，无 BOM，无任何空白；
2. 对象键按 **UTF-16 码元字典序**排序（= Kotlin `String.compareTo` = JS 字符串比较）；
3. 字符串转义严格表：`"`→`\"`、`\`→`\\`、U+0008→`\b`、U+0009→`\t`、U+000A→`\n`、U+000C→`\f`、U+000D→`\r`、其余 U+0000–U+001F→`\u00xx`（小写 hex）；**其余字符一律原样**（含非 ASCII、U+2028/U+2029）；**未配对代理项写 U+FFFD**（浏览器 TextEncoder 与 Java UTF-8 编码器对孤代理的替换不同，必须先归一，否则哈希不同）；
4. 数字：
   - 整数（金额=分、体重=克、能量=cal 等，全库惯例）：十进制、无前导零、负号前置、0 写 `0`；
   - **浮点字段全库只有 4 个**：diaryEntries 的 `tempMin`/`tempMax`/`lat`/`lon`（TodoEntities.kt:123-127）；整数值浮点（`v == floor(v) 且 |v| < 2^53`）按整数形式输出（统一 `1.0` vs `1`）；非整数按 **ECMAScript `Number::toString`** 最短往返表示（Kotlin 端实现同一算法；禁止 NaN/Infinity）。新增浮点字段必须先登记于此；
5. `true`/`false`/`null` 原样；
6. **数组按元素的 rowKey 规范串（§1.1）字典序排序**；本格式无嵌套数组，新增须登记排序语义；
7. 顶层：`exportedAt` 归零为 `0`（键保留）；`changedAt/changeId/stateAt/stateId/tombstones` **参与**哈希；**全部键都输出**（Kotlin `encodeDefaults=true` 现状），null 写 `null` 不省略键。

### 7.2 哈希定义

`contentHash = lowercase_hex(SHA-256(canonical_json_utf8_bytes))`，64 位。

### 7.3 实现（M2，2026-11-05）

- `web/public/canonical.js`（参考实现）与 `app/src/main/java/com/assetboard/data/sync/CanonicalJson.kt` 逐条实现 §7.1；`BackupManager.contentHash` 已改为调用 `CanonicalJson.contentHash`，旧的 `canonicalize` 只保留给 `legacy:` 戳回填（那些 id 已经写进 format 16 的迁移结果，不能改）。
- 数字统一走 ECMAScript 文本：JS 直接 `String(n)`；Kotlin 不依赖 `Double.toString`（Android/OpenJDK 版本间对 1e23 一类值可能不同），而是用 `BigDecimal(double)` 的**精确二进制值**，从 1 到 17 位逐精度 HALF_EVEN 舍入，取第一个能 `parseDouble` 回原值的最短数字串，再按 ECMA-262 的定点/指数阈值输出。`1.0`、`1e2`、`-0.0`、超过 2^53 的整数字面量因此与浏览器一致。
- 顶层键、普通对象键按 UTF-16 排序；行数组按 rowKey；tombstones 按 rowKey；普通数组按元素 canonical 文本排序（与 JS `canonicalValue` 一致，用于 goal 平局这类场景）。
- `contentHash` 变化会改变上传/比较的哈希；升级端首次同步必然格式变化，两端各合并上传一次即可（无用户提示）。

### 7.4 双端一致性测试（M2 门禁，已落地）

- 交叉样本 `fixtures/sync/canonical-cases.json` 与 `merge-cases.json`（仓库根，仓库内唯一副本；`web/scripts/gen-sync-fixtures.mjs` 生成）。不再按计划复制到两个 resources 目录，而是双端测试都从仓库根读取，避免副本漂移。
- canonical 样本 19 个：键序、转义（含控制符/非 ASCII/emoji/未配对代理项）、整数边界、`1.0`/`1e2`/`-0.0`/`1e21`/`2.5e-7`、超过 2^53 的整数、int64 时间戳、rowKey 转义、墓碑排序、`exportedAt` 归零、空对象。
- merge 样本 29 个：24 个手写场景（内容/状态双戳、墓碑平局与复活、同 id 与自然键归档、盘点级联、习惯 1..7、置顶、分区、期初前移与同刻、父删除/复活、清单 purge、主题重指、孤儿、完成记录、goal/health、长 id 归档、草稿失联、空文件）+ 5 个固定随机种子；每个样本都断言**正反合并 canonical 相同、adjustments 相同、结果自合并幂等**，两端逐字节一致。
- 另有 `web/test/merge-properties.test.mjs` 40 个种子的随机性质测试（交换/幂等/确定性/行键唯一），Kotlin 侧 `MergeTest` 用真实 `format-16.json` 做自合并并重新解码为 `BackupFile`，`MergeScenarioTest` 做双客户端两轮离线编辑收敛与 ~6500 行性能预算（<15s，实际 0.3s 级）。

### 7.5 与服务端 raw hash 的关系

- Web PUT 必须带 `X-Content-Hash`（canonical）；Android 现状已带（canonical）；
- 服务端缺省时对原始请求体 SHA-256（api.go:593-596）仅作弱校验，**两端不得依赖它做相等判断**；
- SyncDecision 比较「本地 canonical 哈希」vs「**下载正文后本地重算**的 canonical 哈希」，不直接比对服务端字符串（修 P0-7 的根本：比较口径只有一种）。

---

## 8. 网页延迟与零存储边界（定稿）

### 8.1 R2 边界（零本地存储的准确定义）

- 不存**业务数据**：M4 起移除 IndexedDB（现状 `gain-web` 整 state 落盘，store.js:9-18）、不落 localStorage/CacheStorage 里的账本/草稿/恢复前副本/outbox；
- 例外 1：HttpOnly 登录 cookie（凭据）；例外 2：主题、隐藏金额两个界面偏好（localStorage，§0.1-1）；
- 在途丢失窗口如实告知：内存编辑在「1s/3s 防抖 + visibilitychange 刷 + beforeunload 确认」之后仍可能因崩溃丢失；keepalive 64KB 上限、sendBeacon 被 BFF 三重检查拒绝（server.mjs:49-53，有测试）是硬约束——**不承诺崩溃零丢失**，写进用户可见说明。

### 8.2 store 重写必须保留/替换的机制清单（M4 验收项）

| 机制 | 现状 | 重写后 |
|---|---|---|
| 表单 expectedGeneration | 打开记 generation，提交不匹配即拦（app.js:35-37、store.js:19） | **保留**（内存态，随 merge/远端变更递增） |
| 编辑器 bodyRev 冲突转草稿 | saveDocument 判 `bodyRev!==rev`/已删/日期撞车 → 落草稿（domain.js:226-238） | **保留**（合并不得绕过；草稿随同步传播） |
| mutate undo | `Object.assign(d, before)` 连旧元数据还原（app.js:25） | 重新打戳（§5.4） |
| openNote 写 reviewedAt | 打开即写（app.js:134、review-note :179） | 走**状态戳**（§1.2） |
| 多标签 BroadcastChannel | 收到即从 IndexedDB 整体替换（store.js:12-17） | 拉新走 **pull→merge→渲染**（绝不盲替换）；navigator.locks 串行保留 |
| previous 恢复前副本 | state 内 + IndexedDB 持久 | replace 前 PUT 上云（服务端历史即副本），§5.2 |
| restoreBatch 不回补 revision | domain.js:212 与 remove 的 mark()（:205）不对称 | 对齐：恢复也推进 revision/stateSeq |

### 8.3 延迟预算（SLA 30 秒的构成）

| 环节 | 预算 | 现状→目标 |
|---|---|---|
| 安卓编辑落库 → 防抖上传 | 10s | 无 → 新增 |
| 网页结构化编辑 → PUT | 1s 防抖 | 现状 1s ✓ |
| 网页文本编辑 → PUT | 3s 空闲 + 失焦/切页强刷 | 现状 1s/4.5s max ✓（可保留） |
| 网页可见 tab 拉 meta | 20s 轮询（revision 变才拉全文→merge） | 现状 60s 全量 → 改 |
| 安卓回前台 / 进应用 | 即时同步 | 进应用 6s 已有；回前台新增 |
| 周期同步 | 5（仅前台）/15/30/60 分，默认 15 | 现状 6 小时 → 改 |

端到端最坏 ≈ 10s + 20s + 传输 ≈ 30s；前端台理想 ≈ 数秒。后台/休眠不承诺（WorkManager 15 分钟下限）。验收场景 1 按此口径。

---

## 9. M1 写路径审计起点（盘点结论，逐条消化进 M1 交付物）

M1 需产出「每表：哪些方法改行 → 怎么取组 → 哪些字段是内容 → 删除写什么墓碑 → 撤销怎么重打戳」的全清单 + 「每个仓储方法执行一次、断言戳推进/不推进」的测试。盘点已知的高危点：

| 高危点 | 证据 | M1 处理 |
|---|---|---|
| `putHabits` **REPLACE 整表**（add/update/reorder/remove 全家桶） | HabitRepository.kt:41,133-184 | 逐行 diff，无变化不推戳 |
| `reorderIn`/`dissolve` 全量重排**含软删行** | TodoRepository.kt:357-370,700-738（allTodos 不过滤 deleted_at） | 同上；软删行的 sortOrder 变更也是内容变更 |
| moveGroup/moveList/moveSection/reorderTopics/updateSnapshot 批量写 | LedgerRepository.kt:100-107、TodoRepository.kt:884-925、NoteRepository.kt:229-244、LedgerRepository.kt:306-328 | 逐行 diff |
| targeted SQL 旁列写：setMood/writeWeather/setPinned/setTopic/setReviewed/setPosition/unpinOthers/markReminded | TodoDao.kt:147-158、NoteEntities.kt:159-176、CountdownEntities.kt:39、TodoRepository.kt:580-587 | 按 §1.2 分类推内容戳/状态戳/不推（markReminded 走状态戳） |
| 软删/恢复只 bump bodyRev/revision、不动 updatedAt | DiaryRepository.kt:127-154、NoteRepository.kt:189-212、WeekRepository.kt:91-117 | 软删=内容戳（deletedAt 属内容）；恢复=重打戳（§5.4） |
| uncheck/uncomplete/立即 purge 物理删 | HabitRepository.kt:104,122、TodoRepository.kt:522,528,662,744,841 | 同事务写墓碑 |
| purgeExpired 30 天 | TodoRepository.kt:1029-1034 | 墓碑保留原 deletedAt |
| checklist 勾选推 todo revision（web app.js:212）；web completeTodo 三分支 | domain.js:183-201 | checklist.done 属 todo 内容字段还是子行？**定为 checklistItems 行内字段**（子行内容戳）；完成组共享变更组 |
| flushEditors 的 writeBody CAS 通道 | BackupManager.kt:696-759 | MergeWriter 复用（闸门内 flush） |
| netWorthGoal 删键 | GoalRepository.kt:16-18 | 值置空+推戳（§6.2） |

测试锚点：`RestoreTest`、`MigrationTest`、`BackupReplaceTest`、`RowGestureTest`（既有）；新增合并性质测试（M2）与打戳审计测试（M1）。

---

## 10. M1 实施记录（2026-11-05）

M1 已按本文档落地；实现时确定的细节如下（后续 M2/M3 以此为准）：

1. **戳的实现**：`data/ChangeStamps.kt` 的 `ChangeStamps.stamp(now)` —— 进程内严格递增（`max(now, last+1)`，CAS），一次用户操作一个 `ChangeStamp`，同操作全部行共享；`RowKey` 与 `deletedTombstone`/`derivedTombstone` 同文件。测试用 `resetForTests()`（`Fixture` 初始化调用），避免多个假时钟夹具共享进程时互相推高。
2. **实体列**：24 张同步表加 `changed_at`/`change_id`；diaryEntries/todos/notes/countdowns 另加 `state_at`/`state_id`；`tombstones(tbl,row_key,deleted_at,change_id)` 复合主键。`health_profile` 单行、`app_meta` 设备本地，均不入墓碑。
3. **迁移 14→15**：`MIGRATION_14_15` 逐表 ALTER + SQL 回填 `changed_at`（§1.3 的 max），habits 用日期串在 Kotlin 里补；`change_id`/`state_id` 在 Kotlin 里对「除戳列外的全部列」做长度前缀、列名排序的哈希（`legacy:` + 32 hex），以 `rowid` 回写；goal 增 `net_worth_goal_at`/`net_worth_goal_change_id` 两键。
4. **旧备份回填（format 1–15）**：`BackupManager.parse` 用同一投影思路对 JSON 行做 `legacy:` 派生；`netWorthGoal` 非空时派生 goal 戳。数据库迁移与 JSON 回填的投影源不同（列名 vs 字段名），不要求互等；各自确定性即可收敛（同内容不同 changeId 由比较器决胜，内容一致）。
5. **写路径**：全部仓储方法已按 §1.2 打戳——targeted SQL 增加 `changed_at/change_id`（内容）或 `state_at/state_id`（状态）参数；批量整写先 diff 只给变化的行；undo/restore 一律重新打戳（§5.4）。物理删除同事务写墓碑：分组/账户/估值/流水/盘点及级联子行、笔记主题、打卡取消、完成记录取消、清单项、草稿、习惯、健康、饮食、倒数日、回收站 purge（30 天与立即 purge，保留原 deletedAt）。
6. **墓碑版本**：主动删除 = `deletedAt=now` + `T:` 随机；purge/级联的派生墓碑用 `derivedTombstone`（版本取被删行原删除时刻），保证跨设备同批 purge 生成同一墓碑。
7. **备份格式 16**：`BackupFile` 增 `tombstones`/`netWorthGoalAt`/`netWorthGoalChangeId`；`current()` 读墓碑与 goal 戳；`overwrite()` 写回；`isEmpty()` 计入墓碑与 goal 戳。`format-16.json` 已用 `WRITE_BACKUP_FIXTURE=1` 生成；`WebBackupCompatibilityTest` 改为剥离戳后比较内容。
8. **门禁**：`compare-backups.py` 忽略四个戳字段；`dev-test.sh --all` 通过（793 测试 / 153 截图 / lint）。
9. **遗留（按计划进入后续里程碑）**：canonical 哈希当时仍为旧实现（**已在 M2 重写，见 §11**）；网页仍写 format 15（M4）；reset/恢复备份的 replace 墓碑语义、resetLedgerHistory 的流水墓碑（M3）；`docs/backup-format.md` 的 16 版条目（M5）。

---

## 11. M2 实施记录（2026-11-05）

M2 已按 §5.1 / §7 落地，Kotlin 与 JS 双实现逐字节一致。实现时确定、并回写进前文的细节：

1. **接口**：`data/sync/CanonicalJson.kt`（`canonicalJson` / `contentHash` / `rowKey`）与 `data/sync/Merge.kt`（`Merge.merge(a, b): Result(data, adjustments)`）都作用于 `JsonObject` 树，纯函数、无时钟无随机；只在同步边界用 `BackupFile` 解码。JS 对应 `web/public/canonical.js` 与 `web/public/merge.js`，`merge` 为 async（WebCrypto 派生 id），算法与 Kotlin 一一对应。
2. **收敛性修正**（测试驱动发现，已同步进 §3.2）：
   - R6 碰撞把期初估值移到目标之前时，账户 `baselineAt` 跟随该落点；否则下一轮又判「估值早于期初」，fixpoint 内反复下移，正反合并结果不同。
   - R10 中父行若因更晚的 CASCADE 子行复活，SET_NULL 的 completion 行保留 `repeatSourceId`；只有父仍删时才置空并重打戳。
   - R7 只在 fixpoint 结束后计数一次，不随轮次累加。
   - 自然键去重、R2、R4 的胜者比较在版本全同时按 canonical 内容决胜，不依赖数组顺序；同一输入内部若出现重复 rowKey（Room 不会产生，但防御性处理），两侧都先经 `mergeRow` 折叠，保证交换律。
   - 行合并产生的归档草稿在行并集阶段先放旁路列表，等所有表初始化后再并入草稿表，避免草稿被该表的行并集覆盖。
3. **归档**：败者进 `diaryDrafts`/`noteDrafts`/`weeklyDrafts`，字段与实体严格一致（多余字段会导致 `ignoreUnknownKeys=false` 解码失败）；id 为 `syncLose:<rowKey>:<changeId>`，超过 120 字符则取哈希；草稿行本身也带 `R:` 内容戳，后续合并按普通行处理。
4. **哈希切换**：`BackupManager.contentHash` 改用 `CanonicalJson.contentHash`；旧 `canonicalize` 仅留给 format 16 的 `legacy:` 戳回填（迁移结果不可重算）。`exportedAt` 在 canonical 内归零，无需再复制文件。
5. **样本与门禁**：`fixtures/sync/` 为唯一副本（不向 resources 分发拷贝），由 `web/scripts/gen-sync-fixtures.mjs` 生成并自检交换/幂等；`web npm test` 50 项、Kotlin `CanonicalJsonTest`/`MergeTest`/`MergeScenarioTest` 8 项通过；真实 `format-16.json` 自合并后可重新解码；~6500 行合并远低于 15s 预算。
6. **遗留到 M3/M4**：`MergeWriter`（闸门内 flush→export→merge→原子写回）、`validate` 实现、`SyncDecision` 改为下载后本地重算 canonical 哈希再比较、`SyncApi.download` 返回正文/修订/哈希；Web 端 store 接入这两个模块并去掉本地持久化。改动合并语义时必须同时运行 `gen-sync-fixtures.mjs` 并审阅 fixture diff。

---

## 12. M3/M4 实施记录（2026-11-05）

M3（安卓落库）与 M4（网页云端化）已按本文档落地：

1. **MergeWriter**：`BackupManager.mergeFromJson(remoteText)` 在 `WriteGate.freeze()` 内先 `flushEditors()`，再读本地、解析远端、`Merge.merge`、`validate`，通过后单事务 `overwrite` + epoch +1，随后重排提醒/刷新小组件并重建界面。合并前另存 `asset-board-presync.json`（本机私有目录 + 备份目录尽力而为）；校验或事务失败时本地不动。结果与远端 canonical 相同时跳过写回（避免轮询造成无谓重建）。
2. **SyncDecision**：动作改为 `UP_TO_DATE / UPLOAD / DOWNLOAD / MERGE / REMOTE_TOO_NEW`。两边都改、首登有本地数据、服务端 revision 回退都走 MERGE；不再有 CONFLICT 与用户选择。`resolveConflict` 与冲突 UI 已删除。
3. **下载竞态**：`GET /v1/sync` 的 `X-Revision`/`X-Content-Hash` 通过 `Http.Response.headers` 暴露；`SyncGateway.download` 返回 `RemoteSnapshot(text, revision, contentHash)`。合并后用**响应头里的 revision** 作为 `If-Match`，不再用之前 meta 的 revision。409 时重新下载-合并-上传，最多 3 轮（递归深度亦限 3）。
4. **replace 语义**：`restore/导入/reset/resetLedgerHistory/历史版本恢复` 统一走 `replaceAll`，其中 `prepareReplace` 把文件行全部打上操作戳、把旧有而文件没有的行写成墓碑、把文件自带的墓碑重新打戳、目标资产打戳（清空也保留戳）；戳取 `max(now, 文件最大戳+1, 旧数据最大戳+1)`，时钟落后也不会被旧版本反超。同步的首次下载走 merge 而非 replace，保留远端版本。
5. **调度**：进应用 6 秒后同步；`MainActivity.onStart` 回前台同步（30 秒内不重复）；前台 ticker 每 30 秒检查一次用户间隔（0/5/15/30/60 分钟，默认 15）；编辑经 `WriteGate.onCommitted` 触发 10 秒防抖上传；WorkManager 周期任务按设置更新（<15 分钟仅前台，取消后台任务）。
6. **校时**：`ServerClock` 从每个响应的 `Date` 头学习偏移（超过一天的视为无效），`ChangeStamps.nextAt` 只对合并戳加偏移；用户看到的时间不变。
7. **历史版本**：账号页新增云端历史列表与恢复；恢复 = replace + 立即同步。`GET /v1/sync/revisions` 与 `/revisions/{n}` 使用服务端既有接口，零服务端改动。
8. **网页 Store**：完全内存态（无 IndexedDB/localStorage/CacheStorage 数据副本）；`store.change` 对 before/after 做逐表 diff，内容字段推内容戳、4 组状态字段推状态戳、删除写 `T:` 墓碑、目标资产按值变化打戳；PUT 带 canonical `X-Content-Hash` 与 `If-Match`，409 自动拉取合并重试；`sync()` 先 meta 后按需下载合并再上传；可见标签页 20 秒轮询；`pagehide/visibilitychange` 用 keepalive PUT 尽力兜底（≤60KB，省略哈希，下次同步纠正）。导入走 `replaceData`（同样的重新打戳 + 墓碑），保留本次会话的导入前副本供下载。
9. **旧格式**：网页 `parseBackup` 对 1–15 回填 `legacy:` 戳（同步 FNV 派生、后取 `Date.now` 之外的时间字段 max），与 Kotlin 的派生不要求逐字相同——两端都看到双方 id，比较器和内容决胜仍收敛；format 16 要求每行有戳（`changedAt` 可为 0、`changeId` 可为空，表示尚未被用户改动）。
10. **门禁**：新增 `SyncMergeTest`（合并写回、replace 墓碑、未来时钟、墓碑压过更新的远端行）、`AccountManagerTest` 合并上传、网页 store/domain 测试；`web npm test` 53 项、Android `--fast` 690 项通过。M5 的 `--all` 与发布记录见 CHANGELOG 与发布提交。
11. **残余风险（如实记录）**：设备时钟严重偏移由 Date 校时缓解，但离线期间的相对顺序仍取决于本地时钟；网页关闭标签页时未上云的编辑只能尽力 keepalive，失败即丢失（页面会提示）；旧安卓（<1.4.1）遇到 format 16 会拒绝并提示升级，符合 §6.4 的发布顺序。
