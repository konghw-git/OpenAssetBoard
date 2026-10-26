package com.assetboard.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A day counted to, or from once it has passed: an exam, a trip, a birthday. A [yearly] one
 * counts to the next time its month and day come round, so it never passes. [pinned] is the one
 * the widget shows large; at most one is, which the repository keeps.
 */
@Serializable
@Entity(tableName = "countdowns")
data class Countdown(
    @PrimaryKey val id: String,
    val title: String,
    /** ISO date; for a yearly one the first occurrence, which also numbers the later ones. */
    val date: String,
    val yearly: Boolean = false,
    val pinned: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface CountdownDao {
    @Query("SELECT * FROM countdowns ORDER BY created_at") fun observeAll(): Flow<List<Countdown>>
    @Query("SELECT * FROM countdowns ORDER BY created_at") suspend fun all(): List<Countdown>
    @Query("SELECT * FROM countdowns WHERE id = :id") suspend fun get(id: String): Countdown?
    @Insert suspend fun insert(c: Countdown)
    @Insert suspend fun insertAll(items: List<Countdown>)
    @Update suspend fun update(c: Countdown)
    @Query("UPDATE countdowns SET pinned = 0 WHERE id != :id") suspend fun unpinOthers(id: String)
    @Query("DELETE FROM countdowns WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM countdowns") suspend fun clear()
}
