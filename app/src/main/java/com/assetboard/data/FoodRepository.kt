package com.assetboard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import com.assetboard.domain.LedgerException
import com.assetboard.domain.ensure
import com.assetboard.domain.health.Energy
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

@Dao
interface FoodDao {
    @Query("SELECT * FROM food_entries ORDER BY date, eaten_at, created_at") fun observeAll(): Flow<List<FoodEntry>>
    @Query("SELECT * FROM food_entries WHERE id = :id") suspend fun entry(id: String): FoodEntry?
    @Query("SELECT * FROM food_entries WHERE date = :date ORDER BY eaten_at, created_at") suspend fun onDate(date: String): List<FoodEntry>

    @Insert suspend fun insert(e: FoodEntry)
    @Update suspend fun update(e: FoodEntry)
    @Query("DELETE FROM food_entries WHERE id = :id") suspend fun delete(id: String)

    // ------------------------------------------------------------ backup
    @Query("SELECT * FROM food_entries ORDER BY date, eaten_at, created_at") suspend fun all(): List<FoodEntry>
    @Insert suspend fun insertAll(items: List<FoodEntry>)
    @Query("DELETE FROM food_entries") suspend fun clear()
}

/** What the food sheet hands in; [id] is null for a new entry. */
data class FoodDraft(
    val id: String?,
    val date: LocalDate,
    val name: String?,
    val per: Int?,
    val amount: Int?,
    val unit: FoodUnit?,
    val energyCal: Long,
    val inKj: Boolean,
    val proteinMg: Long?,
    val fatMg: Long?,
    val carbMg: Long?,
    val sodiumMg: Long?,
)

/**
 * What was eaten, one row per thing, as many a day as there were. Nothing is derived here: the
 * day's sums and the burn are [Energy]'s, from these rows, the profile and the weigh-ins.
 */
class FoodRepository(
    private val db: AppDatabase,
    private val gate: WriteGate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = db.foodDao()

    val entries: Flow<List<FoodEntry>> = dao.observeAll()

    fun today(): LocalDate = com.assetboard.domain.todo.TodoViews.localDate(clock(), zone())

    suspend fun onDate(date: LocalDate): List<FoodEntry> = dao.onDate(date.toString())

    /** Saves [draft]; returns the entry's id. An edit keeps its time unless it moves to another day. */
    suspend fun save(draft: FoodDraft): String = gate.write {
        val name = draft.name?.trim()?.takeIf { it.isNotEmpty() }
        val now = clock()
        val date = draft.date.toString()
        val e = FoodEntry(
            id = draft.id ?: newId(), date = date, eatenAt = 0, name = name,
            per = draft.per, amount = draft.amount, unit = draft.unit,
            energyCal = draft.energyCal, inKj = draft.inKj,
            proteinMg = draft.proteinMg, fatMg = draft.fatMg, carbMg = draft.carbMg, sodiumMg = draft.sodiumMg,
            createdAt = now, updatedAt = now,
        )
        check(e)
        ensure(!draft.date.isAfter(today())) { "不能记录未来的日期" }
        // Eaten now if it is today's; a back-filled day sits at the end of that day.
        val eatenAt = if (draft.date == today()) now else draft.date.atTime(23, 59, 59).atZone(zone()).toInstant().toEpochMilli()
        db.withTransaction {
            val editing = draft.id?.let { dao.entry(it) ?: throw LedgerException("这条记录已不存在") }
            if (editing == null) {
                dao.insert(e.copy(eatenAt = eatenAt))
            } else {
                dao.update(e.copy(eatenAt = if (editing.date == date) editing.eatenAt else eatenAt, createdAt = editing.createdAt))
            }
            e.id
        }
    }

    /** Removes the entry and hands it back, so an undo can put it back as it was. */
    suspend fun delete(id: String): FoodEntry? = gate.write {
        db.withTransaction { dao.entry(id)?.also { dao.delete(id) } }
    }

    /** Puts back an entry [delete] returned, id and times unchanged. */
    suspend fun restore(e: FoodEntry) = gate.write {
        check(e)
        db.withTransaction { if (dao.entry(e.id) == null) dao.insert(e) }
    }

    companion object {
        /** The rules a save and a restore share; the date's future check is the save's alone. */
        fun check(e: FoodEntry) {
            ensure(runCatching { LocalDate.parse(e.date) }.isSuccess) { "日期无效" }
            ensure((e.name?.length ?: 0) <= Energy.NAME_MAX) { "名称最多 ${Energy.NAME_MAX} 个字" }
            val labelled = e.per != null
            ensure(labelled == (e.amount != null) && labelled == (e.unit != null)) { "包装的份量和吃了多少要一起填" }
            if (e.unit == FoodUnit.SERVING) {
                ensure(e.per == Energy.SERVING_PER && e.amount!! in 1..Energy.SERVINGS_MAX_HALVES) { "份数需在 0.5–${Energy.SERVINGS_MAX_HALVES / Energy.SERVING_PER}" }
            } else if (labelled) {
                ensure(e.per!! in 1..Energy.PER_MAX) { "每份的量需在 1–${Energy.PER_MAX}" }
                ensure(e.amount!! in 1..Energy.AMOUNT_MAX) { "吃了多少需在 1–${Energy.AMOUNT_MAX}" }
            }
            ensure(e.energyCal in 0..Energy.ENERGY_MAX_CAL && Energy.energyOf(e) <= Energy.ENERGY_MAX_CAL) { "热量过大，请检查单位和数字" }
            for (v in listOf(e.proteinMg, e.fatMg, e.carbMg, e.sodiumMg)) {
                if (v == null) continue
                ensure(v in 0..Energy.NUTRIENT_MAX_MG && Energy.scaled(v, e.per, e.amount) <= Energy.NUTRIENT_MAX_MG) { "营养素数值过大，请检查" }
            }
        }
    }
}
