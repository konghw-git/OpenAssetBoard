package com.assetboard.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiEditTest {

    @Test fun eachPromptDoesOnlyItsOwnJob() {
        assertTrue(AiEdit.FORMAT_SYSTEM_NOTE.contains("只做排版"))
        assertTrue(AiEdit.FORMAT_SYSTEM_NOTE.contains("不增删改写"))
        assertTrue(AiEdit.FORMAT_SYSTEM_DIARY.contains("只做排版"))
        assertTrue(AiEdit.FORMAT_SYSTEM_DIARY.contains("缩进两个全角空格"))
        assertTrue(AiEdit.POLISH_SYSTEM_NOTE.contains("只做润色"))
        assertTrue(AiEdit.POLISH_SYSTEM_NOTE.contains("保持原意"))
        assertTrue(AiEdit.POLISH_SYSTEM_DIARY.contains("第一人称"))
        assertTrue(AiEdit.DIARY_SYSTEM.contains("第一人称"))
        assertTrue(AiEdit.DIARY_SYSTEM.contains("不编造"))
        assertEquals(AiEdit.FORMAT_SYSTEM_NOTE, AiEdit.systemFor(AiAction.FORMAT, DocKind.NOTE))
        assertEquals(AiEdit.FORMAT_SYSTEM_DIARY, AiEdit.systemFor(AiAction.FORMAT, DocKind.DIARY))
        assertEquals(AiEdit.POLISH_SYSTEM_NOTE, AiEdit.systemFor(AiAction.POLISH, DocKind.NOTE))
        assertEquals(AiEdit.POLISH_SYSTEM_DIARY, AiEdit.systemFor(AiAction.POLISH, DocKind.DIARY))
        assertEquals(AiEdit.DIARY_SYSTEM, AiEdit.systemFor(AiAction.GENERATE, DocKind.DIARY))
    }

    @Test fun aSelectionIsTheTargetAndNothingElse() {
        val text = "标题\n\n第一段，写得不错。\n\n第二段。"
        val s = text.indexOf("第一段")
        val e = s + "第一段，写得不错。".length
        assertEquals(s until e, AiEdit.targetRange(text, s, e))
    }

    @Test fun noSelectionMeansTheWholeBody() {
        val text = "整篇\n\n都是目标。"
        assertEquals(0 until text.length, AiEdit.targetRange(text, 2, 2))
        assertEquals(0 until text.length, AiEdit.targetRange(text, -1, -1))
    }

    @Test fun aBlankSelectionAlsoMeansTheWholeBody() {
        val text = "甲\n\n乙"
        assertEquals(0 until text.length, AiEdit.targetRange(text, 1, 3))
    }

    @Test fun aReversedSelectionIsStillTheTarget() {
        // Dragging a handle backwards leaves TextRange with start after end; the pass must still
        // see the dragged-over text as the target rather than fall back to the whole body.
        val text = "第一段，写得不错。\n\n第二段。"
        val s = text.indexOf("第一段")
        val e = s + "第一段，写得不错。".length
        assertEquals(s until e, AiEdit.targetRange(text, e, s))
    }

    @Test fun aRangeBeyondTheTextStaysInsideIt() {
        val text = "短"
        assertEquals(0 until 1, AiEdit.targetRange(text, -5, 99))
    }

    @Test fun aFencedReplyIsUnwrapped() {
        assertEquals("排好了。", AiEdit.unwrap("```markdown\n排好了。\n```"))
        assertEquals("排好了。", AiEdit.unwrap("```\n排好了。\n```"))
        assertEquals("排好了。", AiEdit.unwrap("排好了。"))
        assertEquals("```\n不是围栏", AiEdit.unwrap("```\n不是围栏"))
    }

    @Test fun theIndentSurvivesTheUnwrap() {
        // trim() eats 全角空格 (whitespace!); a tidied paragraph's indent must not go with it.
        assertEquals("　　早上好。\n\n　　晚上好。", AiEdit.unwrap("　　早上好。\n\n　　晚上好。"))
        assertEquals("　　早上好。", AiEdit.unwrap("\n　　早上好。\n"))
        val tidied = DiaryFormat.tidy("早上跑了五公里.\n\n\n下午改论文")
        assertEquals(tidied, AiEdit.unwrap(tidied))
    }

    @Test fun replaceSplicesTheOutputAndPointsAtIt() {
        val text = "标题\n\n正文一段。"
        val s = text.indexOf("正文一段")
        val out = AiEdit.replace(text, s, text.length, "新的正文。")
        assertEquals("标题\n\n新的正文。", out.text)
        assertEquals(s, out.start)
        assertEquals(s + "新的正文。".length, out.end)
    }

    @Test fun replaceClampsARangeThatMoved() {
        val out = AiEdit.replace("短", -4, 99, "长很多的正文")
        assertEquals("长很多的正文", out.text)
    }

    @Test fun theDaySummaryCarriesEveryFact() {
        val facts = AiEdit.DayFacts(
            date = LocalDate.parse("2026-10-25"),
            mood = "平静",
            weather = "晴，15~23°C",
            completed = listOf("跑步五公里", "改论文"),
            habitsDone = 3,
            habitsDue = 5,
            habits = listOf("跑步" to true, "冥想" to false),
            stocktake = "¥123,456（较上次 +¥1,234）",
        )
        val s = AiEdit.daySummary(facts)
        assertTrue(s.contains("2026-10-25"))
        assertTrue(s.contains("周日"))
        assertTrue(s.contains("心情：平静"))
        assertTrue(s.contains("天气：晴，15~23°C"))
        assertTrue(s.contains("跑步五公里"))
        assertTrue(s.contains("3/5"))
        assertTrue(s.contains("跑步✓"))
        assertTrue(s.contains("冥想✗"))
        assertTrue(s.contains("¥123,456（较上次 +¥1,234）"))
    }

    @Test fun anEmptyDaySaysSoRatherThanInventing() {
        val s = AiEdit.daySummary(AiEdit.DayFacts(date = LocalDate.parse("2026-10-25")))
        assertTrue(s.contains("没有留下其他记录"))
    }

    @Test fun theTargetIsFramedAndRecoverable() {
        val user = AiEdit.editUser("要改的部分", before = "前文在这", after = "后文在这")
        assertTrue(user.contains("前文在这"))
        assertTrue(user.contains("后文在这"))
        assertEquals("要改的部分", AiEdit.targetOf(user))
    }

    @Test fun aWholeBodyEditHasNoFrameToRecover() {
        val user = AiEdit.editUser("全文")
        assertEquals("全文", AiEdit.targetOf(user))
    }

    @Test fun formatNeverRewritesAndPolishNeverLosesNumbers() {
        assertTrue(AiEdit.valid(AiAction.FORMAT, "早上跑了五公里.", "　　早上跑了五公里。"))
        assertFalse(AiEdit.valid(AiAction.FORMAT, "早上跑了五公里", "早上跑了 5 公里"))
        assertTrue(AiEdit.valid(AiAction.POLISH, "配速 5 分 30 秒", "配速 5 分 30 秒，还不错"))
        assertFalse(AiEdit.valid(AiAction.POLISH, "配速 5 分 30 秒", "配速还行"))
    }

    @Test fun renumberingAListIsStillJustFormatting() {
        // The marker digits are structure, not words: fixing a broken list must not read as a rewrite.
        assertTrue(AiEdit.valid(AiAction.FORMAT, "1. 牛奶\n1. 鸡蛋", "1. 牛奶\n2. 鸡蛋"))
        assertTrue(AiEdit.valid(AiAction.POLISH, "1. 牛奶\n1. 鸡蛋", "1. 牛奶\n2. 鸡蛋"))
        // Digits in the content are still content: 1.5 must not read as a marker.
        assertFalse(AiEdit.valid(AiAction.FORMAT, "1.5 公里", "1.6 公里"))
    }

    @Test fun anEchoedFrameIsTakenOffTheReply() = runBlocking {
        // The prompt shows the model the 【目标】 markers; when it echoes them back, the frame
        // must be peeled off rather than spliced into the document.
        val out = AiEdit.edit(
            complete = { _, _ -> "【目标】\n润色后的文本。\n【目标结束】" },
            kind = AiAction.POLISH,
            doc = DocKind.NOTE,
            target = "还行",
        )
        assertEquals("润色后的文本。", out)
    }

    @Test fun framingJunkIsDroppedRatherThanPasted() = runBlocking {
        // Half a frame or an explanation around the answer: numbers still match, so the narrow
        // number check alone would let it through — the floor has to take over instead.
        val out = AiEdit.edit(
            complete = { _, _ -> "好的，已润色：\n【目标】\n还行" },
            kind = AiAction.POLISH,
            doc = DocKind.NOTE,
            target = "还行",
        )
        assertEquals("还行", out)
    }
}
