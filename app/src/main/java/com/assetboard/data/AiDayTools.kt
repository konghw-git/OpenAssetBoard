package com.assetboard.data

import com.assetboard.domain.AiAgent
import com.assetboard.domain.AiEdit
import com.assetboard.domain.Bills
import com.assetboard.domain.Dates
import com.assetboard.domain.HabitDay
import com.assetboard.domain.Money
import com.assetboard.domain.Mood
import com.assetboard.domain.date
import com.assetboard.domain.health.Energy
import com.assetboard.domain.todo.TodoViews
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The diary agent's window into the user's data: every domain but the notes. Each tool reads the
 * repositories when it is called, so what the model sees is what the database holds at that
 * moment — the stale-screen problem the fixed prompt used to have. Formatting is kept here (thin,
 * read-only) and the wording rules stay in [AiEdit].
 */
class AiDayTools(
    private val diary: DiaryRepository,
    private val todos: TodoRepository,
    private val habits: HabitRepository,
    private val health: HealthRepository,
    private val food: FoodRepository,
    private val ledger: LedgerRepository,
    private val countdowns: CountdownRepository,
    private val weeks: WeekRepository,
    private val goal: GoalRepository,
) {
    /** The seed for a day, read fresh: the facts the old prompt hard-coded, minus nothing. */
    suspend fun dayFacts(date: LocalDate): AiEdit.DayFacts {
        val e = diary.entryOn(date)
        val h = habits.observeDay(date).first()
        val done = todos.observeCompletedOn(date).first()
        val series = ledger.loadOnce().netWorthSeries()
        val at = series.indexOfLast { Dates.localDate(it.snapshot.at) == date }
        return AiEdit.DayFacts(
            date = date,
            mood = Mood.of(e?.mood)?.label,
            weather = e?.let { w ->
                val temps = when {
                    w.tempMin != null && w.tempMax != null -> "${w.tempMin!!.toInt()}~${w.tempMax!!.toInt()}°C"
                    w.tempMax != null -> "${w.tempMax!!.toInt()}°C"
                    w.tempMin != null -> "${w.tempMin!!.toInt()}°C"
                    else -> null
                }
                listOfNotNull(WeatherService.describe(w.weatherCode).ifBlank { null }, temps, w.city)
                    .joinToString("，").ifBlank { null }
            },
            completed = done.map { it.title },
            habitsDone = h.done,
            habitsDue = h.due,
            habits = h.shown.map { it.label to (it.key in h.checked) },
            stocktake = at.takeIf { it >= 0 }?.let { i ->
                val p = series[i]
                val change = if (i > 0 && p.netWorth != series[i - 1].netWorth) {
                    "（较上次 " + Money.format(p.netWorth - series[i - 1].netWorth, signed = true) + "）"
                } else ""
                "¥" + Money.short(p.netWorth) + change
            },
        )
    }

    /** Everything the model may ask for about [day]. Keep names stable: they land in the prompt. */
    fun tools(day: LocalDate): List<AiAgent.Tool> = listOf(
        AiAgent.Tool(
            name = "day_overview",
            description = "某天的概览：心情、天气、每日指标、完成的待办、当天盘点",
            args = "date: yyyy-MM-dd（可省，默认写日记的那天）",
        ) { args -> AiEdit.daySummary(dayFacts(args.date("date", day))) },
        AiAgent.Tool(
            name = "todos",
            description = "某天完成的待办，以及那天计划中、逾期未完成的待办",
            args = "date: yyyy-MM-dd（可省，默认当天）",
        ) { args -> todosText(args.date("date", day)) },
        AiAgent.Tool(
            name = "habits",
            description = "某天的每日指标打卡情况",
            args = "date: yyyy-MM-dd（可省，默认当天）",
        ) { args -> habitsText(args.date("date", day)) },
        AiAgent.Tool(
            name = "mood_history",
            description = "一段时间里每天的心情",
            args = "from, to: yyyy-MM-dd（可省，默认最近 7 天，含当天）",
        ) { args ->
            val to = args.date("to", day)
            val from = args.date("from", to.minusDays(6))
            val moods = diary.observeMoods(from, to).first()
            if (moods.isEmpty()) "（这段时间没有记心情）"
            else moods.toSortedMap().entries.joinToString("\n") { (d, m) -> "$d：${Mood.of(m)?.label ?: "未知"}" }
        },
        AiAgent.Tool(
            name = "diaries",
            description = "一段时间的日记正文（以往的日记可以参考口吻和事件）",
            args = "from, to: yyyy-MM-dd（可省，默认最近 7 天，含当天）",
        ) { args ->
            val to = args.date("to", day)
            val from = args.date("from", to.minusDays(6))
            val entries = diary.observeBetween(from, to).first()
            if (entries.isEmpty()) "（这段时间没有日记）"
            else entries.joinToString("\n\n") { e -> "${e.date}：\n" + e.body.take(600).ifBlank { "（空）" } }
        },
        AiAgent.Tool(
            name = "health",
            description = "某天的身体数据：体重、体脂",
            args = "date: yyyy-MM-dd（可省，默认当天）",
        ) { args ->
            val m = health.onDate(args.date("date", day))
            if (m == null) "（这天没有称重）"
            else listOfNotNull(
                "${m.weightG / 1000.0}kg",
                m.bodyFatBp?.let { "体脂 ${it / 100.0}%" },
                m.note,
            ).joinToString("，")
        },
        AiAgent.Tool(
            name = "food",
            description = "某天的饮食记录",
            args = "date: yyyy-MM-dd（可省，默认当天）",
        ) { args ->
            val entries = food.onDate(args.date("date", day))
            if (entries.isEmpty()) "（这天没有记录饮食）"
            else entries.joinToString("\n") { f ->
                listOfNotNull(f.name?.takeIf { it.isNotBlank() }, "${Energy.energyOf(f) / 1000.0}kcal")
                    .joinToString(" ")
            }
        },
        AiAgent.Tool(
            name = "bills",
            description = "某天的账单：每笔支出或收入的分类、金额和备注",
            args = "date: yyyy-MM-dd（可省，默认当天）",
        ) { args ->
            val on = args.date("date", day)
            val flows = ledger.loadOnce().flows
                .filter { Dates.localDate(it.at) == on && it.kind.isBill }
                .sortedBy { it.at }
            if (flows.isEmpty()) "（这天没有收支）"
            else flows.joinToString("\n") { f ->
                val label = Bills.category(f).label
                val sign = Money.format(Bills.signed(f), signed = true)
                listOf(label, sign, f.note?.takeIf { it.isNotBlank() }.orEmpty()).joinToString(" ").trim()
            }
        },
        AiAgent.Tool(
            name = "net_worth",
            description = "最近几次盘点的净资产和变化",
            args = "无需参数",
        ) {
            val series = ledger.loadOnce().netWorthSeries()
            if (series.isEmpty()) "（还没有盘点过）"
            else series.takeLast(5).mapIndexed { i, p ->
                val prev = series.getOrNull(series.size - 5 + i - 1)
                val change = prev?.let { "（较上次 " + Money.format(p.netWorth - it.netWorth, signed = true) + "）" }.orEmpty()
                "${Dates.localDate(p.snapshot.at)}：¥${Money.short(p.netWorth)}$change"
            }.joinToString("\n")
        },
        AiAgent.Tool(
            name = "countdowns",
            description = "倒数日清单：名称、日期、离某天还有多少天",
            args = "date: yyyy-MM-dd（可省，默认当天，用于算剩余天数）",
        ) { args ->
            val from = args.date("date", day)
            val list = countdowns.all()
            if (list.isEmpty()) "（没有倒数日）"
            else list.joinToString("\n") { c ->
                val left = ChronoUnit.DAYS.between(from, LocalDate.parse(c.date))
                "${c.title}：${c.date}" + (if (c.yearly) "（每年）" else "") + "，" +
                    (if (left >= 0) "还有 $left 天" else "已过 ${-left} 天")
            }
        },
        AiAgent.Tool(
            name = "week_review",
            description = "某天所在周的周复盘正文",
            args = "date: yyyy-MM-dd（可省，默认当天）",
        ) { args ->
            val r = weeks.reviewOf(Weeks.start(args.date("date", day)))
            if (r == null || r.body.isBlank()) "（这周还没写复盘）" else r.body
        },
        AiAgent.Tool(
            name = "goal",
            description = "净资产目标（元）",
            args = "无需参数",
        ) {
            val cents = goal.observe().first()
            if (cents == null) "（没有设目标）" else "目标 ¥" + Money.short(cents)
        },
    )

    private suspend fun todosText(date: LocalDate): String {
        val all = todos.loadOnce()
        val done = all.todos.filter { it.completedAt != null && Dates.localDate(it.completedAt) == date }
        val due = all.todos.filter { TodoViews.isOpen(it) && it.planDate != null && it.planDate!! <= date.toString() }
        return buildString {
            if (done.isNotEmpty()) {
                append("完成的：")
                done.forEach { append("\n· ${it.title}") }
            }
            if (due.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("计划中或逾期未完成的：")
                due.forEach { append("\n· ${it.title}（计划 ${it.planDate}）") }
            }
            if (isEmpty()) append("（这天没有待办记录）")
        }
    }

    private suspend fun habitsText(date: LocalDate): String = formatHabits(habits.observeDay(date).first())

    /** The counts and which habit was ticked, the same marks the day's summary carries. */
    private fun formatHabits(h: HabitDay): String =
        if (h.shown.isEmpty()) "（没有每日指标）"
        else "${h.done}/${h.due}：" + h.shown.joinToString("、") {
            it.label + if (it.key in h.checked) "✓" else "✗"
        }
}
