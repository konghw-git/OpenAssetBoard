<div align="center">

<img src="docs/images/icon.png" width="96" alt="日盈">

# 日盈 Gain · OpenAssetBoard

*日日行动，日日有盈*

**一个默认完全本地运行的 Android 个人 App：资产看板 + 待办 + 日记 + 笔记。**

打开就知道自己有多少钱，顺手记下今天要做的事和今天发生了什么。默认没有账号、没有云、没有广告；需要时再登录你自己的服务器同步。

[![Release](https://img.shields.io/github/v/release/konghw-git/OpenAssetBoard?style=flat-square&color=1E5B4F)](https://github.com/konghw-git/OpenAssetBoard/releases/latest)
[![License](https://img.shields.io/github/license/konghw-git/OpenAssetBoard?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#安装)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#技术栈)

[English](README.en.md) · **简体中文**

</div>

<img src="docs/images/banner.webp" width="100%" alt="日盈 — 资产看板、待办与日记">

<img src="docs/images/24-overview-fold.webp" width="100%" alt="资产总览（折叠屏双栏）">

## 它解决什么问题

大多数记账 App 想让你**逐笔记一笔消费**。但很多人真正的用法是另一种：每个月（或每周）打开一次，把各个账户的余额校对成真实值，然后看净资产是涨了还是跌了。

「日盈」就是为这个用法做的。一次盘点扫过所有账户、改掉变动的三五个、保存，目标 **60 秒内完成**。在这之上，投资账户可以记申购 / 赎回 / 分红流水，看市值和累计盈亏；想记账时下拉就能记一笔，余额跟着变；待办和日记则把「今天该做什么」和「今天发生了什么」收进同一个本地数据库。

| 是 | 不是 |
|---|---|
| 打开即看净资产与资产配置 | 非得逐笔记账才能用 |
| 批量盘点，行内直接改余额；想记时下拉记一笔 | 预算、自动导入账单 |
| 投资账户的流水、净投入与累计盈亏 | 行情接口、自动估值、收益率 |
| Things 3 式的待办与可靠的到点提醒 | 标签、团队协作、番茄钟 |
| 一天一篇纯文字日记，自动附上农历、天气、当天完成的事 | 图片、富文本 |

## 功能

### 资产

- **净值总览**：净资产、总资产 / 总负债、负债率，净资产卡里一个小环形图看各分组的配置比例；账户按分组折叠，收起时只看小计，展开看每个账户。
- **攒钱目标**：在净资产卡上设一个目标资产，卡片里显示进度条、还差多少，以及按近半年盘点的速度大约还要多久。
- **盘点**：整页行内编辑，只为真实变动的账户改数字；支持给历史日期补录；每次盘点生成一个净值点。
- **净值趋势**：3 月 / 半年 / 1 年 / 全部，期间最高 / 最低 / 涨跌额与涨跌幅；资产与负债的柱线组合图。
- **投资账户**：申购、赎回、分红、转账、还款、借款、外部转入转出；手续费有承担方，盈亏口径不会算错；市值与净投入双线。
- **账户管理**：分组、图标（内置常见银行 / 支付 / 基金品牌图标）、计入净资产开关、停用、「锁定至」日期、期初前移。展开分组后，账户左滑化成粒子删除（已有记录的改为停用，都可撤销），右滑在这一行上直接改余额。
- **记账**：资产页往下拉就记一笔：支出或收入、金额（自带数字键，可以 + − 把小票加起来）、分类一点即选，账户和日期按上次与常用的猜好，多数情况三下就记完；「再记」连着记下一笔。每笔记在一个账户上，余额跟着变，之后的盘点仍以盘点为准。资产页的记账卡片看本月支出、近 7 天和最近几笔；「账单」按月看收支、结余、日均和分类占比，按天列出每一笔，点开修改、左滑删除，都可撤销。账户页也能直接记一笔（信用卡记支出即增加欠款）。
- **可用现金**：从计入净资产的账户里排除基金投资和锁定资金，得出随时能用的钱，在净资产卡里用一条分段条显示现金 / 锁定 / 投资的占比，点开列出每个现金账户。
- **健康资产**：每天称一次，记体重（体脂率可选），看体重 / 体脂率 / BMI 三条曲线与 7 天平均；BMI、体脂分级、脂肪量、去脂体重、目标进度与九格身体类型。没称的日子就空着，不补点、不断签。
- **饮食与热量**：大多数食物估个大概的 kcal 就能记，营养素都是可选的；有包装时照着营养成分表填（每 100 g/ml 的能量等，再填吃了多少），kJ 自动换算成 kcal。最近吃过的点一下就填好。按档案和体重估算基础代谢，显示摄入占基础代谢的百分比、不运动时的缺口和近 7 天的摄入；只记录，不设目标。
- **桌面小组件**：四个玻璃质感的小组件，跟随系统深浅色，可拖动调整大小。资产看板显示净资产、较上次盘点的变化、走势线和各分组占比，一键盘点，眼睛可在桌面隐藏金额；今天待办点圆圈即完成（再点撤销），点标题在桌面上弹出的卡片里改名、标重要、推到明天或删除；笔记点开就在桌面上编辑，自动保存；倒数日显示离重要日子还有几天，生日纪念日可每年重复。设置里「桌面小组件」可一键添加到桌面。

<img src="docs/images/01-overview.webp" width="24%" alt="资产总览"> <img src="docs/images/28-health.webp" width="24%" alt="健康资产"> <img src="docs/images/04-trend.webp" width="24%" alt="净值趋势"> <img src="docs/images/07-account-investment.webp" width="24%" alt="投资账户">

### 待办

- **Things 3 式结构**：收件箱 → 今天 → 计划 → 以后再说，加自定义清单与分区；条目可带截止日期、重要标记、备注和检查清单，检查项像一根线上的节点挂在待办卡片下，点一下就勾上。
- **拖动待办**：长按标题后上下拖动排序，今天视图完全按手动顺序排，过期、重要的条目也能拖到任意位置；靠近边缘自动滚动，越靠边、停得越久滚得越快，长距离拖动不会中途断开；清单里拖过分区标题即移入该分区。上下拖只排序，悬停不会误并入；要并入检查项，把它拖到目标下方再向右滑，卡片上会提示并入哪一条（可撤销）。长按不动是多选，拖动选中的其中一条，其余几条跟着一起走；正在原地编辑别的待办时也能直接拖。
- **拖动检查项**：长按检查项文字，在同一待办内调整顺序，或拖到另一条待办的检查项之间：其余检查项实时让位，落点留出虚线空位，所见即松手后的位置。靠近列表边缘会自动滚动，移动后可撤销，文字与勾选状态保留。详情页可长按检查项右侧的拖动把手排序，文字区域仍用于编辑。
- **清单卡片**：清单一行一张卡片，截止日期以标签挂在名字下方；左滑归档、右滑删除（都可撤销），长按拖动调整顺序。待办的计划日、过期和截止标签排在标题下、提醒时刻前，空间不足时换行。
- **多选整理**：选中几条后，可以改期、移动、删除，也可以把它们变成一条新待办的检查项（在新条目上原地起名，可撤销），或者并入已有待办的检查项；带检查项的待办可以原地解散，检查项各自变回独立的待办。
- **一个转盘**：首页中央是放射状导航，一圈显示各视图的条目数，点一下切换；右侧是今天的每日打卡。
- **每日打卡**：默认吃补剂、健身、控制饮食、喝够水、早睡早起；点「每日」可改名、换图标和颜色、写打卡标准（每行一条）、调顺序、新增（最多 7 项）或移除。在待办页点一下打勾，按住浮出这一项的打卡标准、松手消失；日记页只读展示当天的那几项，改动不影响过去的记录。
- **可靠的提醒**：普通通知、强提醒（全屏 + 响铃 + 震动，深度休眠也准时）、重复规则的每期独立提醒；可以提前提醒（准时、5 / 15 / 30 分钟、1 / 2 小时、1 天）；开机补发、权限被撤销后的补救。
- **快速记录**：桌面小组件点 `+` 直接输入，从点开到保存目标 5 秒内；笔记、倒数日的小组件同样点 `+` 就记。
- **滑动操作**：左滑删除（卡片化成粒子散去，底部小胶囊可撤销），右滑出完成 / 改期 / 标重要；检查项也能单独左滑删除。在条目上横滑只操作条目，条目以外的地方横滑切换 tab，底部 tab 栏上左右滑也能切换。列表顶部下拉即新建待办，资产页下拉即记一笔。
- **回收站与日志**：删除的待办、清单、日记、周复盘、笔记保留 30 天；已完成的重复记录留在日志。

<img src="docs/images/11-todo-home.webp" width="24%" alt="待办"> <img src="docs/images/12-todo-composer.webp" width="24%" alt="快速记录"> <img src="docs/images/25-todo-unfold.webp" width="24%" alt="待办（折叠屏）"> <img src="docs/images/15-todo-detail.webp" width="24%" alt="待办详情">

### 日记

- **一天一篇纯文字**，自动附上农历日期、节气、法定节假日与调休、当天天气。心情、每日指标、当天盘点、周复盘和完成的待办收在正文上方的一张卡片里；往下拉就开始写，正文直接写在页面上，没有边框。
- **不会丢字**：停止输入 1 秒即落盘；一直不停地打字时，每 4.5 秒也至少落盘一次。保存失败会明确提示，并把内容留在编辑框里等你自己处理。
- **日历与搜索**：按月浏览，按关键词搜全部日记，「那年今日」回顾往年同一天。
- **周复盘**：挂在日记的时间轴上，一周一篇。页面顶部是这周的数字（日记天数、每日指标、完成的待办、体重和净资产的变化；体重比的是两周各自称重的平均，净资产按账户余额算，行内改余额和流水也计入），中间是这周七天的日记和从中提炼的笔记，最下面写复盘，同样自动保存。日记页每天下方有入口，周一和周日晚上会提醒；日历每一行末尾标出写没写。

<img src="docs/images/18-diary.webp" width="24%" alt="日记"> <img src="docs/images/19-diary-calendar.webp" width="24%" alt="日历"> <img src="docs/images/20-diary-dark.webp" width="24%" alt="日记（深色）"> <img src="docs/images/23-reminder-guide.webp" width="24%" alt="提醒设置检查">

### 笔记

- **日记按天记，笔记按主题攒**：一个想法一篇，反复修订，越写越厚。第一行就是标题，`#标签` 高亮。
- **从日记提炼**：在日记页点灯泡按段落勾选，或在正文里长按选中一段、从弹出菜单选「提炼到笔记」；存成新笔记（没填标题就用「10月20日 日记摘录」）或追加到已有笔记。原文不动，笔记记下摘自哪天，点一下回到那天。周复盘也能这样提炼。
- **今日回顾**：每天挑一篇最久没碰过的笔记放回列表顶部，当天固定；看过或关掉后它排到队尾。
- **一层主题**：横排筛选条末尾固定一个按钮，点开是全部主题的网格（主题多了可以搜），新建、管理都在里面；全文搜索所有笔记。
- **顺序与滑动**：长按笔记拖动排序（也可以改按最近更新或最近创建排列），长按不动是多选，选中的可以一起拖、一起删；左滑删除，右滑置顶或换主题。每篇笔记是一张清透的玻璃卡片，标题行末尾是主题和时间，下面两行正文，和桌面小组件一个样子；玻璃在设置里可选「清透」或「深色」。新建笔记：在列表顶部下拉，松手即可。
- **和日记一样不会丢字**：同样的自动保存与冲突转草稿，恢复备份前未保存的笔记也会先落盘。

<img src="docs/images/32-notes.webp" width="24%" alt="笔记"> <img src="docs/images/33-note.webp" width="24%" alt="一篇笔记"> <img src="docs/images/34-extract.webp" width="24%" alt="从日记提炼"> <img src="docs/images/35-week.webp" width="24%" alt="周复盘">

### 共同

- **本地优先**：数据默认只在本机 SQLite；备份是一个 JSON 文件，写到你选的文件夹，每次覆盖同一个文件。
- **可选的账号与同步**：账号由服务器管理员创建；登录后整份备份通过 HTTPS 同步到你自己的服务器。同一台手机可以切换多个账号，每个账号一个独立的本地数据库；不登录时完全本地运行。冲突不会自动合并，由你选择保留本机还是使用云端。服务端与协议见 [`server/README.md`](server/README.md)。
- **备份与恢复**：格式带版本号（当前 v14，能读 v1–v13）；恢复是整库覆盖，恢复前自动另存当前数据；恢复有专门的写入闸门，旧操作不会写进新数据。
- **联网是可选且可一键关闭的**：关掉后不发起任何网络请求，节假日用内置数据，不显示天气。
- **玻璃拟态界面**：每个页面底下铺着几块色彩，打开时缓缓晕开；卡片、标签和按钮是带亮边的半透明玻璃，底部标签栏是悬浮的毛玻璃。净资产卡是暖色玻璃，琥珀、珊瑚、柿红的色块在下面缓慢流动，一点光沿着边缘游走。玻璃在设置里可选「清透」或「深色」。
- **动效**：卡片依次入场，按住时朝手指倾斜，勾选时扩散一圈主题色；净资产打开时从零滚到实际值；页面从右侧推入，标签栏的高亮跟着翻页滑动。
- **墨绿、雾白与香槟金配色**，支持深色模式、跟随系统、涨跌颜色切换（默认红涨绿跌）和折叠屏双栏布局。
- **资产看板分层**：净资产、资产配置、账户明细分别呈现，减少金额重复；窄屏和大字号下调整布局、自动缩放金额。
- **应用内升级**：设置 › 应用更新，从 GitHub Releases 下载新版、校验签名后交给系统安装，数据保留；默认每天检查一次，可关。预置直连和几个国内加速镜像，一键测速选最快的。
- **桌面快捷方式**：长按图标直达盘点 / 记待办 / 写日记。

<details>
<summary>更多截图</summary>

<br>

<img src="docs/images/02-overview-dark.webp" width="24%" alt="资产总览（深色）"> <img src="docs/images/09-account-edit.webp" width="24%" alt="账户设置"> <img src="docs/images/10-flow-buy.webp" width="24%" alt="申购流水"> <img src="docs/images/08-account-cash.webp" width="24%" alt="现金账户">

<img src="docs/images/03-stocktake.webp" width="24%" alt="盘点"> <img src="docs/images/30-cash.webp" width="24%" alt="可用现金"> <img src="docs/images/29-health-figures.webp" width="24%" alt="身体成分"> <img src="docs/images/31-health-dark.webp" width="24%" alt="健康资产（深色）">

<img src="docs/images/13-todo-upcoming.webp" width="24%" alt="计划"> <img src="docs/images/14-todo-list.webp" width="24%" alt="清单与分区"> <img src="docs/images/17-todo-inbox.webp" width="24%" alt="收件箱"> <img src="docs/images/16-todo-dark.webp" width="24%" alt="待办（深色）">

<img src="docs/images/05-trend-balance.webp" width="24%" alt="资产与负债"> <img src="docs/images/06-trend-change.webp" width="24%" alt="涨跌"> <img src="docs/images/21-trash.webp" width="24%" alt="回收站"> <img src="docs/images/22-settings.webp" width="24%" alt="设置">

<img src="docs/images/26-account-icons.webp" width="49%" alt="内置账户图标"> <img src="docs/images/27-account-icons-dark.webp" width="49%" alt="内置账户图标（深色）">

</details>

## 安装

从 [Releases](https://github.com/konghw-git/OpenAssetBoard/releases/latest) 下载最新的 `OpenAssetBoard-x.y.z.apk`，在手机上打开安装（需要允许「安装未知来源应用」）。

- 支持 **Android 10（API 29）及以上**。
- 目标用户是国内手机（vivo / 小米 / OPPO 等）。**提醒的准时性依赖系统后台权限**：安装后请在「设置 → 提醒设置检查」里按引导打开自启动、后台运行、通知和精确闹钟。没有 Google 服务也能用。
- App 没有上架任何应用商店。从 0.8.0 起可以在「设置 → 应用更新」里直接升级：下载、校验签名、先备份，再交给系统安装；更早的版本需要手动下载安装一次。
- 应用名是「日盈」（英文名 Gain，0.9.0 之前叫「强者之路」），包名 `com.assetboard`，仓库名是 OpenAssetBoard——它们是同一个东西。

> ⚠️ 这是个人项目，没有经过专业金融软件的审计。用它管钱之前，请先导入几天的测试数据试一遍，并定期备份。

## 数据与隐私

- **默认情况下，资产、待办、日记的内容只在本机**。不登录也能使用全部功能；没有统计上报、没有广告。
- **账号同步是可选的自建服务**。只有你主动登录后，整份备份 JSON 才会通过 HTTPS 上传到**你自己的服务器**。服务器上的快照是明文（没有端到端加密），账号由管理员在服务器上创建；退出登录后数据仍保留在本机。不登录时，内容不会发往任何服务器。
- 每一次写入都经过一个写入门（`WriteGate`）：恢复期间拒绝新写入，携带过期数据纪元的旧操作也不会写进恢复后的数据。
- **默认会联网**，但你可以关掉。联网只用于：
  - 写日记时取当天天气：把大致坐标发给 [Open-Meteo](https://open-meteo.com/)（开源、无需密钥）。只发粗粒度坐标，不发内容。
  - 每年 11 月起每周检查一次下一年的放假安排：从 [NateScarlet/holiday-cn](https://github.com/NateScarlet/holiday-cn) 下载一个公开 JSON。
  - 检查更新：每天至多一次读取本仓库最新 Release 的信息，有新版且你点了下载才下载安装包。只下载，不上传任何东西。
  - 登录账号后的同步：只发往你在登录页填写的自己的服务器；不登录则不存在这类请求。
- 备份文件是明文的 JSON，放在你自己选的文件夹里，**没有任何加密**。服务器上的快照同样没有加密，请自行保管好 VPS 和备份。

## 从源码构建

需要 JDK 21 和 Android SDK（platform 36 / 37）。

```bash
# 一条命令装好 JDK 21、Android SDK、Gradle，并接受 SDK 许可（全部装在 ~ 下，不需要 sudo）
scripts/setup-toolchain.sh
source ~/.local/share/dev-dl/android-env.sh

./gradlew testDebugUnitTest   # 全部单元测试：计算规则、仓储、恢复、迁移、界面
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

打 release 包需要自己的签名（仓库里不含任何密钥）：

```bash
scripts/create-keystore.sh    # 生成到你自己的目录，务必另存一份
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

> Debug 包的包名是 `com.assetboard.debug`，与正式包并存，不会覆盖、不会清空正式包的数据。**正式包一旦用不同签名覆盖安装会被 Android 拒绝；卸载会清空本地数据库**，升级前请先备份。

### 截图与基准图

界面截图由 Roborazzi 在 JVM 上渲染真实 Compose 界面得到，不需要设备：

```bash
scripts/dev-test.sh --all      # 全部测试，截图与 app/screenshots/ 的基准图逐像素比对，再跑 lint
scripts/dev-test.sh --accept   # 界面有意改动后：把这次画出的截图收为新基准图
```

截图里的「今天」是固定的一天，基准图不会隔天过期。刚克隆时没有基准图，`--all` 会把每张图列为新增，先 `--accept` 一次。`scripts/publish-release.sh` 发布前跑的就是同一个 `--all`。`docs/images/` 里的图是这些 PNG 缩放、转成 WebP 后的版本，横幅用 `scripts/dev-test.sh --brand` 重画。

## 技术栈

| | |
|---|---|
| 语言 / UI | Kotlin 2.4、Jetpack Compose、Material 3 |
| 架构 | MVVM + `StateFlow`，手写 `AppContainer`（无 DI 框架） |
| 存储 | Room（schema 导出并随仓库版本化，显式迁移 1 → 2 → … → 12） |
| 后台 | WorkManager、AlarmManager、前台服务 |
| 桌面 | Jetpack Glance 小组件 |
| 图表 | 全部自绘 Canvas，无图表库 |
| 农历 / 节气 / 调休 | [cn.6tail:lunar](https://github.com/6tail/lunar-java) + 内置官方调休表 |
| 测试 | JUnit + Robolectric，含 Roborazzi 截图测试 |
| 最低版本 | Android 10（API 29） |

## 代码结构

```
app/src/main/java/com/assetboard/
├── domain/           纯规则：余额、净投入、盈亏、净值趋势、健康分级与趋势、待办视图与重复、农历
├── data/             每次写入的唯一入口：校验、事务、写入门、备份与恢复、迁移
├── ui/               Compose 页面（资产 / 健康 / 待办 / 日记 / 笔记 / 设置），components 里是自绘图表与共享控件
├── reminder/         闹钟、通知、强提醒响铃服务、全屏提醒、开机补发
└── widget/           Glance 桌面小组件
```

设计原则是**计算逻辑与界面分离**：所有出现在屏幕上的数字都由 `domain/` 里的纯函数算出，`data/` 里每个写操作都在一个事务内完成，约束由 `Rules.kt` 与仓储层共同保证。因此核心规则可以在 JVM 上不依赖设备地测试。

| 文件 | 内容 |
|---|---|
| `domain/Ledger.kt` | 余额、净投入、盈亏、盘点合计 |
| `domain/health/` | BMI、体脂分级、身体类型、健康趋势与移动平均 |
| `domain/todo/` | 视图归属与排序、重复规则、提醒状态、不变量 |
| `data/WriteGate.kt` | 读写闸门 + 数据纪元 |
| `data/BackupManager.kt` | JSON 导出（v13，可读 v1–v12）、校验、恢复协调器 |
| `domain/calendar/` | 农历、节气、节日、官方调休表 |

## 路线图

- [x] 资产总览、盘点、净值趋势、投资流水、记账
- [x] 待办、日记、每日指标、回收站
- [x] 备份 / 恢复、提醒与小组件
- [x] 健康资产（体重、体脂率、BMI）、可用现金
- [x] 饮食热量记录（包装换算、每日缺口）
- [x] 笔记：一层主题、从日记提炼、溯源、搜索
- [x] 笔记今日回顾、选字提炼、周复盘
- [ ] 统一搜索（日记、周复盘、笔记一起搜）、`[[双链]]`
- [ ] 收益率（XIRR，资金加权年化）
- [ ] 目标配置对比
- [ ] 信用卡账单日与还款日提醒
- [ ] 份额 × 净值自动估值
- [ ] 应用锁

## 深入阅读

规则写下来才能被质疑。这几篇是代码里那些「为什么这样算」的答案：

- [计算规则](docs/calculations.md) — 余额、净投入、累计盈亏、净值趋势，以及手续费为什么只算一次
- [写入闸门与数据纪元](docs/write-gate.md) — 恢复中途的旧操作为什么写不进来
- [提醒是怎么做到准时的](docs/reminders.md) — 精确闹钟、强提醒、权限被撤销后的补救、令牌
- [备份与恢复](docs/backup-format.md) — 文件格式、原子替换、整库覆盖的流程
- [健康资产](docs/health.md) — 一天一条、缺测日、anchored 起点、分级表的来源

## 参与贡献

欢迎 issue 和 PR，也欢迎在 [Discussions](https://github.com/konghw-git/OpenAssetBoard/discussions) 里聊聊你是怎么用它的。提交前请看 [CONTRIBUTING.md](CONTRIBUTING.md)：

- `./gradlew testDebugUnitTest` 全部通过。
- 计算规则相关的改动请在 `domain/` 的纯函数里加测试，不要在界面层做计算。
- 涉及数据模型或备份格式的改动需要同时更新 `app/schemas/` 与迁移测试。
- 界面文案目前是中文硬编码，暂不打算做多语言界面；README 的中英两版是例外。

安全问题的报告方式见 [SECURITY.md](SECURITY.md)。

## 致谢

- [6tail/lunar-java](https://github.com/6tail/lunar-java) — 农历、节气与节日（MIT）
- [NateScarlet/holiday-cn](https://github.com/NateScarlet/holiday-cn) — 中国法定节假日与调休数据
- [Open-Meteo](https://open-meteo.com/) — 免费天气与地理编码接口
- 账户图标中包含各银行、支付与基金机构的商标，仅用于识别用户自己的账户，版权归各自所有。

## 许可证

[Apache License 2.0](LICENSE)
