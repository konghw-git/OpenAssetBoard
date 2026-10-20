package com.assetboard.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** About 40 characters on either side of the first hit, on one line. */
fun snippet(body: String, q: String): String {
    val i = body.indexOf(q, ignoreCase = true).takeIf { it >= 0 } ?: return body.take(120)
    val from = (i - 40).coerceAtLeast(0)
    val to = (i + q.length + 80).coerceAtMost(body.length)
    return (if (from > 0) "…" else "") + body.substring(from, to).replace('\n', ' ') + (if (to < body.length) "…" else "")
}

/** Every hit of [q] in [text], bold on a faint wash of [color]. */
fun highlight(text: String, q: String, color: Color): AnnotatedString = buildAnnotatedString {
    if (q.isBlank()) { append(text); return@buildAnnotatedString }
    var start = 0
    while (true) {
        val i = text.indexOf(q, start, ignoreCase = true)
        if (i < 0) { append(text.substring(start)); break }
        append(text.substring(start, i))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold, background = color.copy(alpha = 0.12f))) { append(text.substring(i, i + q.length)) }
        start = i + q.length
    }
}
