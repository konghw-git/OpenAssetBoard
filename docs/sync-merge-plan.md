# 日盈 Web 云端化与双端互通：需求明细与实施方案

状态：**已确认 v2**（2026-11-05 开工；§9 六项按推荐默认执行）
v1 经外部评审（21 组论断）+ 代码逐条核对后修订：新增 M0「合并语义与不变量设计」前置里程碑，补齐败者归档、墓碑矩阵、合并落库、账本口径、哈希规范、迁移首升等空白；更正 8 处与代码不符的表述（见附录 A）。**M0 语义已定稿：[`sync-merge-semantics.md`](sync-merge-semantics.md)**（含全表矩阵、修复/墓碑/归档规则、细化决定 A–G；与本文冲突以该文档为准）。
**M1 已完成**（2026-11-05）：24 表内容戳 + 4 表状态戳、tombstones 表、迁移 14→15 回填、备份格式 16（旧文件 `legacy:` 回填）、全部写路径打戳与物理删除墓碑；`dev-test.sh --all` 通过（793 测试 / 153 截图无变化 / lint）。实施记录见语义文档 §10。

**M2 已完成**（2026-11-05）：`CanonicalJson.kt` + `Merge.kt` 与 `canonical.js` + `merge.js` 双实现；内容哈希切到新口径；`fixtures/sync/` 交叉样本双端逐字节一致；交换/幂等/确定性随机性质测试、双客户端两轮离线收敛、真实 format 16 备份自合并可解码、~6500 行性能预算全部通过。实施记录见语义文档 §11。

**M3 已完成**（2026-11-05）：`MergeWriter`（`BackupManager.mergeFromJson`：闸门内 flush→merge→原子写回→epoch/提醒/UI）；`SyncDecision` 的 CONFLICT 改为 MERGE；下载改用响应头的 revision/hash（修 meta/GET 竞态）；409 读-合并-重试（≤3）；replace 语义（恢复/导入/重置重新打戳 + 墓碑）；前台回来自动同步、10 秒编辑防抖、可配周期（5/15/30/60/关）与 WorkManager 对齐、服务端 Date 校时、云端历史版本 UI。**M4 已完成**（同日）：`store.js` 改为纯内存云端客户端（删 IndexedDB）、网页写路径集中打戳与墓碑、导入=replace、PUT 带 canonical `X-Content-Hash`、20 秒 meta 轮询、409 合并重试、旧备份 1–16 回填；`web npm test` 53 项通过。实施记录见语义文档 §12。

**M5 已完成**（2026-11-05）：文档门禁、`dev-test.sh --all` 纳入 `web npm test`、发布 1.4.1（Android 先行；网页按计划在安卓升级后再部署）。发布记录见 `CHANGELOG.md`。

决策已定：方案 B（记录级 LWW 合并）；网页端纯云端零本地存储；安卓进应用自动同步 + 周期可配；网页端实时同步；本地用户不受影响。

---

## 1. 用户分层与数据归属

| | 本地用户（不登录） | 账号用户 |
|---|---|---|
| 数据存放 | 仅手机本地库 | 安卓：本地库（主）+ 云端快照（副本）；网页：仅云端 |
| 离线可写 | ✅ | 安卓 ✅；网页 ❌（须在线编辑） |
| 网页端 | 不适用（登录页说明） | 与手机完全互通 |
| 同步 | 无 | 自动 + 手动（见 §5） |

**零本地存储的边界（网页端）**：
- 不使用 IndexedDB / localStorage / CacheStorage 保存任何**账本数据**（含草稿、恢复前副本——它们是数据，必须随账号走）；数据只存页面内存，关页即清；
- **例外 1**：HttpOnly 登录会话 cookie（凭据，非数据，现状即如此）；
- **例外 2**：主题、隐藏金额两个**界面偏好**存 localStorage（推荐保留，§9-1）；
- 在途未上传的编辑可能因崩溃丢失——用「1s 防抖 + 切后台即刷 + beforeunload 确认 + ≤60KB 文本 keepalive 尽力」压窗口（§5.2），如实告知残余风险；
- 登出即清内存；`X-Gain-User` 防串号（现状）。

**数据表范围**：全库 27 表 = 24 张同步表 + 3 张设备本地表（holidays、holiday_years、app_meta）。app_meta 按 key 区分：`net_worth_goal` 参与同步，`note_review_pick` 等设备本地。healthProfile 为单行 upsert（id='self'），非逐行增删表。

---

## 2. 需求明细

| 编号 | 需求 | 说明 |
|---|---|---|
| R1 | 双端功能互通 | 网页已覆盖：资产、账单、待办（重复/清单/习惯）、日记、笔记、周复盘、健康、饮食、倒数日、设置（回收站/草稿/历史版本/备份/改密） |
| R2 | 网页零本地存储 | 见 §1 边界定义 |
| R3 | 无感汇合 | 同一账号下任一端的改动自动出现在另一端，**不再出现「保留本机 / 使用云端」二选一** |
| R4 | 记录级 LWW | 每条记录带变更时间戳，同条记录双端都改时新的覆盖旧的，不做人工裁决 |
| R5 | 安卓自动同步 | 进应用自动同步（已有，延迟 6s）+ 回前台 + 设置可选周期；手动同步保留 |
| R6 | 网页实时同步 | 每次变更即上云：结构化编辑防抖 1s 立即 PUT；文本编辑 3s 空闲 + 失焦/切页强刷 |
| R7 | 删除语义不变 | 回收站、撤销条、停用账户、防误删规则在两端保持现状 |
| R8 | 版本协商 | 新端能读旧备份/旧快照（读 1–16，迁移回填）；旧端拒新（REMOTE_TOO_NEW 双向不动，已核实） |
| R9 | 失败安全 | 网络失败不丢字；**LWW 败者永不静默丢有效内容**——文本三表败者进草稿（同步传播），其余表败者保服务端历史 + 合并前副本（§4.3） |
| R10 | 服务端零改动 | 保持哑快照存储（整快照 + revision + If-Match + 历史 N 份）；replace/reset 也用纯 merge 协议表达（§4.6） |

**「完全互通」验收场景**（SLA 按 §5 定义）：
1. 手机记一笔 → 网页 **30 秒内**刷新可见（两端前台）；网页记一笔 → 手机 30 秒内可见（防抖上传 + 20s meta 轮询）；
2. 手机离线记账一天，网页正常用；手机联网同步后，两边数据 = 并集，无弹窗；账户余额口径见 §4.5；
3. 同一分钟两端改同一个待办 → 两边最终都显示改动时间较新的一版；
4. 网页删一条流水 + 手机同期改这条流水 → 时间新者生效（编辑新 ⇒ 复活；删除新 ⇒ 消失）；
5. 全程无「冲突，请选择保留哪份」类提示；合并做的修复以「合并调整 N 处」toast 告知（双端同内容）。

---

## 3. 同步架构总览

```
┌──────────────┐   整快照+revision    ┌──────────────────┐   整快照+revision   ┌──────────────┐
│  Android     │◄──── If-Match ──────►│  同步服务（哑存储） │◄──── If-Match ─────►│  Web（内存态） │
│  本地库 主存储 │      合并跑在端侧     │  Go · 不解析账本   │     合并跑在端侧     │  云端即唯一存储 │
└──────────────┘                      │  历史 N 份后悔药   │                     └──────────────┘
        │                             └──────────────────┘                             │
        │         同一个纯函数 merge(local, remote)（Kotlin / JS 双实现）               │
        └── MergeWriter：闸门内 flush→export→merge→原子写回→副作用（§6.2）──────────────┘
```

- **合并是纯函数、确定性、可交换**：`merge(a,b) == merge(b,a)`，含自然键去重、孤儿修复、调整清单都是确定性输出；双端各自计算必然一致；
- 服务端不做合并；409 只是「读-合并-重试」的信号；
- 双实现用**交叉样本测试**锁一致（含 canonical 哈希逐字节一致）；
- 合并结果**落库走 MergeWriter**（不在本图展开）：这是 v1 的空白，设计见 §6.2-3。

---

## 4. 同步规则（核心）

### 4.1 记录变更元数据与字段分类

**每条记录（24 张同步表）新增内容版本戳；4 张表额外加状态版本戳：**

| 字段 | 类型 | 语义 |
|---|---|---|
| `changedAt` / `changeId` | Long / String | **内容版本戳**：内容字段（含删除标记）最后一次变更 |
| `stateAt` / `stateId` | Long / String | **状态版本戳**（仅 diaryEntries、notes、todos、countdowns）：系统/展示字段最后一次变更 |

**字段分类（写路径打戳规则，全表清单是 M0 交付物）**：
- **内容字段**：标题、正文、金额、日期、分类、引用外键、sortOrder、mood、topicId、deletedAt/removedOn 等用户语义内容 → 变化推进内容戳；
- **状态字段**（变化只推状态戳，不推内容戳）：
  - diaryEntries：weather、weatherAt、weatherRev（写天气不抢正文版本）
  - notes：reviewedAt、pinned（「打开看了一下」不抢正文）
  - todos：remindedPeriod、snoozeAt（提醒投递不抢任务版本）
  - countdowns：pinned
- **批量写回先 diff**：habits 整表 upsert、moveGroup、reorderTopics 等「写全部」操作，逐行比对内容字段，**无变化不推戳**——解决「重排把没变的行变成新版本、反过来覆盖另一端真实修改」；
- **撤销/恢复重新打戳**：reviveTodo/restoreBatch/restoreAccount/Countdown.restore/Food.restore/Note.restore/Diary.restore/Week.restore 等写回旧行的路径，一律视为新变更（changedAt=now、新 changeId），否则远端较新的删除/修改会赢、撤销同步不出去。恢复动作同时推进 bodyRev/revision（现状）也照旧；
- 现有语义时间戳（at/checkedAt/updatedAt/completedAt…）保留原义；`changedAt` 只服务合并；
- **对比键** `(changedAt, changeId)` 字典序大者胜；同毫秒由 changeId 确定性决胜（双端独立算出同一胜者）；
- 顶层 `netWorthGoal` 伴随 `netWorthGoalAt` + `netWorthGoalChangeId`；**值为 null 也保留戳**（清空目标是可合并的变更；现状 GoalRepository.set(null) 删键，需改为值置空+推戳）；
- **回填规则**（Room 迁移 14→15 与读旧备份共用）：changedAt = 该行全部相关时间字段的最大值（updatedAt → createdAt → at → checkedAt → **deletedAt → completedAt → reviewedAt → weatherAt** 取 max 而非优先级）；changeId = 由内容字段确定性派生（哈希投影**排除四个戳字段自身**，防循环依赖）。注意：todos 无 updatedAt，只能用 createdAt，升级前的相对顺序不真实——见 §8「升级后首并」。

### 4.2 删除语义与墓碑

现状：6 张表软删（todos/todoLists/todoSections/diaryEntries/notes/weeklyReviews，deletedAt+deleteBatch）；habits 双轨（无打卡→硬删、有打卡→removedOn）；其余硬删。**硬删在 LWW 下会复活，必须进墓碑**：

- **软删表**：deletedAt 即一次内容变更（推内容戳），删除与编辑统一比新旧；
- **硬删表**：新增顶层 `tombstones: [{table, rowKey, deletedAt, changeId}]`。物理删行**在同一事务**写墓碑；
- **行键规范（rowKey）**：`id` 列（UUID 表）或复合主键规范序列化——`date|habit`、`snapshotId|accountId`、`noteId|kind|ref`、`key`（habits）、`self`（healthProfile）。表名统一用 **BackupFile 字段名**（如 `habitChecks` 而非 `habit_checks`）；
- **墓碑版本** = 被删行的原版本戳（确定性，双端一致）；删除本体则 deletedAt=now、新 changeId；
- **平局规则**：行与墓碑版本完全相同 → **墓碑胜**（自然键去重产生的墓碑与败者行同版本，必须能赢；语义上「同一次变更」里删除保守）；
- **级联与隐藏删除全部入矩阵**（M0 交付物，核实过的清单）：
  - deleteSnapshot → FK CASCADE 删 snapshotItems + 归属 valuations（Entities.kt:80-85）→ 子行逐行写墓碑；
  - deleteAccount → 先删 baseline valuation，账户下估值/流水随删；
  - deleteTopic → FK SET NULL 改 notes.topicId（内容戳照常推）；
  - **uncheck 打卡 = 物理删 habit_checks 行**（HabitRepository.kt:104,122）→ 必须写墓碑，否则「取消打卡」永远同步不出去；
  - **uncomplete 永久删 COMPLETION 行**（TodoRepository.kt:522,528）→ 同上；
  - gather/ungather、dissolve、extract 的**立即 purge 软删批次**（TodoRepository.kt:662,744,841）→ purge 即墓碑点；
  - purgeBatch / purgeExpired（30 天）→ 写墓碑，**deletedAt 保留原删除时刻**（不是 purge 时刻，否则 30 天前删除会被误判胜过 10 天前的恢复/编辑）；
  - HealthRepository.save 同日覆盖先删旧行 → 归为自然键 date 的**同一行更新**，不写墓碑；
  - checklistItems 删除、3 个 discardDraft、HolidayRepository.clearHolidayYear（设备本地表，不管）；
- 墓碑不物理清除（个人量级可忽略）；回收站、撤销条、账户防误删规则不变。

### 4.3 LWW 合并算法（正式定义）

```
merge(a, b) -> (merged, adjustments[])
  1. 逐表按行键取并集；同键两行分两步取：
     内容字段 ← (changedAt,changeId) 胜者；状态字段 ← (stateAt,stateId) 胜者（无状态戳表整行 LWW）。
     墓碑参与比较（墓碑 deletedAt 即其 changedAt）；行 vs 墓碑平局 → 墓碑胜。
  2. 自然键去重：同自然键只留 LWW 胜者；败者 → 确定性墓碑（版本=败者版本）+ 败者归档（见下）。
     自然键：diaryEntries.date*、weeklyReviews.weekStart*、bodyMeasurements.date、
     habitChecks(date,habit)、habits.key、snapshots.at、valuations(accountId,at)、
     snapshotItems(snapshotId,accountId)、noteSources(noteId,kind,ref)、
     noteTopics.name(trim+lowercase)*、重复完成记录 (repeatSourceId, 计划日)
     (* 仅限未删记录)
  3. 变更组原子性：同组行共享 (changedAt,changeId)，去重/孤儿修复按组判定
     （盘点= snapshot+items+valuations；重复完成=完成记录+源待办+检查项；清单级联删除=全组）。
  4. 孤儿/不变量修复（确定性规则表，M0 交付物；已核对的规则与修复方向）：
     - 主题重名（trim+lowercase）→ 留胜者行，败者笔记 topicId 重指到胜者；
     - 倒数日 pinned > 1 → 只留戳新的置顶，其余改 false；
     - 习惯 active > MAX_ACTIVE → 按戳淘汰多余者、removedOn 取确定性 UTC 日期（记 adjustments）；非空且 active=0 → 复活戳最高的一项（记 adjustments）；空列表维持内置五项语义；
     - section 不属于 todo 的 list → todo.sectionId 置 null（移入无分区）；
     - 流水 at <= baselineAt（另一端前移期初）→ 基线时间戳降到并集下界（基线估值 at 同移，保唯一）；
     - 流水与估值同刻共存 → 保留双方，口径按「锚点含同刻流水」（domain 既有语义），不修不删；
     - snapshotItems 三重引用一致 → 随盘点组原子比较，不拆组；
     - 重复完成去重 → 败者及其 checklistItems 随组删（墓碑）；
     - 父删子活（账户删除 vs 他端新流水等）→ 删除与编辑比新旧：任一必须随父的子行内容戳晚于父墓碑 ⇒ 父行以 R: 戳复活，否则子行随父删（细则见 semantics §3.2-R10）。
  5. validate 分层：闸门校验（结构/类型/引用/唯一性）不过 → 报错不落库；
     **依赖设备本地数据的校验（节假日日历推算 Recurrence.validate）降级为提示**——
     两端日历数据可能不同，同一份合并结果不能一端过一端不过（已核实 BackupManager.kt:512 传入设备日历）。
```

**败者归档（R9 的落点）**：
- **文本三表**（diaryEntries/notes/weeklyReviews）LWW/去重败者，内容字段与胜者不同 → 整行写入对应草稿表（diaryDrafts/noteDrafts/weeklyDrafts），草稿 ID 由 (行键, 败者 changeId) 确定性派生、reason='同步合并保留败者版本'——双端各自合并生成同一草稿行，不重复、随同步传播，**零丢失**；
- 其余表败者不归档，保服务端历史版本 + 合并前副本（§9-6 待确认是否扩为全表冲突归档）；
- 草稿、墓碑都是数据，随快照同步。

性质：交换律、幂等、确定性 ⇒ 任意顺序同步收敛到同一状态（LWW-Register Map + 确定性修复，状态式 CRDT）。

### 4.4 冲突场景对照表

| 场景 | 结果 |
|---|---|
| ① 双端改**不同**记录 | 并集，各取各的，无感 |
| ② 双端改**同条**记录 | 内容戳新者整行胜；败者文本进草稿，其余保历史 |
| 删除 vs 编辑 | 谁的时间新谁生效（编辑新 ⇒ 复活；删除新 ⇒ 消失；同版本 → 墓碑胜） |
| 双端各完成同一次重复待办 | 完成记录按 (repeatSourceId,计划日) 去重留新，败者随组删；计划日只推一次 |
| 双端同一天各写一篇日记 | 同自然键去重留新，败者整篇进 diaryDrafts |
| 「网页打开看笔记」vs 手机改正文 | reviewedAt 是状态字段，只推状态戳——正文版本不受影响，反之亦然 |
| 提醒投递 vs 改待办 | remindedPeriod/snoozeAt 状态字段，互不干扰 |
| 双端各置顶一个倒数日 | 修复规则留戳新的置顶，另一个自动取消（adjustments 告知） |
| 双端同毫秒同键改写 | changeId 字典序决胜，双端同一胜者 |
| 设备时钟严重偏移 | 唯一残余风险，对策见 §8 |

### 4.5 账本口径策略（待拍板 §9-5）

「手机离线记账 + 网页同期记账 + 各自盘点」并集后，锚点之间的历史曲线可能与任一端当时的认知不同（write-gate.md 提到的「口径问题」）。**推荐策略**（不动「恢复=整库覆盖」产品决定）：

- flows/valuations/snapshots/snapshotItems 全部按行 LWW 合并——流水是事件、估值是测量，都是事实，并集正确；
- 余额口径不变：估值锚定 + 其后流水（app 既有语义）⇒ **合并后「当前余额」永远自洽**；
- 合并后跑「口径漂移体检」：逐账户比较每个估值锚点值 vs 期初+流水算到该时刻的值，差异则给账户挂「建议重新盘点」徽标（不阻塞、不改数据）；用户重盘点即产生新锚点，此后归正；
- 历史曲线的跳变如实存在，属双端离线分头测量的固有代价。

### 4.6 merge / replace / reset 三语义

| 语义 | 用途 | 实现（纯 merge 协议，服务端零改动） |
|---|---|---|
| **merge** | 同步默认 | §4.3 |
| **replace** | 恢复备份、导入、恢复历史版 | replaceAll 前导出旧快照 → diff：旧有新无的行生成墓碑（版本=replace 时刻）、文件内行全部重新打戳 → 之后照常 merge，意图传播到云端与安卓。Web 端 replace 前先把当前状态 PUT 上云（服务端历史即 pre-replace 副本）+ 二次确认：「云端与手机数据都会被替换」 |
| **reset** | 恢复出厂/清空 | = replace 空文件（全部墓碑） |

- 撤销（undo/revive/restore）= 普通 merge 变更 + **重新打戳**（§4.1）；
- `docs/write-gate.md` 的「恢复是整库覆盖，不做合并」**保持有效**：replace 语义仍是覆盖，只是覆盖意图编码成墓碑随 merge 传播；
- Android 的 asset-board-prerestore.json 副本照旧（本地用户也在用）；MergeWriter 的「合并前副本」另存独立文件名，互不覆盖。

### 4.7 历史版本 = 后悔药

- 服务端保留最近 **30** 份（`ASSETBOARD_HISTORY=30`，env 改动即可）；
- **Android 新增「历史版本」入口**（M3）：列表/下载/恢复走服务端既有 `GET /v1/sync/revisions`（api.go:130 已存在，零服务端改动）——补上「安卓无历史恢复路径」的缺口；
- Web 历史 UI 照旧；
- 网页保存节奏合并防抖（文本 3s、结构化 1s 合并连击），避免秒级 PUT 把 30 份历史打穿；按时间保留需改服务端（违 R10），暂不做（§9-4）。

---

## 5. 同步时机与可见性 SLA

**SLA：两端前台时，任一端改动 30 秒内出现在另一端**（v1 的「5 秒」不可达，已按实测节奏修正）。

### 5.1 Android

| 触发点 | 行为 | 状态 |
|---|---|---|
| 进入应用 | 自动同步（延迟 6s，已有） | 保持 |
| 应用切回前台 | 自动同步 | 新增 |
| 周期自动同步 | 设置可选 **5（仅前台）/ 15 / 30 / 60 分钟 / 关闭**（默认 15） | 新增 |
| 编辑落库后 | 防抖 **10 秒**静默上传 | 新增 |
| 手动「立即同步」 | 保留 | 保持 |

- 前台周期用应用内协程计时；后台 WorkManager 对齐间隔（系统 15 分钟下限，5 分钟档文案注明「仅前台生效」）；
- 失败重试沿用 WorkManager backoff，同步失败只改状态文案。

### 5.2 Web

| 触发点 | 行为 |
|---|---|
| 打开页面 | 登录后整快照拉入内存 |
| 结构化编辑 | 防抖 1s 合并连击即 PUT |
| 文本编辑 | 3s 空闲上云 + 失焦/切页/关页前强刷（visibilitychange flush 已有） |
| 可见 tab | 每 **20s** `GET /sync/meta` 轻轮询；revision 变 → 拉全文 → **merge** → 渲染（不是覆盖）；隐藏 tab 不轮询 |
| PUT 409 | 自动 GET → merge → 重试（≤3 次），仍败提示重试 |
| 断网 | 编辑暂存内存 + 常驻「未同步」提示 + beforeunload 确认 + ≤60KB 文本 keepalive 尽力兜底 |
| 多标签 | BroadcastChannel 仅通知（不存数据）；navigator.locks 串行（已有）；收端拉新走 merge；表单 expectedGeneration/编辑器 bodyRev 保护保留（内存态） |

- 状态栏：已同步 / 同步中 / 未同步 N 项 / 合并调整 N 处；删除「同步冲突」横幅与 resolve-cloud/local 流程。

---

## 6. 端侧改造清单

### 6.0 M0 交付物（先行，见 §7；产出汇总在 [`sync-merge-semantics.md`](sync-merge-semantics.md)）

1. 每张表的**行键规范**（含复合主键）+ 内容/状态字段分类表；
2. **败者归档**规则与确定性 ID 派生；
3. **自然键 + 跨行不变量修复表**（每条 FK/唯一/业务规则的胜负与修复）；
4. **删除/级联/purge 墓碑矩阵**（表 → 行键 → 删除路径 → 级联子行 → 墓碑写法）；
5. merge/replace/reset 语义细则（含确认文案）；
6. **迁移/首升策略**：逐表回填 SQL（取全时间字段 max）、isEmpty 计入墓碑与 goal 戳、「升级后第一次同步」策略（pre-merge 副本 + 调整清单兜底，真实旧库测试）；
7. **canonical JSON/hash 规范**：对象键排序（或明确按声明序）、数组按行键排序、排除 exportedAt 与四个戳字段？（不——戳字段进 hash，仅 exportedAt=0）、数字格式统一；Kotlin/JS 双实现交叉样本「同一 fixture → 同一 64 位 hex」；
8. 网页延迟与零存储边界（本节 §1/§5 即初稿）。

### 6.1 Android（主要工作量）

1. **数据模型**：24 表双版本戳（4 表加 stateAt/stateId）+ tombstones + netWorthGoalAt/ChangeId；升 `AppDatabase.SCHEMA_VERSION`（14→15）、`Migrations`（含回填）、迁移测试（真实旧库）、`BackupManager.FORMAT_VERSION`（15→16），`WRITE_BACKUP_FIXTURE=1` 生成样本（严格按 CLAUDE.md 清单）；
2. **Merge.kt**：§4.3 全规则 + 性质测试（交换/幂等/去重/墓碑/变更组/修复）+ 调整清单；
3. **MergeWriter**：冻结闸门 → flushEditors → 导出本地 → 拉远端 → merge → **单事务原子写回** → epoch/提醒/小组件/UI 刷新；合并前副本独立文件；失败保本地不动。回答 v1 空白：不复用 replaceAll 的「清空重建+恢复前副本」路径（防覆盖用户副本、防打断编辑器）；
4. **写路径清单（M1 交付物）**：逐表列「哪些方法改行 → 怎么取组 → 哪些字段算内容 → 删除写什么墓碑 → 撤销怎么重新打戳」；含 targeted SQL（setMood/writeWeather/setPinned/setTopic/setReviewed/markReminded）、批量整写（HabitRepository.update、reorderTopics、moveGroup、TodoRepository.reorderIn/mergeInto/dissolve）、undo/restore 全系、uncheck/uncomplete/立即 purge；加测试：**每个仓储方法执行一次，断言内容戳/状态戳按分类推进或不推进**；
5. **同步层**：SyncDecision CONFLICT 分支改「读-合并-重试」；**download 返回 (text, revision, hash)** 修 meta/GET 竞态（现用旧 meta 的 revision 标记新正文，已核实 AccountManager.kt:429-434）；Http 暴露响应头（Date 校时、X-Revision、X-Content-Hash 校验）；REMOTE_TOO_NEW / parse 兜底双门保留；
6. **时钟**：changedAt = max(设备时间+offset, lastIssued+1)；offset 在每次同步按服务端 Date 头校准；
7. **调度**：前台周期协程 + 回前台钩子 + 编辑后 10s 防抖；设置页「自动同步」（间隔 + 进应用开关）；
8. **历史版本 UI**（§4.7）；
9. **测试**：合并性质、迁移、写路径打戳、`dev-test.sh --all`、Roborazzi 设置页截图。

### 6.2 Web

1. `store.js` 重写为内存态读-合并-写客户端（删 IndexedDB；保留内存 generation/bodyRev 保护；undo 重新打戳；openNote reviewedAt 走状态戳）；
2. `merge.js`（与 Merge.kt 同构）+ **canonical JSON/hash** 实现；**PUT 带 X-Content-Hash**（修现状混用 bug：web 不带 → 服务端 raw hash ≠ Android canonical hash → Android 每次误判「两边都改过」反复冲突，**现在就会发生**）；
3. 状态栏/横幅改造；轮询升级 20s meta（pull→merge→render）；断网与 beforeunload；
4. 导入=replace 语义（合成墓碑 + 重新打戳）；备份读 1–16、写 16。

### 6.3 服务端

- **代码零改动**；仅运维 `ASSETBOARD_HISTORY=30`。

### 6.4 发布顺序

**Android 先发**（读 1–16、写 16），所有安卓升级后再上 Web 16 格式。理由：网页永远最新，网页先写 16 会让所有旧安卓 REMOTE_TOO_NEW（已核实：双向拒绝、不毁数据，但同步停摆）。混用期如实提示升级。备份格式文档 `docs/backup-format.md` 补 16 版条目。

---

## 7. 实施步骤与验收

| 里程碑 | 内容 | 验收 |
|---|---|---|
| **M0 合并语义与不变量** | §6.0 全部 8 项设计产出 → [`sync-merge-semantics.md`](sync-merge-semantics.md) **已定稿**（2026-11-05） | 每条修复/墓碑/归档规则有确定性定义；双端语义无空白 |
| M1 数据模型+写路径 | 双版本戳/墓碑/迁移回填/写路径审计/新 fixture/compare-backups.py 忽略戳字段 → **已完成（2026-11-05）**：`ChangeStamps`、`Tombstone`、`MIGRATION_14_15`、FORMAT 16、`format-16.json`、`StampTest` | 迁移测试（真实旧库）+ 旧备份回填 + 打戳测试 + `--all` 绿 ✅ |
| M2 合并器双实现 | Merge.kt + merge.js + 交叉样本（含哈希）+ 双客户端模拟测试 + 性能基准（几千行/几 MB） → **已完成（2026-11-05）**：`fixtures/sync/`、新 contentHash、`MergeScenarioTest`（两轮收敛 + 6500 行） | 双端同输入逐字节一致（数据+哈希）✅ |
| M3 安卓同步改造 | MergeWriter + SyncDecision 合并化 + 调度 + 设置 UI + 历史 UI → **已完成（2026-11-05）**：`mergeFromJson`、自动合并、replace 墓碑、编辑防抖、周期设置、历史版本 | 五场景由合并语义测试与双客户端测试覆盖，真机待发布后走一遍 ✅ |
| M4 Web 云端化 | store 重写 + 实时上云 + 状态改造 → **已完成（2026-11-05）**：内存态、集中打戳、merge 接入、20s 轮询、导入 replace、1–16 读取 | 网页零本地存储 + 30s SLA + 断网不丢字（keepalive 尽力）✅ |
| M5 收尾 | 文档全清单 + 门禁 + 发布 → **进行中**：文档、门禁脚本、1.4.1 | `dev-test.sh --all` + `web npm test` 全绿 |

顺序说明：M2 先于 M3/M4——最贵的错误（合并语义、哈希）在写 UI 之前暴露。
**测试计划**（评审补充项全纳入）：同篇日记/笔记双端改正文（败者进草稿）、账本估值+并发流水（口径体检）、级联删除/purge 墓碑、复合主键墓碑、跨行不变量修复、同戳平局、contentHash 两端相等、合并与编辑器并发、首次登录/升级首并、恢复/重置/导入、旧客户端混用、调整清单确定性；交叉样本定义输出规范序（数组/对象排序）后才可逐字节比较。
**文档门禁**（M5，评审清单）：docs/write-gate.md:44、docs/backup-format.md:54,71、server/README.md:247、README.md:98、README.en.md:98 的「不合并」表述全部改写；`scripts/compare-backups.py` 忽略 changedAt/changeId/stateAt/stateId（tombstones 不在其遍历范围，另测）；`scripts/dev-test.sh --all`（或单独脚本）纳入 `web npm test`；`docs/backup-format.md` 的 jsonc 示例补 `countdowns` 字段（现缺失）。

---

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| **设备时钟偏移**（LWW 引入的新风险；现状同步只比 revision/hash，无此攻击面） | 同步按服务端 Date 校 offset + 单调打戳（lastIssued+1）；极端偏移仍可能错胜错负——如实告知，服务端历史 30 份兜底 |
| **哈希口径不一致**（现存 bug：web/安卓混用即反复误判冲突） | M2 canonical 规范 + 双实现交叉样本 + PUT 带 X-Content-Hash |
| 合并结果违反跨行不变量 → validate 永久失败 | M0 修复表（重名主题/pinned/超额习惯/孤儿）+ validate 分层（设备日历降级提示）+ 调整清单告知 |
| LWW 整行胜导致状态字段抢版本 | 内容/状态双版本戳 + 批量写回先 diff（§4.1） |
| 败者内容丢失 | 文本三表进草稿（确定性 ID、随同步传播）+ 历史 30 份 + 合并前副本 |
| 墓碑/去重副作用双端不一致 | 墓碑版本=败者版本、草稿 ID 派生自 (行键,changeId)，全部确定性；平局规则墓碑胜 |
| 合并落库丢编辑器内容/覆盖用户副本 | MergeWriter 闸门内 flush→merge→原子写回；合并前副本独立文件（§6.1-3） |
| 快照整 PUT 流量/历史打穿 | 防抖合并连击 + 20s meta 轮询 + 30 份 + 历史 UI；远期可上增量协议 |
| 双实现漂移 | 交叉样本进 CI 门禁，改规则两端同改同测 |
| 后台周期同步受系统 15 分钟下限 | 设置文案注明；前台精确、后台尽力 |
| 双端版本过渡 | Android 先发；REMOTE_TOO_NEW 双向不动（已核实） |
| 升级后首并把升级前硬删除复活/回填戳失真 | 逐表回填取全时间字段 max + pre-merge 副本 + 调整清单；真实旧库测试（M0-6） |

---

## 9. 待确认事项

1. **界面偏好**：主题/隐藏金额留 localStorage（评审同意；推荐保留）？
2. **周期档位**：5（仅前台）/15/30/60/关闭，默认 15（评审建议去掉 5 分钟档或标注仅前台）？
3. **编辑后防抖**：安卓 10s 静默上传 + SLA 改 30 秒（替代 v1 的 30s 防抖 + 5s 验收——评审指出互相矛盾）？
4. **历史策略**：30 份 + 网页保存节奏合并 + 安卓历史 UI（组合方案）；按时间保留需改服务端，暂不做？
5. **账本口径策略**（§4.5）：全并集 + 口径漂移体检徽标（推荐）？还是账本不参与自动合并？
6. **败者归档范围**：文本三表进草稿、其余靠历史（推荐）？还是全表冲突归档？

确认后按 **M0 → M1 → M2 → M3 → M4 → M5** 开工。

---

## 附录 A：外部评审 21 组论断核对结论

核对方式：四路并行逐条对代码验证（Android 写路径、同步栈、Web/服务端、不变量/文档/脚本），2026-11-05。

| 评审项 | 结论 | 关键证据/修正 |
|---|---|---|
| P0-1 系统字段抢版本/败者丢内容 | **属实** | setMood 动 updatedAt 而 writeWeather 不动（TodoDao.kt:147 vs :154）；notes 旁列全不动（NoteEntities.kt:173）；reviewedAt 打开即写（app.js:134）→ §4.1 双版本戳 + §4.3 败者归档 |
| P0-2 账本时间线口径 | **属实**（机制判断成立） | write-gate.md:44 原文属实；口径决议见 §4.5 |
| P0-3 合并落库空白 | **属实** | replaceAll 会 flush→validate→**覆盖 prerestore 副本**→清库重建；exportForSync 不含编辑器文字 → §6.1-3 MergeWriter |
| P0-4 写路径/墓碑清单缺 | **属实** | 批量整写（habits/moveGroup/reorderTopics）、undo/restore 不重打戳、uncheck/uncomplete 物理删均核实 → M1 写路径清单 |
| P0-5 墓碑模型不够 | **属实** | 复合主键、FK CASCADE、purge 30 天均核实；**修正**：purge 后草稿（SET NULL）与完成记录仍留存，非「不留痕迹」 |
| P0-6 跨行不变量 | **属实** | 主题重名/pinned≤1/超额习惯/section 引用均在 validate；**修正**：「同刻无估值」只在 saveFlow:265，validate 不查；**新发现**：validate 依赖设备节假日日历（BackupManager.kt:512）→ §4.3 validate 分层 |
| P0-7 contentHash 双端不可比 | **属实（且是现存 bug）** | web PUT 无 X-Content-Hash（store.js:35）→ 服务端 raw sha256（api.go:593）≠ Android canonical（exportedAt=0、数组排序、键按声明序、encodeDefaults）；自报 hash 仅验格式不重算 → M2 |
| P0-8 5 秒验收矛盾 | **属实** | web 60s 全量 sync 轮询（app.js:220）→ SLA 改 30s、防抖 10s、20s meta 轮询 |
| P0-9 零存储 vs 持久 | **属实** | previous 在 IndexedDB；beforeunload 仅确认；sendBeacon 无头被拒（server.mjs:49-51 写请求要 X-Gain-Request）；**修正**：expectedGeneration 仅表单用，撤销自持 generation；visibilitychange flushEditor 已有 → §1/§5.2 |
| P0-10 恢复/重置/撤销语义 | **属实** | reset/restore 均 replaceAll 整库覆盖；web 导入仅标 dirty；undo 连旧元数据一起还原 → §4.6 三语义 + 重新打戳 |
| P0-11 迁移回填/首升 | **属实** | 回填需含 deletedAt/completedAt/reviewedAt（§4.1）；**修正**：isEmpty 现状已查 netWorthGoal（前瞻风险仅新字段）；goal 删键需改推戳 |
| P1-1 自然键/归档不全 | **属实** | 补 noteTopics.name；weeklyDrafts 被漏；**修正**：重复完成记录是软删 todos 行（有回收）；foodEntries/countdowns/checklistItems 也无归档 |
| P1-2 变更组不等于同胜同负 | **属实** | 组级去重/修复入 §4.3-3 |
| P1-3 确定性副作用 | **属实** | 墓碑版本=败者版本、草稿 ID 派生、平局墓碑胜（§4.2/4.3） |
| P1-4 download 竞态 | **属实** | AccountManager.kt:429-434 用旧 meta 标记新正文；Http.kt:14 丢弃响应头 → §6.1-5 |
| P1-5 时钟对策不可执行 | **属实** | Http 不暴露 Date；现状无时钟问题（revision/hash 决策），LWW 才引入 → §6.1-6 |
| P1-6 历史 30 份不够/安卓无入口 | **属实** | 安卓确无历史 UI；服务端已有 /sync/revisions → §4.7 组合方案 |
| P1-7 混用版本 | **属实** | REMOTE_TOO_NEW 双向拒绝（不毁数据）；网页先上 16 会停掉旧安卓 → §6.4 Android 先发 |
| P1-8 文档/门禁 | **属实** | 5 处「不合并」表述、compare-backups.py 整行比较（只比 LEDGER 6+HEALTH 2 表）、dev-test 不跑 web 测试 → §7 门禁 |
| P1-9 表单/撤销/多标签 | **属实** | navigator.locks 已串行、拉新即刷需改 merge → §5.2 |
| P1-10/11 测试与性能 | **属实** | 测试矩阵扩充（§7）、M2 加基准 |
| P1-12 小问题 | **部分属实** | **更正**：两端实际读 1..FORMAT_VERSION（现 15），v1「读 10–16」写错→1–16；healthProfile 单行 upsert 属实；24 表实为 27（3 张设备本地）；墓碑表名定为 BackupFile 字段名 |
