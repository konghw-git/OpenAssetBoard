package com.assetboard.domain

import com.assetboard.domain.notes.NoteMarkdown
import com.assetboard.domain.notes.NoteMarkdown.Kind
import com.assetboard.domain.notes.NoteMarkdownEdit
import com.assetboard.domain.notes.NoteMarkdownEdit.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMarkdownTest {
    @Test fun linesAreParsedByTheirBlock() {
        val doc = NoteMarkdown.parse(
            """
            # 一级
            ## 二级
            普通一段
            - [ ] 买牛奶
            - [x] 跑步
            - 一个圆点
            1. 第一步
            > 一句引用
            ---
            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )
        assertEquals(Kind.HEADING, doc.lines[0].kind)
        assertEquals(1, doc.lines[0].level)
        assertEquals(2, doc.lines[1].level)
        assertEquals(Kind.PARAGRAPH, doc.lines[2].kind)
        assertEquals(Kind.CHECKLIST, doc.lines[3].kind)
        assertEquals(false, doc.lines[3].checked)
        assertEquals("买牛奶", doc.lines[3].content)
        assertEquals(true, doc.lines[4].checked)
        assertEquals(Kind.BULLET, doc.lines[5].kind)
        assertEquals(Kind.NUMBERED, doc.lines[6].kind)
        assertEquals(1, doc.lines[6].number)
        assertEquals(Kind.QUOTE, doc.lines[7].kind)
        assertEquals(Kind.DIVIDER, doc.lines[8].kind)
        assertEquals(Kind.FENCE, doc.lines[9].kind)
        assertEquals(Kind.CODE, doc.lines[10].kind)
        assertEquals(2, doc.headings.size)
        assertEquals(2, doc.tasks.size)
        assertEquals(1, doc.doneTasks)
    }

    @Test fun titleAndPreviewDropTheSyntax() {
        assertEquals("周末清单", NoteMarkdown.title("# 周末清单\n- [x] 买菜\n- [ ] 跑步"))
        assertEquals("周末清单", NoteMarkdown.title("\n\n## 周末清单"))
        assertEquals("☑ 买菜 ☐ 跑步", NoteMarkdown.preview("# 周末清单\n- [x] 买菜\n- [ ] 跑步"))
        assertEquals("一句引用", NoteMarkdown.preview("标题\n> 一句引用"))
        assertEquals("正文 第二行", NoteMarkdown.preview("标题\n\n正文\n第二行"))
        assertEquals("把 粗体 去掉", NoteMarkdown.stripInline("把 **粗体** 去掉"))
        assertEquals("看这个", NoteMarkdown.stripInline("看[这个](https://example.com)"))
        assertEquals("买牛奶", NoteMarkdown.title("- [ ] 买牛奶\n- [x] 跑步"))
        assertEquals("☑ 跑步", NoteMarkdown.preview("- [ ] 买牛奶\n- [x] 跑步"))
    }

    @Test fun inlineMarksFindEachPairOnce() {
        val text = "你好 **粗体** 和 *斜体* 与 `code` ~~删掉~~"
        val doc = NoteMarkdown.parse(text)
        val marks = NoteMarkdown.inlineMarks(text, doc.lines[0])
        assertEquals(
            listOf(NoteMarkdown.Inline.BOLD, NoteMarkdown.Inline.ITALIC, NoteMarkdown.Inline.CODE, NoteMarkdown.Inline.STRIKE),
            marks.map { it.kind },
        )
        assertEquals("粗体", text.substring(marks[0].contentStart, marks[0].contentEnd))
        assertEquals("斜体", text.substring(marks[1].contentStart, marks[1].contentEnd))
        assertEquals("code", text.substring(marks[2].contentStart, marks[2].contentEnd))
        assertEquals("删掉", text.substring(marks[3].contentStart, marks[3].contentEnd))
    }

    @Test fun inlineMarksKeepALinkTargetOutOfTheWay() {
        val text = "看 [文档](https://example.com) 吧"
        val marks = NoteMarkdown.inlineMarks(text, NoteMarkdown.parse(text).lines[0])
        assertEquals(1, marks.size)
        assertEquals(NoteMarkdown.Inline.LINK, marks[0].kind)
        assertEquals("文档", text.substring(marks[0].contentStart, marks[0].contentEnd))
        assertEquals("](https://example.com)", text.substring(marks[0].closeStart, marks[0].closeEnd))
    }

    @Test fun headingButtonTogglesAndKeepsTheCursorWithTheWords() {
        val text = "一行正文"
        val h = NoteMarkdownEdit.format(text, 4, 4, Format.H1)!!
        assertEquals("# 一行正文", h.text)
        assertEquals(6, h.start)
        val h2 = NoteMarkdownEdit.format(h.text, h.start, h.end, Format.H2)!!
        assertEquals("## 一行正文", h2.text)
        val off = NoteMarkdownEdit.format(h2.text, h2.start, h2.end, Format.H2)!!
        assertEquals("一行正文", off.text)
    }

    @Test fun checklistButtonTogglesAndConvertsOtherBlocks() {
        val check = NoteMarkdownEdit.format("买牛奶", 1, 1, Format.CHECKLIST)!!
        assertEquals("- [ ] 买牛奶", check.text)
        assertEquals(7, check.start)
        assertEquals("- [x] 买牛奶", NoteMarkdownEdit.setChecked(check.text, NoteMarkdown.parse(check.text).lines[0], true))
        val bullet = NoteMarkdownEdit.format(check.text, 0, 0, Format.BULLET)!!
        assertEquals("- 买牛奶", bullet.text)
        val off = NoteMarkdownEdit.format(bullet.text, 2, 2, Format.BULLET)!!
        assertEquals("买牛奶", off.text)
    }

    @Test fun multiLineChecklistMarksEveryTouchedLine() {
        val text = "甲\n乙\n丙"
        val r = NoteMarkdownEdit.format(text, 1, 3, Format.CHECKLIST)!!
        assertEquals("- [ ] 甲\n- [ ] 乙\n丙", r.text)
    }

    @Test fun boldWrapsAWordOrTheSelection() {
        val text = "你好世界"
        val word = NoteMarkdownEdit.format(text, 1, 1, Format.BOLD)!!
        assertEquals("你**好**世界", word.text)
        assertEquals("好", word.text.substring(word.start, word.end))
        val selected = NoteMarkdownEdit.format("你好世界", 1, 3, Format.ITALIC)!!
        assertEquals("你*好世*界", selected.text)
        val off = NoteMarkdownEdit.format(selected.text, selected.start, selected.end, Format.ITALIC)!!
        assertEquals("你好世界", off.text)
    }

    @Test fun emptySelectionInsertsAnEmptyPair() {
        val r = NoteMarkdownEdit.format(" ", 0, 0, Format.BOLD)!!
        assertEquals("**** ", r.text)
        assertEquals(2, r.start)
    }

    @Test fun inlineFormattingWrapsEverySelectedLine() {
        val text = "甲行\n乙行"
        val bold = NoteMarkdownEdit.format(text, 0, text.length, Format.BOLD)!!
        assertEquals("**甲行**\n**乙行**", bold.text)
        NoteMarkdown.parse(bold.text).lines.forEach { line ->
            assertEquals(listOf(NoteMarkdown.Inline.BOLD), NoteMarkdown.inlineMarks(bold.text, line).map { it.kind })
        }
        assertEquals("甲行", bold.text.substring(bold.start, bold.start + 2))
        val off = NoteMarkdownEdit.format(bold.text, bold.start, bold.end, Format.BOLD)!!
        assertEquals(text, off.text)
        assertEquals(0, off.start)
        assertEquals(text.length, off.end)
    }

    @Test fun italicRendersNextToChineseText() {
        val text = "前文*强调*后文"
        val marks = NoteMarkdown.inlineMarks(text, NoteMarkdown.parse(text).lines[0])
        assertEquals(listOf(NoteMarkdown.Inline.ITALIC), marks.map { it.kind })
        assertEquals("强调", text.substring(marks[0].contentStart, marks[0].contentEnd))
        assertEquals("前文强调后文", NoteMarkdown.stripInline(text))
        // An arithmetic expression is not emphasis, and Latin words stay as they were.
        assertTrue(NoteMarkdown.inlineMarks("2*3*4", NoteMarkdown.parse("2*3*4").lines[0]).isEmpty())
    }

    @Test fun dividerReplacesAnEmptyLineOrGoesBelowTheCurrentOne() {
        val empty = NoteMarkdownEdit.format("", 0, 0, Format.DIVIDER)!!
        assertEquals("---", empty.text)
        val after = NoteMarkdownEdit.format("正文", 2, 2, Format.DIVIDER)!!
        assertEquals("正文\n---\n", after.text)
        val off = NoteMarkdownEdit.format(after.text, after.start, after.end, Format.DIVIDER)!!
        assertEquals("正文", off.text)
    }

    @Test fun enterCarriesTheListMarker() {
        val check = "- [ ] 买牛奶"
        val next = NoteMarkdownEdit.continueList(check, check.length, check.length, check + "\n", check.length + 1, check.length + 1)!!
        assertEquals("- [ ] 买牛奶\n- [ ] ", next.text)
        assertEquals(next.text.length, next.start)

        val bullet = "- 一个点"
        val b = NoteMarkdownEdit.continueList(bullet, bullet.length, bullet.length, bullet + "\n", bullet.length + 1, bullet.length + 1)!!
        assertEquals("- 一个点\n- ", b.text)

        val numbered = "3. 第三步"
        val n = NoteMarkdownEdit.continueList(numbered, numbered.length, numbered.length, numbered + "\n", numbered.length + 1, numbered.length + 1)!!
        assertEquals("3. 第三步\n4. ", n.text)

        val quote = "> 一句"
        val q = NoteMarkdownEdit.continueList(quote, quote.length, quote.length, quote + "\n", quote.length + 1, quote.length + 1)!!
        assertEquals("> 一句\n> ", q.text)
    }

    @Test fun enterOnAnEmptyItemEndsTheList() {
        val text = "- [ ] "
        val r = NoteMarkdownEdit.continueList(text, text.length, text.length, text + "\n", text.length + 1, text.length + 1)!!
        assertEquals("\n", r.text)
        assertEquals(0, r.start)
    }

    @Test fun enterAfterAHeadingStartsAParagraph() {
        val text = "# 标题"
        assertNull(NoteMarkdownEdit.continueList(text, text.length, text.length, text + "\n", text.length + 1, text.length + 1))
    }

    @Test fun cursorInsideAHiddenCheckboxMovesToTheWords() {
        val text = "- [ ] 买牛奶"
        assertEquals(6, NoteMarkdownEdit.snapCursor(text, 2))
        assertEquals(6, NoteMarkdownEdit.snapCursor(text, 3))
        assertEquals(8, NoteMarkdownEdit.snapCursor(text, 8))
    }

    @Test fun backspaceAtAMarkerRemovesTheWholeMarker() {
        val check = "- [ ] 买牛奶"
        val withoutCheck = check.removeRange(5, 6)
        val r = NoteMarkdownEdit.backspaceAtMarker(check, 6, 6, withoutCheck, 5, 5)!!
        assertEquals("买牛奶", r.text)
        assertEquals(0, r.start)

        val heading = "# 标题"
        val withoutSpace = heading.removeRange(1, 2)
        val h = NoteMarkdownEdit.backspaceAtMarker(heading, 2, 2, withoutSpace, 1, 1)!!
        assertEquals("标题", h.text)

        // Anywhere else is an ordinary backspace.
        val middle = "买牛奶"
        assertNull(NoteMarkdownEdit.backspaceAtMarker(middle, 2, 2, middle.removeRange(1, 2), 1, 1))
    }

    @Test fun indentAndOutdentTouchEverySelectedLine() {
        val text = "甲\n乙"
        val indented = NoteMarkdownEdit.format(text, 0, 3, Format.INDENT)!!
        assertEquals("  甲\n  乙", indented.text)
        val outdented = NoteMarkdownEdit.format(indented.text, 0, indented.text.length, Format.OUTDENT)!!
        assertEquals(text, outdented.text)
    }

    @Test fun aBlankLineIsNotATitle() {
        assertNull(NoteMarkdown.parse(" \n ").titleLine)
        assertEquals("", NoteMarkdown.title(" \n "))
        assertTrue(NoteMarkdown.parse("#\n正文").headings.isEmpty())
    }

    @Test fun dividerNeedsTheSameCharacterThreeTimes() {
        assertEquals(Kind.DIVIDER, NoteMarkdown.parse("---").lines[0].kind)
        assertEquals(Kind.DIVIDER, NoteMarkdown.parse("- - -").lines[0].kind)
        assertEquals(Kind.BULLET, NoteMarkdown.parse("- 一项").lines[0].kind)
        assertEquals(Kind.PARAGRAPH, NoteMarkdown.parse("--").lines[0].kind)
    }
}
