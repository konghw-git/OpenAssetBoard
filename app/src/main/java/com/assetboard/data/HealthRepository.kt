package com.assetboard.data

import com.assetboard.domain.AppClock
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import com.assetboard.domain.LedgerException
import com.assetboard.domain.ensure
import com.assetboard.domain.health.Health
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

@Dao
interface HealthDao {
    @Query("SELECT * FROM health_profile WHERE id = 'self'") fun observeProfile(): Flow<HealthProfile?>
    @Query("SELECT * FROM body_measurements ORDER BY date") fun observeAll(): Flow<List<BodyMeasurement>>
    @Query("SELECT * FROM health_profile WHERE id = 'self'") suspend fun profile(): HealthProfile?
    @Query("SELECT * FROM body_measurements WHERE date = :date") suspend fun onDate(date: String): BodyMeasurement?
    @Query("SELECT * FROM body_measurements WHERE id = :id") suspend fun measurement(id: String): BodyMeasurement?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProfile(profile: HealthProfile)
    @Insert suspend fun insert(m: BodyMeasurement)
    @Update suspend fun update(m: BodyMeasurement)
    @Query("DELETE FROM body_measurements WHERE id = :id") suspend fun delete(id: String)

    // ------------------------------------------------------------ backup
    @Query("SELECT * FROM health_profile") suspend fun allProfiles(): List<HealthProfile>
    @Query("SELECT * FROM body_measurements ORDER BY date") suspend fun all(): List<BodyMeasurement>
    @Insert suspend fun insertProfiles(items: List<HealthProfile>)
    @Insert suspend fun insertAll(items: List<BodyMeasurement>)
    @Query("DELETE FROM health_profile") suspend fun clearProfile()
    @Query("DELETE FROM body_measurements") suspend fun clear()
}

/** What the edit sheet hands in; the repository decides whether it is new, an edit or an overwrite. */
data class MeasurementDraft(
    /** The record being edited; null for a new one. */
    val id: String?,
    val date: LocalDate,
    val weightG: Long,
    val bodyFatBp: Int?,
    val note: String?,
)

/** Saving would replace the record already on [date]; the sheet asks first, then saves with overwrite. */
class SameDayExists(val date: LocalDate) : LedgerException("${date.monthValue}月${date.dayOfMonth}日已经有一条记录")

/**
 * The health profile and the daily weigh-ins. One record a day: a save onto a day that already
 * has one fails with [SameDayExists] unless the caller confirmed the overwrite, and an overwrite
 * keeps that day's row id (the date index is unique, so a new id would collide with it).
 */
class HealthRepository(
    private val db: AppDatabase,
    private val gate: WriteGate,
    private val clock: () -> Long = AppClock::millis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = db.healthDao()

    val profile: Flow<HealthProfile?> = dao.observeProfile()
    val measurements: Flow<List<BodyMeasurement>> = dao.observeAll()

    fun today(): LocalDate = com.assetboard.domain.todo.TodoViews.localDate(clock(), zone())

    suspend fun onDate(date: LocalDate): BodyMeasurement? = dao.onDate(date.toString())

    suspend fun saveProfile(sex: Sex, birthDate: LocalDate, heightMm: Int, targetWeightG: Long?) = gate.write {
        checkProfile(birthDate.toString(), heightMm, targetWeightG, today())
        dao.putProfile(HealthProfile(sex = sex, birthDate = birthDate.toString(), heightMm = heightMm, targetWeightG = targetWeightG, updatedAt = clock()))
    }

    /**
     * Saves [draft]; returns the record's id. When another record already holds the date, throws
     * [SameDayExists] unless [overwrite] is set, in which case that record takes the new values
     * (and the edited one, if it was a different row, goes away).
     */
    suspend fun save(draft: MeasurementDraft, overwrite: Boolean = false): String = gate.write {
        checkMeasurement(draft.weightG, draft.bodyFatBp)
        ensure(!draft.date.isAfter(today())) { "不能记录未来的日期" }
        val now = clock()
        val date = draft.date.toString()
        val note = draft.note?.trim()?.takeIf { it.isNotEmpty() }
        // A day's own record keeps its time; a back-filled day sits at the end of that day.
        val measuredAt = if (draft.date == today()) now else draft.date.atTime(23, 59, 59).atZone(zone()).toInstant().toEpochMilli()
        db.withTransaction {
            val editing = draft.id?.let { dao.measurement(it) ?: throw LedgerException("这条记录已不存在") }
            val occupant = dao.onDate(date)?.takeIf { it.id != editing?.id }
            if (occupant != null && !overwrite) throw SameDayExists(draft.date)
            when {
                occupant != null -> {
                    if (editing != null) dao.delete(editing.id)
                    dao.update(occupant.copy(measuredAt = measuredAt, weightG = draft.weightG, bodyFatBp = draft.bodyFatBp, note = note, updatedAt = now))
                    occupant.id
                }
                editing != null -> {
                    val keepTime = if (editing.date == date) editing.measuredAt else measuredAt
                    dao.update(editing.copy(date = date, measuredAt = keepTime, weightG = draft.weightG, bodyFatBp = draft.bodyFatBp, note = note, updatedAt = now))
                    editing.id
                }
                else -> {
                    val id = newId()
                    dao.insert(BodyMeasurement(id, date, measuredAt, draft.weightG, draft.bodyFatBp, note, createdAt = now, updatedAt = now))
                    id
                }
            }
        }
    }

    suspend fun delete(id: String) = gate.write { dao.delete(id) }

    companion object {
        fun checkMeasurement(weightG: Long, bodyFatBp: Int?) {
            ensure(weightG >= Health.WEIGHT_MIN_G && weightG <= Health.WEIGHT_MAX_G) { "体重需在 20–300 kg 之间" }
            ensure(bodyFatBp == null || (bodyFatBp >= Health.FAT_MIN_BP && bodyFatBp <= Health.FAT_MAX_BP)) { "体脂率需在 3%–70% 之间" }
        }

        /**
         * Only the format and that the birthday is not in the future: a birthday that makes the
         * person younger than 18 is still accepted, the body fat grade then just stays empty.
         */
        fun checkProfile(birthDate: String, heightMm: Int, targetWeightG: Long?, today: LocalDate) {
            val d = runCatching { LocalDate.parse(birthDate) }.getOrNull()
            ensure(d != null) { "生日格式无效" }
            ensure(!d!!.isAfter(today)) { "生日不能晚于今天" }
            ensure(heightMm >= Health.HEIGHT_MIN_MM && heightMm <= Health.HEIGHT_MAX_MM) { "身高需在 100–250 cm 之间" }
            ensure(targetWeightG == null || (targetWeightG >= Health.WEIGHT_MIN_G && targetWeightG <= Health.WEIGHT_MAX_G)) { "目标体重需在 20–300 kg 之间" }
        }
    }
}
