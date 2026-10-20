package com.assetboard.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** One level of topics; a note without one is "未归类", which is not a row. */
@Serializable
@Entity(tableName = "note_topics")
data class NoteTopic(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * A long-lived note. [position] is its place in the hand-set order, smallest first; new notes
 * go to the top. Only body writes (and moving to or from the trash) bump `body_rev`, so
 * pinning or changing the topic never makes an open editor stale; `updated_at` follows the body.
 */
@Serializable
@Entity(
    tableName = "notes",
    foreignKeys = [ForeignKey(entity = NoteTopic::class, parentColumns = ["id"], childColumns = ["topic_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("topic_id"), Index("delete_batch")],
)
data class Note(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "topic_id") val topicId: String? = null,
    val body: String = "",
    @ColumnInfo(name = "body_rev") val bodyRev: Long = 0,
    val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val position: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "delete_batch") val deleteBatch: String? = null,
) {
    /** The first non-blank line; the one place a note's title is defined. */
    val title: String get() = NoteText.title(body)
}

/** Where a note's text came from: a diary day for now, a week later on. Written idempotently. */
@Serializable
@Entity(
    tableName = "note_sources",
    primaryKeys = ["note_id", "kind", "ref"],
    foreignKeys = [ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["note_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("kind", "ref")],
)
data class NoteSource(
    @ColumnInfo(name = "note_id") val noteId: String,
    val kind: String,
    val ref: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Text that could not be saved into its note; it may point at no note at all. */
@Serializable
@Entity(
    tableName = "note_drafts",
    foreignKeys = [ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["note_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("note_id")],
)
data class NoteDraft(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String? = null,
    val text: String,
    val reason: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** A source before it belongs to a row: what an append carries until its text lands. */
data class SourceRef(val kind: String, val ref: String) {
    companion object {
        const val DAY = "DAY"
        fun day(date: java.time.LocalDate) = SourceRef(DAY, date.toString())
    }
}

object NoteText {
    const val UNTITLED = "无标题"

    fun title(body: String): String = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: UNTITLED

    /** What follows the title, folded into one line for a list row. */
    fun preview(body: String): String {
        val lines = body.lineSequence().map { it.trim() }.dropWhile { it.isEmpty() }.drop(1)
        return lines.filter { it.isNotEmpty() }.joinToString(" ").take(160)
    }

    /** The block an append adds, dated like a diary draft that goes back into its entry. */
    fun block(date: String, text: String) = "— $date\n$text"

    fun join(body: String, tail: String) = if (body.isBlank()) tail else body.trimEnd() + "\n\n" + tail

    /** Paragraphs of a diary entry: split at blank lines when it has any, otherwise at each line. */
    fun paragraphs(body: String): List<String> {
        val parts = if (Regex("""\n[ \t]*\n""").containsMatchIn(body)) body.split(Regex("""\n[ \t]*\n""")) else body.split('\n')
        return parts.map { it.trim('\n', ' ', '\t') }.filter { it.isNotBlank() }
    }

    /** `#标签` words: a hash followed by letters, digits or underscores, not inside a word. */
    val TAG = Regex("""(?<![\p{L}\p{N}_#])#[\p{L}\p{N}_]+""")
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE deleted_at IS NULL ORDER BY pinned DESC, position, updated_at DESC") fun observeLive(): Flow<List<Note>>
    @Query("SELECT * FROM notes WHERE id = :id") fun observeNote(id: String): Flow<Note?>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun note(id: String): Note?
    @Query("SELECT * FROM note_topics ORDER BY sort_order") fun observeTopics(): Flow<List<NoteTopic>>
    @Query("SELECT * FROM note_topics ORDER BY sort_order") suspend fun topics(): List<NoteTopic>
    @Query("SELECT * FROM note_topics WHERE id = :id") suspend fun topic(id: String): NoteTopic?
    @Query("SELECT * FROM note_sources WHERE note_id = :noteId ORDER BY ref DESC") fun observeSources(noteId: String): Flow<List<NoteSource>>
    @Query("SELECT * FROM note_drafts ORDER BY created_at DESC") fun observeDrafts(): Flow<List<NoteDraft>>
    @Query("SELECT * FROM note_drafts WHERE id = :id") suspend fun draft(id: String): NoteDraft?

    @Query(
        "SELECT notes.* FROM note_sources JOIN notes ON notes.id = note_sources.note_id " +
            "WHERE note_sources.kind = :kind AND note_sources.ref = :ref AND notes.deleted_at IS NULL ORDER BY notes.updated_at DESC",
    )
    fun observeFromSource(kind: String, ref: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE deleted_at IS NULL AND body LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY pinned DESC, updated_at DESC LIMIT 200")
    suspend fun search(q: String): List<Note>

    @Query("SELECT COUNT(*) FROM notes WHERE topic_id = :topicId AND deleted_at IS NULL") suspend fun liveCountIn(topicId: String): Int
    @Query("SELECT COALESCE(MIN(position), 0) FROM notes") suspend fun minPosition(): Long
    @Query("UPDATE notes SET position = :position WHERE id = :id") suspend fun setPosition(id: String, position: Long)

    @Insert suspend fun insert(note: Note)
    @Update suspend fun update(note: Note)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSource(source: NoteSource)
    @Insert suspend fun insertTopic(topic: NoteTopic)
    @Update suspend fun updateTopics(topics: List<NoteTopic>)
    @Query("UPDATE note_topics SET name = :name WHERE id = :id") suspend fun renameTopic(id: String, name: String)
    @Query("DELETE FROM note_topics WHERE id = :id") suspend fun deleteTopic(id: String)

    /** The versioned body write: affects 0 rows when the session's view is stale. */
    @Query("UPDATE notes SET body = :body, body_rev = body_rev + 1, updated_at = :now WHERE id = :id AND deleted_at IS NULL AND body_rev = :expectedRev")
    suspend fun updateBody(id: String, body: String, expectedRev: Long, now: Long): Int

    // Columns that leave body_rev and updated_at alone, so an open editor never goes stale over them.
    @Query("UPDATE notes SET pinned = :pinned WHERE id = :id") suspend fun setPinned(id: String, pinned: Boolean): Int
    @Query("UPDATE notes SET topic_id = :topicId WHERE id = :id") suspend fun setTopic(id: String, topicId: String?): Int
    @Query("UPDATE notes SET reviewed_at = :at WHERE id = :id") suspend fun setReviewed(id: String, at: Long?): Int

    @Insert suspend fun insertDraft(draft: NoteDraft)
    @Query("DELETE FROM note_drafts WHERE id = :id") suspend fun deleteDraft(id: String)

    // ------------------------------------------------------------ trash
    @Query("SELECT * FROM notes WHERE deleted_at IS NOT NULL") fun observeDeleted(): Flow<List<Note>>
    @Query("SELECT * FROM notes WHERE delete_batch = :batch") suspend fun inBatch(batch: String): List<Note>
    @Query("SELECT DISTINCT delete_batch FROM notes WHERE deleted_at IS NOT NULL AND deleted_at < :before") suspend fun expiredBatches(before: Long): List<String>
    @Query("SELECT DISTINCT delete_batch FROM notes WHERE deleted_at IS NOT NULL") suspend fun allBatches(): List<String>
    @Query("DELETE FROM notes WHERE delete_batch = :batch AND deleted_at IS NOT NULL") suspend fun purgeBatch(batch: String)

    // ------------------------------------------------------------ backup
    @Query("SELECT * FROM note_topics") suspend fun allTopics(): List<NoteTopic>
    @Query("SELECT * FROM notes") suspend fun all(): List<Note>
    @Query("SELECT * FROM note_sources") suspend fun allSources(): List<NoteSource>
    @Query("SELECT * FROM note_drafts") suspend fun allDrafts(): List<NoteDraft>
    @Insert suspend fun insertTopics(items: List<NoteTopic>)
    @Insert suspend fun insertAll(items: List<Note>)
    @Insert suspend fun insertSources(items: List<NoteSource>)
    @Insert suspend fun insertDrafts(items: List<NoteDraft>)
    // Children first; the inserts go the other way round, since foreign keys are checked per row.
    @Query("DELETE FROM note_drafts") suspend fun clearDrafts()
    @Query("DELETE FROM note_sources") suspend fun clearSources()
    @Query("DELETE FROM notes") suspend fun clear()
    @Query("DELETE FROM note_topics") suspend fun clearTopics()
}
