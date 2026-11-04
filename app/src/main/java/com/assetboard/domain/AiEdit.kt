package com.assetboard.domain

import com.assetboard.domain.notes.NoteMarkdownEdit
import java.time.LocalDate

/** Which AI pass the editor asked for. */
enum class AiAction { FORMAT, POLISH, GENERATE }

/** What kind of document the pass runs on: the prompts differ in voice and house style. */
enum class DocKind { DIARY, NOTE }

/**
 * The AI editor helpers, kept as pure text: the prompts (one per [AiAction] × [DocKind], each told
 * to do only its own job and shown one example), the context framing around a selection, the
 * reply cleanup and validation, and the splice of a reply back into the body. The network side is
 * [com.assetboard.data.ai.AiService]; what a day contributes to the generated diary is [daySummary].
 *
 * An edit never lands below its floor: FORMAT starts from the local [DiaryFormat.tidy] on a diary,
 * POLISH from the target itself, and a reply that changes the wrong things is retried once and
 * then dropped in favour of the floor.
 */
object AiEdit {
    /** Where a target starts and ends inside an [editUser] message. */
    const val TARGET_OPEN = "【目标】"
    const val TARGET_CLOSE = "【目标结束】"

    private const val FORMAT_RULES =
        "只做排版，不做任何改写：\n" +
            "· 修正标点的全半角与误用，统一空格；\n" +
            "· 合并多余空行，理顺段落之间的空行；\n" +
            "· 整理 Markdown 结构（标题、列表、引用等）的格式；\n" +
            "· 不增删改写任何一个字词，不改变句子顺序和段落划分（空行整理除外）。"

    private const val POLISH_RULES =
        "只做润色，不做改写：\n" +
            "· 在保持原意、事实、数字、人称、语气和要点不变的前提下，让表达更通顺、自然；\n" +
            "· 可以调整用词和句式、修正语病，但不增删要点，不改变段落结构；\n" +
            "· 保留原文的 Markdown 格式（标题、列表、加粗等）。"

    val FORMAT_SYSTEM_NOTE =
        "你是 Markdown 笔记的排版助手。$FORMAT_RULES\n" +
            "例：「# 采购清单」下空三行、「* 牛奶」和「*  鸡蛋」两行，排版后是「# 采购清单」下空一行、「- 牛奶」和「- 鸡蛋」。\n" +
            "只输出排版后的文本，不要任何解释，不要用代码块包裹。"

    val FORMAT_SYSTEM_DIARY =
        "你是日记的排版助手。$FORMAT_RULES\n" +
            "日记的段落习惯是首行缩进两个全角空格（　　），列表、标题、引用行顶格；不要给正文加标题。\n" +
            "例：「早上跑了五公里.」空三行「下午改论文」，排版后是「　　早上跑了五公里。」空一行「　　下午改论文」。\n" +
            "只输出排版后的文本，不要任何解释，不要用代码块包裹。"

    val POLISH_SYSTEM_NOTE =
        "你是 Markdown 笔记的写作润色助手。$POLISH_RULES\n" +
            "例：「这个方案我觉得吧还行,但是有点贵了点」→「这个方案还行，只是价格偏高。」\n" +
            "只输出润色后的文本，不要任何解释，不要用代码块包裹。"

    val POLISH_SYSTEM_DIARY =
        "你是日记的写作润色助手。$POLISH_RULES\n" +
            "日记是第一人称随手记，口吻自然，不要写成公文或总结报告。\n" +
            "例：「今天跑了五公里,然后呢去吃了饭,感觉还行」→「今天跑了五公里，之后去吃了饭，感觉还行。」\n" +
            "只输出润色后的文本，不要任何解释，不要用代码块包裹。"

    const val DIARY_SYSTEM =
        "你是日记助手。用户会给你某一天的记录，你可以用工具查看用户的数据（待办、每日指标、心情、天气、" +
            "盘点、健康、饮食、账单、倒数日、周复盘、以往日记等；不包括笔记），想查什么就调用工具，" +
            "然后写一篇第一人称的中文日记：\n" +
            "· 只写有依据的内容，不编造没有发生的事；数据很少就写短一点；\n" +
            "· 语气自然，像本人随手记的，不要写成总结报告；\n" +
            "· 用 Markdown 适当分段，普通段落首行缩进两个全角空格。\n" +
            "只输出日记正文，不要任何解释，不要用代码块包裹。"

    fun systemFor(kind: AiAction, doc: DocKind): String = when (kind) {
        AiAction.FORMAT -> if (doc == DocKind.DIARY) FORMAT_SYSTEM_DIARY else FORMAT_SYSTEM_NOTE
        AiAction.POLISH -> if (doc == DocKind.DIARY) POLISH_SYSTEM_DIARY else POLISH_SYSTEM_NOTE
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

    /** The seed message behind [DIARY_SYSTEM]: every fact in a line, nothing invented here. */
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
     * The user message behind a FORMAT or POLISH pass: the text to change framed by its
     * surroundings (so the reply keeps the document's voice) and an explicit output contract.
     * Whatever sits between [TARGET_OPEN] and [TARGET_CLOSE] is the part that comes back.
     */
    fun editUser(target: String, before: String = "", after: String = ""): String = buildString {
        if (before.isNotBlank()) append("【前文】（只作参照，不要输出）\n${before.trim()}\n\n")
        append("$TARGET_OPEN\n$target\n$TARGET_CLOSE")
        if (after.isNotBlank()) append("\n\n【后文】（只作参照，不要输出）\n${after.trim()}")
        append("\n\n只输出【目标】【目标结束】之间文本改写后的结果，不要输出标记、前文或后文。")
    }

    /** The target section of an [editUser] message; the whole message when there is no frame. */
    fun targetOf(user: String): String {
        val open = user.indexOf("$TARGET_OPEN\n")
        val close = user.indexOf("\n$TARGET_CLOSE")
        if (open < 0 || close < 0 || close < open) return user
        return user.substring(open + TARGET_OPEN.length + 1, close)
    }

    /**
     * One FORMAT or POLISH pass on [target], with one corrective retry before the floor takes
     * over. [complete] is the chat endpoint; [before]/[after] frame the target as context.
     */
    suspend fun edit(
        complete: suspend (system: String, user: String) -> String,
        kind: AiAction,
        doc: DocKind,
        target: String,
        before: String = "",
        after: String = "",
    ): String {
        val whole = before.isBlank() && after.isBlank()
        // A diary's mechanics are deterministic; the model only improves on top of them.
        val floor = if (kind == AiAction.FORMAT && doc == DocKind.DIARY && whole) DiaryFormat.tidy(target) else target
        val system = systemFor(kind, doc)
        suspend fun attempt(note: String): String =
            stripFrame(unwrap(complete(system, editUser(floor, before, after) + note)))
        var out = attempt("")
        if (!valid(kind, floor, out)) {
            out = attempt("\n\n注意：上一次的输出不符合要求（${if (kind == AiAction.FORMAT) "改动了字词" else "改动或丢失了数字"}）。请严格按规则重新输出。")
            if (!valid(kind, floor, out)) return floor
        }
        // A diary's mechanics are deterministic: whatever the model wrote, tidy has the last word.
        return if (kind == AiAction.FORMAT && doc == DocKind.DIARY && whole) DiaryFormat.tidy(out) else out
    }

    /**
     * [edit] over [range] of [text], framed by a short window of the text on either side, so
     * both editors pass the same context without repeating how the window is cut.
     */
    suspend fun editText(
        complete: suspend (system: String, user: String) -> String,
        kind: AiAction,
        doc: DocKind,
        text: String,
        range: IntRange,
    ): String = edit(
        complete,
        kind,
        doc,
        target = text.substring(range),
        before = text.substring(0, range.first).takeLast(FRAME_CHARS),
        after = text.substring(range.last + 1).take(FRAME_CHARS),
    )

    /** How much of the text around the target goes along as context. */
    private const val FRAME_CHARS = 200

    /**
     * The reply reduced to the edited text: a model that wraps its answer in the [editUser] frame
     * has that frame taken off; any other reply comes back untouched.
     */
    fun stripFrame(reply: String): String {
        val t = reply.trim('\r', '\n')
        if (!t.startsWith(TARGET_OPEN) || !t.endsWith(TARGET_CLOSE)) return reply
        return t.substring(TARGET_OPEN.length, t.length - TARGET_CLOSE.length).trim('\r', '\n')
    }

    /**
     * FORMAT may move punctuation but never the words; POLISH may reword but never lose a number.
     * Framing the model echoed and a blank answer fail either way: neither belongs in the
     * document, and the floor takes over.
     */
    fun valid(kind: AiAction, before: String, after: String): Boolean {
        if (after.isBlank() && before.isNotBlank()) return false
        if (after.contains(TARGET_OPEN) || after.contains(TARGET_CLOSE) ||
            after.contains("【前文】") || after.contains("【后文】")
        ) return false
        return when (kind) {
            // Flattening paragraphs keeps every word but wrecks the page, so a format may split
            // or join lines yet never merge two paragraphs into one.
            AiAction.FORMAT -> words(before) == words(after) && paragraphs(after) >= paragraphs(before)
            AiAction.POLISH -> numbers(before) == numbers(after)
            AiAction.GENERATE -> true
        }
    }

    /** Blank-line-separated blocks. */
    fun paragraphs(text: String): Int {
        val t = text.trim()
        return if (t.isEmpty()) 0 else t.split(Regex("\\n\\s*\\n")).size
    }

    /** Han and latin letters and digits, width-folded: everything a rewrite must not touch for FORMAT. */
    fun words(text: String): String = buildString(text.length) {
        for (c in withoutMarkers(text)) {
            val w = foldWidth(c)
            if (w.isLetterOrDigit()) append(w)
        }
    }

    /** The numbers a polish must carry over unchanged. */
    fun numbers(text: String): List<String> = NUMBER.findAll(foldWidth(withoutMarkers(text))).map { it.value }.toList()

    /**
     * Ordered-list markers at line starts are structure, not words: fixing a list's numbering
     * must not read as a rewrite. Same shape as NoteMarkdown's numbered line, so digits the
     * parser sees as content (1.5 公里, a date) stay counted.
     */
    private fun withoutMarkers(text: String): String = NUMBERED_MARKER.replace(text, "")

    /** Full-width digits and Latin letters to their plain forms; everything else as it is. */
    private fun foldWidth(text: String): String = buildString(text.length) { for (c in text) append(foldWidth(c)) }

    private fun foldWidth(c: Char): Char =
        if (c in '０'..'９' || c in 'Ａ'..'Ｚ' || c in 'ａ'..'ｚ') (c.code - 0xFEE0).toChar() else c

    /**
     * The range the edit covers: the selection when it holds non-blank text, else the whole body.
     * A selection dragged backwards still counts. The end is exclusive.
     */
    fun targetRange(text: String, selStart: Int, selEnd: Int): IntRange {
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        if (a < b && text.substring(a, b).isNotBlank()) return a until b
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

    private val NUMBER = Regex("\\d+(?:\\.\\d+)?")

    /** A numbered list's marker at a line start: digits, the delimiter and its spacing. */
    private val NUMBERED_MARKER = Regex("""(?m)^[ \t]*\d{1,9}[.)][ \t]+""")
}
