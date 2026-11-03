package com.assetboard.ui.notes

import com.assetboard.data.Note

/**
 * The newest note titles behind each topic, precomputed in one pass so the deck can say what a
 * topic holds without opening it; the deck is small enough for three lines to be readable.
 */
data class TopicPreviews(
    /** The newest titles across every note: the "all" card. */
    val all: List<String>,
    /** The newest titles per topic id; the null key holds the unsorted notes. */
    val byTopic: Map<String?, List<String>>,
)

/** Sorts once by recency and takes [limit] titles per topic. */
fun buildTopicPreviews(notes: List<Note>, limit: Int = 3): TopicPreviews {
    val byNewest = notes.sortedByDescending { it.updatedAt }
    val byTopic = LinkedHashMap<String?, MutableList<String>>()
    for (n in byNewest) {
        val slot = byTopic.getOrPut(n.topicId) { mutableListOf() }
        if (slot.size < limit) slot.add(n.title)
    }
    return TopicPreviews(
        all = byNewest.take(limit).map { it.title },
        byTopic = byTopic,
    )
}
