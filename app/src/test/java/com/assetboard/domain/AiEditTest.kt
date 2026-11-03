package com.assetboard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiEditTest {

    @Test fun eachPromptDoesOnlyItsOwnJob() {
        assertTrue(AiEdit.FORMAT_SYSTEM.contains("只做排版"))
        assertTrue(AiEdit.FORMAT_SYSTEM.contains("不增删改写"))
        assertTrue(AiEdit.POLISH_SYSTEM.contains("只做润色"))
        assertTrue(AiEdit.POLISH_SYSTEM.contains("保持原意"))
        assertTrue(AiEdit.DIARY_SYSTEM.contains("第一人称"))
        assertTrue(AiEdit.DIARY_SYSTEM.contains("不编造"))
        assertEquals(AiEdit.FORMAT_SYSTEM, AiEdit.systemFor(AiAction.FORMAT))
        assertEquals(AiEdit.POLISH_SYSTEM, AiEdit.systemFor(AiAction.POLISH))
        assertEquals(AiEdit.DIARY_SYSTEM, AiEdit.systemFor(AiAction.GENERATE))
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
}
