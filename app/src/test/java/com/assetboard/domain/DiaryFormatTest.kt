package com.assetboard.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DiaryFormatTest {
    private val i = DiaryFormat.INDENT

    @Test fun indentsEachParagraphAndFoldsBlankLines() {
        val typed = "\n\n  今天去爬山了，风很大。\n\n\n\n　回来的路上买了橘子。  \n\n"
        assertEquals("${i}今天去爬山了，风很大。\n\n${i}回来的路上买了橘子。", DiaryFormat.tidy(typed))
    }

    @Test fun keepsTheWritersOwnLineBreaks() {
        assertEquals("${i}第一句。\n${i}第二句。", DiaryFormat.tidy("第一句。\n第二句。"))
    }

    @Test fun aShortFirstLineWithoutClosingPunctuationIsATitle() {
        assertEquals("周末爬山\n${i}风很大。", DiaryFormat.tidy("周末爬山\n风很大。"))
        // A sentence that closes is a paragraph, and a lone line is never a title.
        assertEquals("${i}风很大。\n${i}回家了。", DiaryFormat.tidy("风很大。\n回家了。"))
        assertEquals("${i}周末爬山", DiaryFormat.tidy("周末爬山"))
    }

    @Test fun listItemsAndTimesSitFlush() {
        val typed = "今天做了几件事：\n- 交周报\n1. 买菜\n2、做饭\n一、复盘\n14:30 开会\n体重减了1.5公斤。"
        assertEquals(
            "${i}今天做了几件事：\n- 交周报\n1. 买菜\n2、做饭\n一、复盘\n14:30 开会\n${i}体重减了1.5公斤。",
            DiaryFormat.tidy(typed),
        )
    }

    @Test fun halfWidthPunctuationAfterChineseBecomesFullWidth() {
        assertEquals("${i}今天很累，但是很开心！明天呢？", DiaryFormat.tidy("今天很累,但是很开心!明天呢?"))
        assertEquals("${i}时间：上午。地点：公园。", DiaryFormat.tidy("时间:上午.地点:公园."))
        assertEquals("${i}我想……算了。", DiaryFormat.tidy("我想...算了。"))
        assertEquals("${i}我想……", DiaryFormat.tidy("我想…"))
        assertEquals("${i}去了公园（人很多）。", DiaryFormat.tidy("去了公园(人很多)。"))
    }

    @Test fun latinNumbersAndTimesKeepTheirPunctuation() {
        assertEquals("${i}读了 Clean Code, chapter 3.", DiaryFormat.tidy("读了 Clean Code, chapter 3."))
        // A line starting with a time is a log line: flush, like a list item.
        assertEquals("10:30 出门，花了3.5元。", DiaryFormat.tidy("10：30 出门,花了3.5元。"))
        assertEquals("${i}跑了10公里", DiaryFormat.tidy("跑了１０公里"))
    }

    @Test fun spacesGoBetweenChineseAndBesidePunctuation() {
        assertEquals("${i}今天天气很好，适合出门。", DiaryFormat.tidy("今天  天气 很好 ， 适合　出门 。"))
        assertEquals("${i}用 Kotlin 写代码", DiaryFormat.tidy("用 Kotlin  写代码"))
    }

    @Test fun runningItTwiceChangesNothingMore() {
        val samples = listOf(
            "周末\n\n今天很累,但是很开心!\n- 交周报\n我想...算了",
            "  　　已经缩进过的段落。\n\n\n第二段(补充)。",
            "",
            "\n\n",
        )
        for (s in samples) {
            val once = DiaryFormat.tidy(s)
            assertEquals(once, DiaryFormat.tidy(once))
        }
        assertEquals("", DiaryFormat.tidy("\n \n　\n"))
    }
}
