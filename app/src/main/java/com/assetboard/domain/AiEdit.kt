package com.assetboard.domain

import com.assetboard.domain.notes.NoteMarkdownEdit
import java.time.LocalDate

/** Which AI pass the editor asked for. */
enum class AiAction { FORMAT, POLISH, GENERATE }

/**
 * The AI editor helpers, kept as pure text: the prompts (one per [AiAction], each told to do only
 * its own job), the reply cleanup, and the splice of a reply back into the body. The network side
 * is [com.assetboard.data.AiService]; what a day contributes to the generated diary is [daySummary].
 */
object AiEdit {
    const val FORMAT_SYSTEM =
        "你是文本排版助手。用户会给你一段文本，请只做排版，不做任何改写：\n" +
            "· 修正标点的全半角与误用，统一空格；\n" +
            "· 合并多余空行，理顺段落之间的空行；\n" +
            "· 整理 Markdown 结构（标题、列表、引用等）的格式；\n" +
            "· 不增删改写任何一个字词，不改变句子顺序和段落划分（空行整理除外）。\n" +
            "只输出排版后的文本，不要任何解释，不要用代码块包裹。"

    const val POLISH_SYSTEM =
        "你是中文写作润色助手。用户会给你一段文本，请只做润色：\n" +
            "· 在保持原意、事实、人称、语气和要点不变的前提下，让表达更通顺、自然、书面一些；\n" +
            "· 可以调整用词和句式、修正语病，但不增删要点，不改变段落结构；\n" +
            "· 保留原文的 Markdown 格式（标题、列表、加粗等）。\n" +
            "只输出润色后的文本，不要任何解释，不要用代码块包裹。"

    const val DIARY_SYSTEM =
        "你是日记助手。用户会给你某一天的记录（完成的待办、每日指标、心情、天气、盘点等）。" +
            "请据此写一篇第一人称的中文日记：\n" +
            "· 只写记录里有依据的内容，不编造没有发生的事；记录很少就写短一点；\n" +
            "· 语气自然，像本人随手记的，不要写成总结报告；\n" +
            "· 用 Markdown 适当分段，普通段落首行缩进两个全角空格。\n" +
            "只输出日记正文，不要任何解释，不要用代码块包裹。"

    fun systemFor(kind: AiAction): String = when (kind) {
        AiAction.FORMAT -> FORMAT_SYSTEM
        AiAction.POLISH -> POLISH_SYSTEM
        AiAction.GENERATE -> DIARY_SYSTEM
    }

    /** One day as the facts the generated diary may draw on. Strings are pre-formatted. */
    data class DayFacts(
        val date: LocalDate,
        val mood: String? = null,
        val weather: String? = null,
        val completed: List<String> = emptyList(),
        val habitsDone: Int? = null,
        val habitsDue: Int? = null,
        val habits: List<Pair<String, Boolean>> = emptyList(),
        val stocktake: String? = null,
    )

    /** The user message behind [DIARY_SYSTEM]: every fact in a line, nothing invented here. */
    fun daySummary(facts: DayFacts): String = buildString {
        append("日期：${facts.date}（周${"一二三四五六日"[facts.date.dayOfWeek.value - 1]}）")
        facts.mood?.let { append("\n心情：$it") }
        facts.weather?.let { append("\n天气：$it") }
        if (facts.completed.isNotEmpty()) {
            append("\n完成的待办：")
            facts.completed.forEach { append("\n· $it") }
        }
        if (facts.habitsDone != null && facts.habitsDue != null) {
            append("\n每日指标：${facts.habitsDone}/${facts.habitsDue}")
            if (facts.habits.isNotEmpty()) {
                append("（" + facts.habits.joinToString("、") { (label, done) -> if (done) "$label✓" else "$label✗" } + "）")
            }
        }
        facts.stocktake?.let { append("\n当天盘点：$it") }
        val hasFacts = facts.mood != null || facts.weather != null || facts.completed.isNotEmpty() ||
            facts.habitsDue != null || facts.stocktake != null
        if (!hasFacts) append("\n（这天没有留下其他记录）")
    }

    /**
     * The range the edit covers: the selection when it holds non-blank text, else the whole body.
     * The end is exclusive.
     */
    fun targetRange(text: String, selStart: Int, selEnd: Int): IntRange {
        val s = selStart.coerceIn(0, text.length)
        val e = selEnd.coerceIn(0, text.length)
        if (s < e && text.substring(s, e).isNotBlank()) return s until e
        return 0 until text.length
    }

    /**
     * A reply that came wrapped in a code fence is unwrapped; anything else is left as it is.
     * Only line breaks around it go — never spaces, or a paragraph's 全角缩进 would be eaten.
     */
    fun unwrap(reply: String): String {
        val t = reply.trim('\r', '\n')
        if (!t.startsWith("```")) return t
        val open = t.indexOf('\n')
        if (open < 0) return t
        val close = t.lastIndexOf("```")
        if (close <= open) return t
        return t.substring(open + 1, close).trim('\r', '\n')
    }

    /** The body with [output] spliced over [start, end), and the range the output occupies. */
    fun replace(text: String, start: Int, end: Int, output: String): NoteMarkdownEdit.Result {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        return NoteMarkdownEdit.Result(text.substring(0, s) + output + text.substring(e), s, s + output.length)
    }
}
