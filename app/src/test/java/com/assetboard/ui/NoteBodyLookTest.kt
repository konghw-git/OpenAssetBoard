package com.assetboard.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import com.assetboard.domain.notes.NoteMarkdown
import com.assetboard.ui.notes.NoteBodyLook
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live preview's title rule: a note promotes its first line to a title, a diary does not —
 * there the first line is just the first line.
 */
class NoteBodyLookTest {

    private fun look(doc: NoteMarkdown.Doc, promoteTitle: Boolean) = NoteBodyLook(
        doc = doc,
        activeLines = null,
        bodySize = 17.sp,
        titleSize = 26.sp,
        textColor = Color.Black,
        mutedColor = Color.Gray,
        faintColor = Color.LightGray,
        accent = Color.Blue,
        codeBackground = Color.Yellow,
        promoteTitle = promoteTitle,
    )

    private fun NoteBodyLook.titleSpans(text: String): Boolean {
        val out = filter(AnnotatedString(text)).text
        return out.spanStyles.any { it.item.fontSize == 26.sp }
    }

    @Test fun aNotePromotesItsFirstLineToATitle() {
        val doc = NoteMarkdown.parse("我的笔记\n第二行")
        assertTrue(look(doc, promoteTitle = true).titleSpans(doc.text))
    }

    @Test fun aDiaryKeepsItsFirstLineAParagraph() {
        val doc = NoteMarkdown.parse("早上跑了五公里\n第二行")
        assertFalse(look(doc, promoteTitle = false).titleSpans(doc.text))
    }

    @Test fun anExplicitHeadingIsStillAHeadingInTheDiary() {
        val doc = NoteMarkdown.parse("# 明确的标题\n正文")
        // The heading's own marker styles it; the title rule is not what does it.
        val out = look(doc, promoteTitle = false).filter(AnnotatedString(doc.text)).text
        assertTrue(out.spanStyles.any { it.item.fontSize == 24.sp })
    }

    @Test fun thePromoteFlagIsPartOfTheLooksIdentity() {
        // equals decides whether a text field re-filters; a look that differs only in the flag
        // must not compare equal to one with the old flag, or the rendering goes stale.
        val doc = NoteMarkdown.parse("我的笔记")
        assertNotEquals(look(doc, promoteTitle = true), look(doc, promoteTitle = false))
    }
}
