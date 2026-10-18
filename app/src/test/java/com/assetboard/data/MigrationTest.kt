package com.assetboard.data

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step-0 release gate: the explicit 1 → 2 migration, run against the exported schema 1.json with
 * data shaped like the real ledger. The six ledger tables must come out row for row identical.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val ledgerTables = listOf("account_groups", "accounts", "snapshots", "valuations", "snapshot_items", "flows")

    private fun SupportSQLiteDatabase.put(table: String, vararg pairs: Pair<String, Any?>) {
        val cv = ContentValues()
        for ((k, v) in pairs) when (v) {
            null -> cv.putNull(k)
            is String -> cv.put(k, v)
            is Long -> cv.put(k, v)
            is Int -> cv.put(k, v)
            is Boolean -> cv.put(k, if (v) 1 else 0)
            else -> error("unsupported $v")
        }
        insert(table, SQLiteDatabase.CONFLICT_ABORT, cv)
    }

    private fun SupportSQLiteDatabase.dump(table: String): List<List<Any?>> =
        query("SELECT * FROM `$table` ORDER BY rowid").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        (0 until c.columnCount).map { i ->
                            when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> null
                                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                else -> c.getString(i)
                            }
                        } + c.columnNames.toList(),
                    )
                }
            }
        }

    private fun seedV1(db: SupportSQLiteDatabase) {
        db.put("account_groups", "id" to "g1", "name" to "储蓄账户", "is_liability" to false, "color_index" to 1, "sort_order" to 0)
        db.put("account_groups", "id" to "g2", "name" to "负债账户", "is_liability" to true, "color_index" to 3, "sort_order" to 1)
        db.put("account_groups", "id" to "g3", "name" to "投资账户", "is_liability" to false, "color_index" to 4, "sort_order" to 2)
        db.put(
            "accounts", "id" to "a1", "group_id" to "g1", "name" to "农行①", "purpose" to "日常消费", "kind" to "NORMAL",
            "institution" to "中国农业银行", "card_tail" to "1234", "code" to null, "locked_until" to null, "baseline_at" to 1_000L,
            "baseline_cost" to null, "include_in_total" to true, "is_disabled" to false, "note" to null, "sort_order" to 0, "created_at" to 1_000L,
        )
        db.put(
            "accounts", "id" to "a2", "group_id" to "g2", "name" to "校园贷款", "purpose" to null, "kind" to "NORMAL",
            "institution" to null, "card_tail" to null, "code" to null, "locked_until" to 9_000_000L, "baseline_at" to 1_000L,
            "baseline_cost" to null, "include_in_total" to true, "is_disabled" to false, "note" to "四年", "sort_order" to 1, "created_at" to 1_000L,
        )
        db.put(
            "accounts", "id" to "a3", "group_id" to "g3", "name" to "招商产业债券A", "purpose" to "3年后", "kind" to "INVESTMENT",
            "institution" to null, "card_tail" to null, "code" to "217022", "locked_until" to null, "baseline_at" to 2_000L,
            "baseline_cost" to 1_600_000L, "include_in_total" to true, "is_disabled" to true, "note" to null, "sort_order" to 2, "created_at" to 2_000L,
        )
        db.put("snapshots", "id" to "s1", "at" to 5_000L, "note" to "月底")
        db.put("valuations", "id" to "v1", "account_id" to "a1", "at" to 1_000L, "value" to 31L, "shares" to null, "snapshot_id" to null, "note" to "期初", "created_at" to 1_000L)
        db.put("valuations", "id" to "v2", "account_id" to "a2", "at" to 1_000L, "value" to -2_000_000L, "shares" to null, "snapshot_id" to null, "note" to "期初", "created_at" to 1_000L)
        db.put("valuations", "id" to "v3", "account_id" to "a3", "at" to 2_000L, "value" to 1_664_911L, "shares" to null, "snapshot_id" to null, "note" to "期初", "created_at" to 2_000L)
        db.put("valuations", "id" to "v4", "account_id" to "a1", "at" to 5_000L, "value" to 4_200L, "shares" to null, "snapshot_id" to "s1", "note" to null, "created_at" to 5_000L)
        db.put("snapshot_items", "snapshot_id" to "s1", "account_id" to "a1", "valuation_id" to "v4", "included" to true)
        db.put("flows", "id" to "f1", "kind" to "BUY", "from_account_id" to "a1", "to_account_id" to "a3", "amount" to 200_000L, "fee" to 300L, "fee_on" to "TO", "at" to 3_000L, "note" to null, "created_at" to 3_000L)
        db.put("flows", "id" to "f2", "kind" to "EXTERNAL_IN", "from_account_id" to null, "to_account_id" to "a1", "amount" to 10_000L, "fee" to 0L, "fee_on" to "TO", "at" to 4_000L, "note" to "工资", "created_at" to 4_000L)
    }

    @Test fun migrationKeepsEveryLedgerRow() {
        val before = helper.createDatabase(name, 1).use { db ->
            seedV1(db)
            ledgerTables.associateWith { db.dump(it) }
        }
        val after = helper.runMigrationsAndValidate(name, 2, true, Migrations.MIGRATION_1_2).use { db ->
            ledgerTables.associateWith { db.dump(it) }
        }
        for (t in ledgerTables) assertEquals("table $t", before[t], after[t])
        assertEquals(3, after.getValue("account_groups").size)
        assertEquals(4, after.getValue("valuations").size)
    }

    @Test fun migratedDatabaseOpensWithRoomAndAcceptsNewData() = runBlocking {
        helper.createDatabase(name, 1).use(::seedV1)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, context.getDatabasePath(name).absolutePath)
            .addMigrations(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val ledger = db.dao()
            assertEquals(3, ledger.accounts().size)
            assertEquals(listOf("f1", "f2"), ledger.flows().map { it.id }.sorted())
            assertTrue(db.todoDao().allTodos().isEmpty())
            db.todoDao().insertList(TodoList("l1", "清单", createdAt = 1))
            db.todoDao().insertTodo(Todo("t1", title = "迁移后的第一条", listId = "l1", createdAt = 1))
            assertEquals("l1", db.todoDao().todo("t1")!!.listId)
        } finally {
            db.close()
        }
    }

    @Test fun migrationTwoToThreeAddsHabitsAndKeepsEverything() {
        val tables = ledgerTables + listOf("todo_lists", "todos", "diary_entries")
        val before = helper.createDatabase(name, 1).use { db ->
            seedV1(db)
            ledgerTables.associateWith { db.dump(it) }
        }
        helper.runMigrationsAndValidate(name, 2, true, Migrations.MIGRATION_1_2).use { db ->
            db.put("todo_lists", "id" to "l1", "name" to "清单", "notes" to "", "deadline" to null, "sort_order" to 0, "archived_at" to null, "created_at" to 1L, "deleted_at" to null, "delete_batch" to null)
            db.put("diary_entries", "id" to "d1", "date" to "2026-10-20", "body" to "正文", "body_rev" to 1L, "weather_code" to null, "temp_min" to null, "temp_max" to null, "city" to null, "lat" to null, "lon" to null, "weather_at" to null, "weather_rev" to 0L, "created_at" to 1L, "updated_at" to 1L, "deleted_at" to null, "delete_batch" to null)
        }
        val mid = helper.runMigrationsAndValidate(name, 2, false).use { db -> tables.associateWith { db.dump(it) } }
        val after = helper.runMigrationsAndValidate(name, 3, true, Migrations.MIGRATION_2_3).use { db ->
            db.put("habit_checks", "date" to "2026-10-20", "habit" to "workout", "checked_at" to 1L)
            assertEquals(1, db.dump("habit_checks").size)
            tables.associateWith { db.dump(it) }
        }
        for (t in tables) assertEquals("table $t", mid[t], after[t])
        for (t in ledgerTables) assertEquals("table $t", before[t], after[t])
    }
}
