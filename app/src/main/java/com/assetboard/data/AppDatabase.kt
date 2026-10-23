package com.assetboard.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM account_groups ORDER BY sort_order")
    fun observeGroups(): Flow<List<AccountGroup>>

    @Query("SELECT * FROM accounts ORDER BY sort_order")
    fun observeAccounts(): Flow<List<Account>>

    @Query("SELECT * FROM valuations")
    fun observeValuations(): Flow<List<Valuation>>

    @Query("SELECT * FROM flows")
    fun observeFlows(): Flow<List<MoneyFlow>>

    @Query("SELECT * FROM snapshots ORDER BY at")
    fun observeSnapshots(): Flow<List<Snapshot>>

    @Query("SELECT * FROM snapshot_items")
    fun observeSnapshotItems(): Flow<List<SnapshotItem>>

    @Query("SELECT * FROM account_groups ORDER BY sort_order")
    suspend fun groups(): List<AccountGroup>

    @Query("SELECT * FROM accounts ORDER BY sort_order")
    suspend fun accounts(): List<Account>

    @Query("SELECT * FROM valuations")
    suspend fun valuations(): List<Valuation>

    @Query("SELECT * FROM flows")
    suspend fun flows(): List<MoneyFlow>

    @Query("SELECT * FROM snapshots ORDER BY at")
    suspend fun snapshots(): List<Snapshot>

    @Query("SELECT * FROM snapshot_items")
    suspend fun snapshotItems(): List<SnapshotItem>

    @Query("SELECT * FROM account_groups WHERE id = :id")
    suspend fun group(id: String): AccountGroup?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun account(id: String): Account?

    @Query("SELECT * FROM valuations WHERE id = :id")
    suspend fun valuation(id: String): Valuation?

    @Query("SELECT * FROM valuations WHERE account_id = :accountId AND at = :at")
    suspend fun valuationAt(accountId: String, at: Long): Valuation?

    @Query("SELECT * FROM valuations WHERE snapshot_id = :snapshotId")
    suspend fun valuationsOfSnapshot(snapshotId: String): List<Valuation>

    @Query("SELECT * FROM flows WHERE id = :id")
    suspend fun flow(id: String): MoneyFlow?

    @Query("SELECT * FROM snapshots WHERE id = :id")
    suspend fun snapshot(id: String): Snapshot?

    @Query("SELECT * FROM snapshots WHERE at = :at")
    suspend fun snapshotAt(at: Long): Snapshot?

    @Query("SELECT * FROM snapshot_items WHERE snapshot_id = :snapshotId")
    suspend fun itemsOfSnapshot(snapshotId: String): List<SnapshotItem>

    @Query("SELECT COUNT(*) FROM accounts WHERE group_id = :groupId")
    suspend fun accountCountInGroup(groupId: String): Int

    @Query("SELECT COUNT(*) FROM flows WHERE from_account_id = :accountId OR to_account_id = :accountId")
    suspend fun flowCount(accountId: String): Int

    @Query("SELECT COUNT(*) FROM valuations WHERE account_id = :accountId")
    suspend fun valuationCount(accountId: String): Int

    @Query("SELECT COUNT(*) FROM snapshot_items WHERE account_id = :accountId")
    suspend fun snapshotItemCount(accountId: String): Int

    @Query("SELECT COUNT(*) FROM snapshot_items WHERE valuation_id = :valuationId")
    suspend fun itemCountForValuation(valuationId: String): Int

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM account_groups")
    suspend fun maxGroupOrder(): Int

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM accounts")
    suspend fun maxAccountOrder(): Int

    @Insert suspend fun insertGroup(group: AccountGroup)
    @Insert suspend fun insertAccount(account: Account)
    @Insert suspend fun insertValuation(valuation: Valuation)
    @Insert suspend fun insertFlow(flow: MoneyFlow)
    @Insert suspend fun insertSnapshot(snapshot: Snapshot)
    @Insert suspend fun insertSnapshotItem(item: SnapshotItem)

    @Insert suspend fun insertGroups(items: List<AccountGroup>)
    @Insert suspend fun insertAccounts(items: List<Account>)
    @Insert suspend fun insertValuations(items: List<Valuation>)
    @Insert suspend fun insertFlows(items: List<MoneyFlow>)
    @Insert suspend fun insertSnapshots(items: List<Snapshot>)
    @Insert suspend fun insertSnapshotItems(items: List<SnapshotItem>)

    @Update suspend fun updateGroup(group: AccountGroup)
    @Update suspend fun updateGroups(groups: List<AccountGroup>)
    @Update suspend fun updateAccount(account: Account)
    @Update suspend fun updateValuation(valuation: Valuation)
    @Update suspend fun updateFlow(flow: MoneyFlow)
    @Update suspend fun updateSnapshot(snapshot: Snapshot)
    @Update suspend fun updateSnapshotItem(item: SnapshotItem)

    @Delete suspend fun deleteGroup(group: AccountGroup)
    @Delete suspend fun deleteAccount(account: Account)
    @Delete suspend fun deleteValuation(valuation: Valuation)
    @Delete suspend fun deleteFlow(flow: MoneyFlow)
    @Delete suspend fun deleteSnapshot(snapshot: Snapshot)

    // Children first, so RESTRICT never fires while wiping for a restore.
    @Query("DELETE FROM snapshot_items") suspend fun clearSnapshotItems()
    @Query("DELETE FROM flows") suspend fun clearFlows()
    @Query("DELETE FROM valuations") suspend fun clearValuations()
    @Query("DELETE FROM snapshots") suspend fun clearSnapshots()
    @Query("DELETE FROM accounts") suspend fun clearAccounts()
    @Query("DELETE FROM account_groups") suspend fun clearGroups()
}

@Database(
    entities = [
        AccountGroup::class, Account::class, Snapshot::class, Valuation::class, SnapshotItem::class, MoneyFlow::class,
        TodoList::class, TodoSection::class, Todo::class, ChecklistItem::class, DiaryEntry::class, DiaryDraft::class,
        Holiday::class, HolidayYear::class, AppMeta::class, HabitCheck::class, com.assetboard.domain.Habit::class,
        HealthProfile::class, BodyMeasurement::class, FoodEntry::class,
        NoteTopic::class, Note::class, NoteSource::class, NoteDraft::class,
        WeeklyReview::class, WeeklyDraft::class,
    ],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
    abstract fun todoDao(): TodoDao
    abstract fun diaryDao(): DiaryDao
    abstract fun metaDao(): MetaDao
    abstract fun habitDao(): HabitDao
    abstract fun healthDao(): HealthDao
    abstract fun foodDao(): FoodDao
    abstract fun noteDao(): NoteDao
    abstract fun weekDao(): WeekDao

    companion object {
        const val SCHEMA_VERSION = 11

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "asset-board.db")
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
