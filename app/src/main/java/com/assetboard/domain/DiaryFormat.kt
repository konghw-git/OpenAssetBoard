package com.assetboard.domain

/**
 * 一键排版 for a plain-text diary: tidies how the text sits without changing what it says, and
 * gives the same result run twice. Line by line:
 *
 * - spaces at either end go (the ideographic one too), runs inside fold to one, and none stays
 *   between two Chinese characters or beside Chinese punctuation;
 * - full-width digits and letters become plain ones;
 * - after Chinese, half-width `, ? ! : ;` become `，？！：；`, a `.` that ends a sentence `。`, dots
 *   or a lone `…` an ellipsis `……`, and parentheses around Chinese `（）`; a time keeps `10:30`;
 *
 * then blank lines fold to one and none is left at either end, and every paragraph starts with
 * two ideographic spaces, except a list item, a line starting with a time, and a short first line
 * without closing punctuation that reads as a title. The text's own line breaks stay: nothing is
 * joined and no blank line is added.
 */
object DiaryFormat {
    const val INDENT = "　　"

    fun tidy(text: String): String {
        val kept = mutableListOf<String>()
        for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map(::clean)) {
            if (line.isEmpty() && (kept.isEmpty() || kept.last().isEmpty())) continue
            kept += line
        }
        while (kept.lastOrNull()?.isEmpty() == true) kept.removeAt(kept.lastIndex)
        val titled = kept.size > 1 && isTitle(kept.first())
        return kept.mapIndexed { i, l ->
            if (l.isEmpty() || (i == 0 && titled) || LIST.containsMatchIn(l)) l else INDENT + l
        }.joinToString("\n")
    }

    /** One line as [tidy] leaves it, before the indent. */
    internal fun clean(line: String): String {
        var s = line.trim { it.isWhitespace() || it == ' ' }
        s = buildString(s.length) { for (c in s) append(halfWidth(c)) }
        s = SPACES.replace(s, " ")
        s = punctuation(s)
        s = NEAR_PUNCT.replace(s) { it.groupValues[1] }
        s = BETWEEN_HAN.replace(s, "$1")
        return s
    }

    private fun punctuation(s: String): String {
        val out = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i]
            val prev = out.lastOrNull { it != ' ' }
            val next = (i + 1 until s.length).firstOrNull { s[it] != ' ' }?.let { s[it] }
            val afterChinese = isHan(prev) || (prev != null && prev in CHINESE_PUNCT)
            out.append(
                when (c) {
                    ',' -> if (afterChinese || isHan(next)) '，' else c
                    '?' -> if (afterChinese) '？' else c
                    '!' -> if (afterChinese) '！' else c
                    ':' -> if (isHan(prev)) '：' else c
                    ';' -> if (isHan(prev)) '；' else c
                    '.' -> if (isHan(prev) && (next == null || isHan(next))) '。' else c
                    else -> c
                },
            )
        }
        var r = ELLIPSIS.replace(out.toString(), "……")
        r = PARENS.replace(r) { "（${it.groupValues[1]}）" }
        return TIME.replace(r, "$1:$2")
    }

    /** Short, nothing closing it, and not a list item: a heading over what follows. */
    private fun isTitle(line: String): Boolean =
        line.length <= TITLE_MAX && line.last() !in CLOSING && !LIST.containsMatchIn(line)

    private fun isHan(c: Char?): Boolean = c != null && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    /** Full-width digits and Latin letters to their plain forms; everything else as it is. */
    private fun halfWidth(c: Char): Char =
        if (c in '０'..'９' || c in 'Ａ'..'Ｚ' || c in 'ａ'..'ｚ') (c.code - 0xFEE0).toChar() else c

    private const val TITLE_MAX = 16
    private const val CHINESE_PUNCT = "，。、！？：；（）「」《》【】"
    private const val CLOSING = "。！？…；，、.!?;,:："
    private val SPACES = Regex("[\\s　 ]+")
    private val NEAR_PUNCT = Regex(" ?([，。、！？：；（）「」《》【】]) ?")
    private val BETWEEN_HAN = Regex("(\\p{IsHan}) (?=\\p{IsHan})")
    private val ELLIPSIS = Regex("(?<=[\\p{IsHan}，])(?:\\.{2,}|。{2,}|…+)")
    private val PARENS = Regex("\\(([^()]*\\p{IsHan}[^()]*)\\)")
    private val TIME = Regex("(\\d)：(\\d)")

    /** Bullets, numbering (not a decimal), 一、 headings and a leading time: these sit flush. */
    private val LIST = Regex("^(?:[-*·•] |\\d{1,3}[.、)）](?!\\d)|[（(]\\d{1,3}[)）]|[①-⑳]|[一二三四五六七八九十]{1,3}、|\\d{1,2}:\\d{2})")
}
