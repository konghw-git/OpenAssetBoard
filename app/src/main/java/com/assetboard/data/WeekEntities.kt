package com.assetboard.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/**
 * A week's review, hung on the diary's timeline by [weekStart] (that week's Monday). At most one
 * live review per week, which the repository keeps. Like a diary entry, only body writes and the
 * trash bump `body_rev`.
 */
@Serializable
@Entity(tableName = "weekly_reviews", indices = [Index("week_start"), Index("delete_batch")])
data class WeeklyReview(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "week_start") val weekStart: String,
    val body: String = "",
    @ColumnInfo(name = "body_rev") val bodyRev: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "delete_batch") val deleteBatch: String? = null,
)

/** Review text that could not be saved into its row; offered back on that week's page. */
@Serializable
@Entity(
    tableName = "weekly_drafts",
    foreignKeys = [ForeignKey(entity = WeeklyReview::class, parentColumns = ["id"], childColumns = ["review_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("review_id"), Index("week_start")],
)
data class WeeklyDraft(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "week_start") val weekStart: String,
    @ColumnInfo(name = "review_id") val reviewId: String? = null,
    val text: String,
    val reason: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Weeks start on Monday, in local time; titles use the ISO week-based year. */
object Weeks {
    fun start(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun days(start: LocalDate): List<LocalDate> = (0L..6L).map { start.plusDays(it) }

    /** "2026 年第 43 周". 2027-01-01 is in 2026's week 53, so never `date.year`. */
    fun title(start: LocalDate): String = "${start.get(IsoFields.WEEK_BASED_YEAR)} 年第 ${start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} 周"

    fun shortTitle(start: LocalDate): String = "第 ${start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} 周"

    /** "10月19日 – 25日", or across a month "9月28日 – 10月4日". */
    fun range(start: LocalDate): String {
        val end = start.plusDays(6)
        val tail = if (end.monthValue == start.monthValue) "${end.dayOfMonth}日" else "${end.monthValue}月${end.dayOfMonth}日"
        return "${start.monthValue}月${start.dayOfMonth}日 – $tail"
    }

    fun isMonday(s: String): Boolean = runCatching { LocalDate.parse(s).dayOfWeek == DayOfWeek.MONDAY }.getOrDefault(false)
}

@Dao
interface WeekDao {
    @Query("SELECT * FROM weekly_reviews WHERE week_start = :start AND deleted_at IS NULL LIMIT 1") fun observeByWeek(start: String): Flow<WeeklyReview?>
    @Query("SELECT * FROM weekly_reviews WHERE week_start = :start AND deleted_at IS NULL LIMIT 1") suspend fun liveByWeek(start: String): WeeklyReview?
    @Query("SELECT * FROM weekly_reviews WHERE id = :id") suspend fun review(id: String): WeeklyReview?
    /** Weeks in [from, to] whose live review has text, for the calendar's marks. */
    @Query("SELECT week_start FROM weekly_reviews WHERE deleted_at IS NULL AND body != '' AND week_start >= :from AND week_start <= :to")
    fun observeWeeksWithText(from: String, to: String): Flow<List<String>>
    @Query("SELECT * FROM weekly_drafts WHERE week_start = :start ORDER BY created_at") fun observeDrafts(start: String): Flow<List<WeeklyDraft>>
    @Query("SELECT * FROM weekly_drafts WHERE id = :id") suspend fun draft(id: String): WeeklyDraft?

    @Insert suspend fun insert(review: WeeklyReview)
    @Update suspend fun update(review: WeeklyReview)

    @Query("UPDATE weekly_reviews SET body = :body, body_rev = body_rev + 1, updated_at = :now WHERE id = :id AND deleted_at IS NULL AND body_rev = :expectedRev")
    suspend fun updateBody(id: String, body: String, expectedRev: Long, now: Long): Int

    @Insert suspend fun insertDraft(draft: WeeklyDraft)
    @Query("DELETE FROM weekly_drafts WHERE id = :id") suspend fun deleteDraft(id: String)

    // ------------------------------------------------------------ trash
    @Query("SELECT * FROM weekly_reviews WHERE deleted_at IS NOT NULL") fun observeDeleted(): Flow<List<WeeklyReview>>
    @Query("SELECT * FROM weekly_reviews WHERE delete_batch = :batch") suspend fun inBatch(batch: String): List<WeeklyReview>
    @Query("SELECT DISTINCT delete_batch FROM weekly_reviews WHERE deleted_at IS NOT NULL AND deleted_at < :before") suspend fun expiredBatches(before: Long): List<String>
    @Query("SELECT DISTINCT delete_batch FROM weekly_reviews WHERE deleted_at IS NOT NULL") suspend fun allBatches(): List<String>
    @Query("DELETE FROM weekly_reviews WHERE delete_batch = :batch AND deleted_at IS NOT NULL") suspend fun purgeBatch(batch: String)

    // ------------------------------------------------------------ backup
    @Query("SELECT * FROM weekly_reviews") suspend fun all(): List<WeeklyReview>
    @Query("SELECT * FROM weekly_drafts") suspend fun allDrafts(): List<WeeklyDraft>
    @Insert suspend fun insertAll(items: List<WeeklyReview>)
    @Insert suspend fun insertDrafts(items: List<WeeklyDraft>)
    @Query("DELETE FROM weekly_drafts") suspend fun clearDrafts()
    @Query("DELETE FROM weekly_reviews") suspend fun clear()
}
