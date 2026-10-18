package com.assetboard.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// Dates are ISO LocalDate strings ("2026-10-19"), times are "HH:mm", instants are epoch millis.
// Rows in the trash carry both deleted_at and delete_batch; one delete operation shares one batch.

@Serializable
@Entity(tableName = "todo_lists")
data class TodoList(
    @PrimaryKey val id: String,
    val name: String,
    val notes: String = "",
    val deadline: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "delete_batch") val deleteBatch: String? = null,
)

@Serializable
@Entity(
    tableName = "todo_sections",
    foreignKeys = [
        // The one cross-batch purge: a section cannot outlive its list (see the trash rules).
        ForeignKey(entity = TodoList::class, parentColumns = ["id"], childColumns = ["list_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("list_id")],
)
data class TodoSection(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "list_id") val listId: String,
    val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "delete_batch") val deleteBatch: String? = null,
)

enum class TodoKind { ORDINARY, REPEAT_SOURCE, COMPLETION }

@Serializable
@Entity(
    tableName = "todos",
    foreignKeys = [
        ForeignKey(entity = TodoList::class, parentColumns = ["id"], childColumns = ["list_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = TodoSection::class, parentColumns = ["id"], childColumns = ["section_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = Todo::class, parentColumns = ["id"], childColumns = ["repeat_source_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index("list_id"), Index("section_id"), Index("plan_date"), Index("completed_at"),
        Index("delete_batch"), Index("repeat_source_id"),
    ],
)
data class Todo(
    @PrimaryKey val id: String,
    val kind: TodoKind = TodoKind.ORDINARY,
    val title: String,
    val notes: String = "",
    @ColumnInfo(name = "list_id") val listId: String? = null,
    @ColumnInfo(name = "section_id") val sectionId: String? = null,
    @ColumnInfo(name = "plan_date") val planDate: String? = null,
    @ColumnInfo(name = "remind_time") val remindTime: String? = null,
    @ColumnInfo(name = "strong_remind") val strongRemind: Boolean = false,
    val someday: Boolean = false,
    val deadline: String? = null,
    val important: Boolean = false,
    /** One-shot snoozed reminder; consumed when it fires. */
    @ColumnInfo(name = "snooze_at") val snoozeAt: Long? = null,
    /** The period whose planned reminder has been delivered; equal to [occurrenceCount] means "already reminded". */
    @ColumnInfo(name = "reminded_period") val remindedPeriod: Int? = null,
    /** JSON of [com.assetboard.domain.todo.RepeatRule]; repeat sources only. */
    @ColumnInfo(name = "repeat_rule") val repeatRule: String? = null,
    @ColumnInfo(name = "repeat_ended_at") val repeatEndedAt: Long? = null,
    /** Periods completed or skipped so far; doubles as the reminder period id. */
    @ColumnInfo(name = "occurrence_count") val occurrenceCount: Int = 0,
    /** Completion records only: the repeat source this period came from. */
    @ColumnInfo(name = "repeat_source_id") val repeatSourceId: String? = null,
    /** Completion records only: JSON of the source's state before this period advanced it. */
    @ColumnInfo(name = "undo_before") val undoBefore: String? = null,
    @ColumnInfo(name = "undo_revision") val undoRevision: Int? = null,
    /** Monotonic action token; bumped by every change that affects completion or reminders. */
    @ColumnInfo(name = "state_seq") val stateSeq: Long = 0,
    /** Bumped by every change; undo of a period requires it unchanged. */
    val revision: Int = 0,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "delete_batch") val deleteBatch: String? = null,
)

@Serializable
@Entity(
    tableName = "checklist_items",
    foreignKeys = [ForeignKey(entity = Todo::class, parentColumns = ["id"], childColumns = ["todo_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("todo_id")],
)
data class ChecklistItem(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "todo_id") val todoId: String,
    val text: String,
    val done: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

/** At most one undeleted entry per date; enforced by the repository, since Room has no partial index. */
@Serializable
@Entity(tableName = "diary_entries", indices = [Index("date"), Index("delete_batch")])
data class DiaryEntry(
    @PrimaryKey val id: String,
    val date: String,
    val body: String = "",
    @ColumnInfo(name = "body_rev") val bodyRev: Long = 0,
    @ColumnInfo(name = "weather_code") val weatherCode: Int? = null,
    @ColumnInfo(name = "temp_min") val tempMin: Double? = null,
    @ColumnInfo(name = "temp_max") val tempMax: Double? = null,
    val city: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "weather_at") val weatherAt: Long? = null,
    @ColumnInfo(name = "weather_rev") val weatherRev: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "delete_batch") val deleteBatch: String? = null,
)

/** Text that could not be saved into its entry; user data, backed up, removed only once handled. */
@Serializable
@Entity(
    tableName = "diary_drafts",
    foreignKeys = [ForeignKey(entity = DiaryEntry::class, parentColumns = ["id"], childColumns = ["diary_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("diary_id"), Index("date")],
)
data class DiaryDraft(
    @PrimaryKey val id: String,
    val date: String,
    @ColumnInfo(name = "diary_id") val diaryId: String? = null,
    val text: String,
    val reason: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

// ---------------------------------------------------------------- device-local, never backed up

@Entity(tableName = "holidays")
data class Holiday(
    @PrimaryKey val date: String,
    val name: String,
    @ColumnInfo(name = "is_off_day") val isOffDay: Boolean,
)

@Entity(tableName = "holiday_years")
data class HolidayYear(
    @PrimaryKey val year: Int,
    /** "builtin" or "online". */
    val source: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "app_meta")
data class AppMeta(
    @PrimaryKey val key: String,
    val value: String,
)
