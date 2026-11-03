package com.assetboard.ui

import com.assetboard.data.Note
import com.assetboard.ui.notes.buildTopicPreviews
import org.junit.Assert.assertEquals
import org.junit.Test

/** The topic previews must agree with what the inline "newest three per topic" code built. */
class NoteDeckCardsTest {
    private var n = 0

    private fun note(body: String, topic: String?, updatedAt: Long) =
        Note(id = "n${n++}", topicId = topic, body = body, createdAt = 0, updatedAt = updatedAt)

    @Test fun theAllPreviewIsTheNewestThreeNotes() {
        val previews = buildTopicPreviews(
            listOf(
                note("最旧", topic = "a", updatedAt = 1),
                note("最新", topic = "b", updatedAt = 3),
                note("中间", topic = null, updatedAt = 2),
                note("太旧", topic = "a", updatedAt = 0),
            ),
        )
        assertEquals(listOf("最新", "中间", "最旧"), previews.all)
    }

    @Test fun eachTopicPreviewsItsOwnNewestNotes() {
        val previews = buildTopicPreviews(
            listOf(
                note("a2", topic = "a", updatedAt = 2),
                note("a1", topic = "a", updatedAt = 1),
                note("a3", topic = "a", updatedAt = 3),
                note("b1", topic = "b", updatedAt = 1),
            ),
        )
        assertEquals(listOf("a3", "a2", "a1"), previews.byTopic["a"])
        assertEquals(listOf("b1"), previews.byTopic["b"])
    }

    @Test fun unsortedNotesHangUnderTheNullKey() {
        val previews = buildTopicPreviews(
            listOf(
                note("没主题", topic = null, updatedAt = 1),
                note("有主题", topic = "a", updatedAt = 2),
            ),
        )
        assertEquals(listOf("没主题"), previews.byTopic[null])
        assertEquals(listOf("有主题"), previews.byTopic["a"])
    }

    @Test fun titlesComeFromTheFirstLineWithoutItsMarker() {
        val previews = buildTopicPreviews(listOf(note("# 周报\n\n正文", topic = "a", updatedAt = 1)))
        assertEquals(listOf("周报"), previews.byTopic["a"])
    }

    @Test fun atMostThreePerTopic() {
        val previews = buildTopicPreviews(
            (1L..5L).map { note("第$it", topic = "a", updatedAt = it) },
        )
        assertEquals(listOf("第5", "第4", "第3"), previews.byTopic["a"])
    }

    @Test fun anEmptyVaultPreviewsNothing() {
        val previews = buildTopicPreviews(emptyList())
        assertEquals(emptyList<String>(), previews.all)
        assertEquals(emptyMap<String?, List<String>>(), previews.byTopic)
    }
}
