package com.assetboard.data

import com.assetboard.domain.AppClock
import com.assetboard.domain.LedgerException
import com.assetboard.domain.ensure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

sealed interface BodySave {
    data class Saved(val entryId: String, val rev: Long, val created: Boolean) : BodySave
    data class Conflict(val reason: String) : BodySave
}

/** The two writes an editor session makes; a seam for the autosave timing tests. */
interface DiaryStore {
    suspend fun saveBody(date: LocalDate, entryId: String?, expectedRev: Long?, text: String): BodySave
    suspend fun saveDraft(date: LocalDate, entryId: String?, text: String, reason: String): String
    fun register(editor: DiaryEditorHandle)
    fun unregister(editor: DiaryEditorHandle)
}

/**
 * Every diary write goes through one serial queue (a fair mutex), so a save submitted earlier can
 * never land after one submitted later. Body writes are conditional on `body_rev`; weather writes
 * on `weather_rev`.
 */
class DiaryRepository(
    private val db: AppDatabase,
    private val gate: WriteGate,
    private val clock: () -> Long = AppClock::millis,
    /** Shared with the note editors; the restore commits all of them first. */
    val editors: EditorRegistry = EditorRegistry(),
) : DiaryStore {
    private val dao = db.diaryDao()
    private val queue = Mutex()

    /** Fault injection for tests: thrown inside the body or draft transaction. */
    @Volatile internal var failBodyWrites: Boolean = false
    @Volatile internal var failDraftWrites: Boolean = false

    override fun register(editor: DiaryEditorHandle) = editors.add(editor)
    override fun unregister(editor: DiaryEditorHandle) = editors.remove(editor)

    private suspend fun <T> serial(block: suspend () -> T): T = queue.withLock { gate.write { block() } }

    fun observe(date: LocalDate): Flow<DiaryEntry?> = dao.observeByDate(date.toString())
    suspend fun entryOn(date: LocalDate): DiaryEntry? = dao.liveByDate(date.toString())
    suspend fun entry(id: String): DiaryEntry? = dao.entry(id)
    fun observeDrafts() = dao.observeDrafts()
    fun observeDatesWithText(from: LocalDate, to: LocalDate) = dao.observeDatesWithText(from.toString(), to.toString())
    fun observeBetween(from: LocalDate, to: LocalDate) = dao.observeLiveBetween(from.toString(), to.toString())
    fun observeOnThisDay(date: LocalDate) = dao.observeOnThisDay(date.toString().substring(5), date.toString())

    suspend fun search(query: String): List<DiaryEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return dao.search(escaped)
    }

    /**
     * Saves [text] as the body of [date]'s entry. A session that has no entry yet adopts an empty
     * live entry or inserts one; an entry with text that appeared meanwhile is a conflict.
     */
    override suspend fun saveBody(date: LocalDate, entryId: String?, expectedRev: Long?, text: String): BodySave = serial {
        if (failBodyWrites) throw LedgerException("模拟写盘失败")
        val now = clock()
        if (entryId == null) {
            val existing = dao.liveByDate(date.toString())
            when {
                existing == null -> {
                    val e = DiaryEntry(newId(), date.toString(), text, bodyRev = 1, createdAt = now, updatedAt = now)
                    dao.insert(e)
                    BodySave.Saved(e.id, 1, created = true)
                }
                existing.body.isEmpty() && dao.updateBody(existing.id, text, existing.bodyRev, now) == 1 ->
                    BodySave.Saved(existing.id, existing.bodyRev + 1, created = false)
                else -> BodySave.Conflict("这一天已有另一篇日记")
            }
        } else {
            if (dao.updateBody(entryId, text, expectedRev ?: -1, now) == 1) {
                BodySave.Saved(entryId, (expectedRev ?: -1) + 1, created = false)
            } else {
                val e = dao.entry(entryId)
                BodySave.Conflict(
                    when {
                        e == null -> "这篇日记已被永久删除"
                        e.deletedAt != null -> "这篇日记已被删除"
                        else -> "这篇日记已在别处修改"
                    },
                )
            }
        }
    }

    override suspend fun saveDraft(date: LocalDate, entryId: String?, text: String, reason: String): String = serial {
        if (failDraftWrites) throw LedgerException("模拟写盘失败")
        val id = newId()
        dao.insertDraft(DiaryDraft(id, date.toString(), entryId?.takeIf { dao.entry(it) != null }, text, reason, clock()))
        id
    }

    suspend fun discardDraft(id: String) = serial { dao.deleteDraft(id) }

    /** The days of [from]..[to] with a mood, as date to [com.assetboard.domain.Mood] value. */
    fun observeMoods(from: LocalDate, to: LocalDate): Flow<Map<String, Int>> =
        dao.observeMoods(from.toString(), to.toString()).map { rows -> rows.associate { it.date to it.mood } }

    /**
     * Sets [date]'s mood, or clears it with null. A day without an entry gets an empty one to
     * hold it, which the first text written that day then takes over. Never touches the body.
     */
    suspend fun setMood(date: LocalDate, mood: Int?) = serial {
        ensure(mood == null || com.assetboard.domain.Mood.of(mood) != null) { "无效的心情" }
        val now = clock()
        val e = dao.liveByDate(date.toString())
        when {
            e != null -> dao.setMood(e.id, mood, now)
            mood != null -> dao.insert(DiaryEntry(newId(), date.toString(), "", createdAt = now, updatedAt = now, mood = mood))
        }
    }

    /** Moves the entry to the trash as its own batch; open sessions on it become stale. */
    suspend fun delete(entryId: String): String = serial {
        val e = dao.entry(entryId)?.takeIf { it.deletedAt == null } ?: throw LedgerException("日记不存在")
        val batch = newId()
        dao.update(e.copy(deletedAt = clock(), deleteBatch = batch, bodyRev = e.bodyRev + 1))
        batch
    }

    /** The live entry that blocks restoring [batch], if any. */
    suspend fun restoreConflict(batch: String): DiaryEntry? {
        val e = dao.inBatch(batch).firstOrNull { it.deletedAt != null } ?: return null
        return dao.liveByDate(e.date)
    }

    /**
     * Restores the diary entry of [batch]. When its date already has a live entry, [replace]
     * decides: true sends the current one to the trash as a new batch; false keeps it and restores nothing.
     */
    suspend fun restore(batch: String, replace: Boolean): Boolean = serial {
        val e = dao.inBatch(batch).firstOrNull { it.deletedAt != null } ?: return@serial false
        val current = dao.liveByDate(e.date)
        if (current != null) {
            if (!replace) return@serial false
            dao.update(current.copy(deletedAt = clock(), deleteBatch = newId(), bodyRev = current.bodyRev + 1))
        }
        dao.update(e.copy(deletedAt = null, deleteBatch = null, bodyRev = e.bodyRev + 1))
        ensure(dao.liveByDate(e.date)?.id == e.id) { "${e.date} 有多篇日记" }
        true
    }

    /** Starts a weather request: bumps and returns weather_rev; only a result carrying it may land. */
    suspend fun beginWeather(entryId: String): Long = serial {
        dao.bumpWeatherRev(entryId)
        dao.entry(entryId)!!.weatherRev
    }

    suspend fun writeWeather(entryId: String, rev: Long, w: WeatherResult): Boolean = serial {
        dao.writeWeather(entryId, rev, w.code, w.tempMin, w.tempMax, w.city, w.lat, w.lon, clock()) == 1
    }
}

data class WeatherResult(val code: Int?, val tempMin: Double?, val tempMax: Double?, val city: String?, val lat: Double?, val lon: Double?)
