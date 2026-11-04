package com.assetboard.data

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

object Migrations {
    /** Adds the todo and diary tables; the six ledger tables are not touched. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in V2_TABLES) db.execSQL(sql)
        }
    }

    /** Adds the daily habit checks; nothing else changes. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `habit_checks` (`date` TEXT NOT NULL, `habit` TEXT NOT NULL, `checked_at` INTEGER NOT NULL, PRIMARY KEY(`date`, `habit`))")
        }
    }

    /** Adds the health profile and the daily weigh-ins; nothing else changes. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `health_profile` (`id` TEXT NOT NULL, `sex` TEXT NOT NULL, `birth_date` TEXT NOT NULL, `height_mm` INTEGER NOT NULL, `target_weight_g` INTEGER, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `body_measurements` (`id` TEXT NOT NULL, `date` TEXT NOT NULL, `measured_at` INTEGER NOT NULL, `weight_g` INTEGER NOT NULL, `body_fat_bp` INTEGER, `note` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_body_measurements_date` ON `body_measurements` (`date`)")
        }
    }

    /** Adds the notes, their topics, sources and drafts; nothing else changes. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in V5_TABLES) db.execSQL(sql)
        }
    }

    /**
     * Notes get a hand-set order, starting as the list showed them before (most recently updated
     * first); weekly reviews and their drafts get their tables. Nothing else changes.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `notes` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE notes SET position = (SELECT COUNT(*) FROM notes n2 WHERE n2.updated_at > notes.updated_at " +
                    "OR (n2.updated_at = notes.updated_at AND n2.id < notes.id))",
            )
            for (sql in V6_TABLES) db.execSQL(sql)
        }
    }

    /** Habits become rows; the table starts empty, which stands for the five built in (see [HabitRepository]). */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(V7_HABITS)
        }
    }

    /** Adds what was eaten; nothing else changes. */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in V8_TABLES) db.execSQL(sql)
        }
    }

    /** A diary day can carry a mood; a habit can be skipped for a day as well as checked. Nothing else changes. */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `diary_entries` ADD COLUMN `mood` INTEGER")
            db.execSQL("ALTER TABLE `habit_checks` ADD COLUMN `skipped` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** A habit can say what counts as done; every habit starts without. Nothing else changes. */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `habits` ADD COLUMN `rubric` TEXT NOT NULL DEFAULT ''")
        }
    }

    /** A todo's reminder can go off ahead of its time; every todo starts at the time itself. Nothing else changes. */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `todos` ADD COLUMN `remind_before` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** A flow can carry a 记账 category; every flow there is starts without. Nothing else changes. */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `flows` ADD COLUMN `category` TEXT")
        }
    }

    /** Adds the countdowns; nothing else changes. */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `countdowns` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `date` TEXT NOT NULL, `yearly` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        }
    }

    /** An account can wear an icon of its own; every existing account keeps following its name. Nothing else changes. */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `accounts` ADD COLUMN `icon` TEXT")
        }
    }

    /**
     * Every synced row gets the merge stamps (docs/sync-merge-semantics.md §6.1) and physical
     * deletes get a tombstone table. Stamps are backfilled from what the row already records:
     * changed_at from the newest of its own time columns, change_id as a `legacy:` hash of the
     * content so two devices migrating the same row agree. Rows without any time column (groups,
     * sections, habits) start at 0 or their date strings; see the known limits in §6.4.
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (table in SYNCED_TABLES) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `changed_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `change_id` TEXT NOT NULL DEFAULT ''")
            }
            for (table in STATE_TABLES) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `state_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `state_id` TEXT NOT NULL DEFAULT ''")
            }
            for (sql in BACKFILL_CHANGED_AT) db.execSQL(sql)
            backfillHabitDates(db)
            for ((table, columns) in BACKFILL_STATE_AT) {
                db.execSQL("UPDATE `$table` SET state_at = COALESCE(${columns.joinToString(", ")}, 0)")
            }
            for (table in SYNCED_TABLES) backfillLegacyChangeIds(db, table, STAMP_COLUMNS)
            for ((table, fields) in STATE_FIELDS) backfillLegacyChangeIds(db, table, fields.toSet(), "state_id")
            db.execSQL("CREATE TABLE IF NOT EXISTS `tombstones` (`tbl` TEXT NOT NULL, `row_key` TEXT NOT NULL, `deleted_at` INTEGER NOT NULL, `change_id` TEXT NOT NULL, PRIMARY KEY(`tbl`, `row_key`))")
            backfillGoalStamp(db)
        }
    }

    /** Every step, in order, for [AppDatabase.build] and the tests that open an old file. */
    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)

    /** The backup's tables; the holiday cache and app_meta are device-local and never carry stamps. */
    private val SYNCED_TABLES = listOf(
        "account_groups", "accounts", "snapshots", "valuations", "snapshot_items", "flows",
        "todo_lists", "todo_sections", "todos", "checklist_items", "diary_entries", "diary_drafts",
        "habit_checks", "habits", "health_profile", "body_measurements", "food_entries",
        "note_topics", "notes", "note_sources", "note_drafts", "weekly_reviews", "weekly_drafts", "countdowns",
    )

    private val STATE_TABLES = listOf("diary_entries", "notes", "todos", "countdowns")

    /** The four stamp columns never take part in a content hash. */
    private val STAMP_COLUMNS = setOf("changed_at", "change_id", "state_at", "state_id")

    private val STATE_FIELDS = mapOf(
        "diary_entries" to listOf("weather_code", "temp_min", "temp_max", "city", "lat", "lon", "weather_at", "weather_rev"),
        "notes" to listOf("reviewed_at", "pinned"),
        "todos" to listOf("reminded_period", "snooze_at"),
        "countdowns" to listOf("pinned"),
    )

    /** Each table's own record of when its rows last changed; the max of what exists, as §1.3 records. */
    private val BACKFILL_CHANGED_AT = listOf(
        "UPDATE `accounts` SET changed_at = created_at",
        "UPDATE `snapshots` SET changed_at = at",
        "UPDATE `valuations` SET changed_at = max(at, created_at)",
        "UPDATE `snapshot_items` SET changed_at = COALESCE((SELECT at FROM snapshots WHERE snapshots.id = snapshot_items.snapshot_id), 0)",
        "UPDATE `flows` SET changed_at = max(at, created_at)",
        "UPDATE `todo_lists` SET changed_at = max(COALESCE(archived_at, 0), created_at, COALESCE(deleted_at, 0))",
        "UPDATE `todo_sections` SET changed_at = COALESCE(deleted_at, 0)",
        "UPDATE `todos` SET changed_at = max(created_at, COALESCE(completed_at, 0), COALESCE(deleted_at, 0), COALESCE(snooze_at, 0), COALESCE(repeat_ended_at, 0))",
        "UPDATE `checklist_items` SET changed_at = COALESCE((SELECT changed_at FROM todos WHERE todos.id = checklist_items.todo_id), 0)",
        "UPDATE `diary_entries` SET changed_at = max(created_at, updated_at, COALESCE(deleted_at, 0))",
        "UPDATE `diary_drafts` SET changed_at = created_at",
        "UPDATE `habit_checks` SET changed_at = checked_at",
        "UPDATE `health_profile` SET changed_at = updated_at",
        "UPDATE `body_measurements` SET changed_at = max(measured_at, created_at, updated_at)",
        "UPDATE `food_entries` SET changed_at = max(eaten_at, created_at, updated_at)",
        "UPDATE `note_topics` SET changed_at = created_at",
        "UPDATE `notes` SET changed_at = max(created_at, updated_at, COALESCE(deleted_at, 0))",
        "UPDATE `note_sources` SET changed_at = created_at",
        "UPDATE `note_drafts` SET changed_at = created_at",
        "UPDATE `weekly_reviews` SET changed_at = max(created_at, updated_at, COALESCE(deleted_at, 0))",
        "UPDATE `weekly_drafts` SET changed_at = created_at",
        "UPDATE `countdowns` SET changed_at = max(created_at, updated_at)",
    )

    private val BACKFILL_STATE_AT = mapOf(
        "diary_entries" to listOf("COALESCE(weather_at, 0)"),
        "notes" to listOf("COALESCE(reviewed_at, 0)"),
        "todos" to listOf("COALESCE(snooze_at, 0)"),
        "countdowns" to listOf("updated_at"),
    )

    /**
     * Habits only know the local dates they were added and removed on; the stamp is the start of
     * that day. `added_on` wins when there is no `removed_on`; neither means the epoch.
     */
    private fun backfillHabitDates(db: SupportSQLiteDatabase) {
        val zone = ZoneId.systemDefault()
        val updates = mutableListOf<Pair<String, Long>>()
        db.query("SELECT `key`, `added_on`, `removed_on` FROM `habits`").use { c ->
            val key = c.getColumnIndexOrThrow("key")
            val added = c.getColumnIndexOrThrow("added_on")
            val removed = c.getColumnIndexOrThrow("removed_on")
            while (c.moveToNext()) {
                val day = (if (c.isNull(removed)) c.getString(added) else c.getString(removed)) ?: continue
                val at = runCatching { LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrDefault(0L)
                updates += c.getString(key) to at
            }
        }
        for ((key, at) in updates) db.execSQL("UPDATE `habits` SET changed_at = ? WHERE `key` = ?", arrayOf<Any?>(at, key))
    }

    /**
     * `legacy:<hex>` from a deterministic hash of the row's content. [excluded] columns stay out
     * of the projection (the stamps themselves, or everything but the state fields); [prefix]
     * names a state projection apart from the content one.
     */
    private fun backfillLegacyChangeIds(
        db: SupportSQLiteDatabase,
        table: String,
        excluded: Set<String>,
        column: String = "change_id",
        prefix: String = "legacy",
    ) {
        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) {
                val columnName = c.getString(name)
                if (columnName !in excluded) columns += columnName
            }
        }
        columns.sort()
        val updates = mutableListOf<Pair<Long, String>>()
        db.query("SELECT rowid, * FROM `$table`").use { c ->
            val rowid = c.getColumnIndexOrThrow("rowid")
            val indices = columns.map { c.getColumnIndexOrThrow(it) }
            while (c.moveToNext()) {
                val sb = StringBuilder(prefix).append('\u0000').append(table)
                columns.forEachIndexed { i, name ->
                    sb.append('\u0000').append(name).append('=').append(cell(c, indices[i]))
                }
                updates += c.getLong(rowid) to "$prefix:" + sha256(sb.toString()).take(32)
            }
        }
        for ((rowid, id) in updates) db.execSQL("UPDATE `$table` SET `$column` = ? WHERE rowid = ?", arrayOf<Any?>(id, rowid))
    }

    /** One cell as a stable string: the type tag keeps 1, "1" and 1.0 apart. */
    private fun cell(c: Cursor, index: Int): String {
        val value = when (c.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> "~"
            Cursor.FIELD_TYPE_INTEGER -> "i" + c.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> "f" + java.lang.Double.toString(c.getDouble(index))
            Cursor.FIELD_TYPE_BLOB -> "b" + c.getBlob(index).contentToString()
            else -> "s" + c.getString(index).orEmpty()
        }
        return value.length.toString() + ":" + value
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** The goal gets a stamp too, so clearing it can be merged; its value alone dates it. */
    private fun backfillGoalStamp(db: SupportSQLiteDatabase) {
        var value: String? = null
        db.query("SELECT `value` FROM `app_meta` WHERE `key` = 'net_worth_goal'").use { c ->
            if (c.moveToNext()) value = c.getString(0)
        }
        if (value == null) return
        val id = "legacy:" + sha256("netWorthGoal\u0000$value").take(32)
        db.execSQL("INSERT OR REPLACE INTO `app_meta` (`key`, `value`) VALUES ('net_worth_goal_at', '0')")
        db.execSQL("INSERT OR REPLACE INTO `app_meta` (`key`, `value`) VALUES ('net_worth_goal_change_id', ?)", arrayOf(id))
    }

    private val V8_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `food_entries` (`id` TEXT NOT NULL, `date` TEXT NOT NULL, `eaten_at` INTEGER NOT NULL, `name` TEXT, `per` INTEGER, `amount` INTEGER, `unit` TEXT, `energy_cal` INTEGER NOT NULL, `in_kj` INTEGER NOT NULL, `protein_mg` INTEGER, `fat_mg` INTEGER, `carb_mg` INTEGER, `sodium_mg` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_food_entries_date` ON `food_entries` (`date`)",
    )

    private const val V7_HABITS =
        "CREATE TABLE IF NOT EXISTS `habits` (`key` TEXT NOT NULL, `label` TEXT NOT NULL, `icon` TEXT NOT NULL, `color` INTEGER NOT NULL, `position` INTEGER NOT NULL, `added_on` TEXT, `removed_on` TEXT, PRIMARY KEY(`key`))"

    private val V6_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `weekly_reviews` (`id` TEXT NOT NULL, `week_start` TEXT NOT NULL, `body` TEXT NOT NULL, `body_rev` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `delete_batch` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_weekly_reviews_week_start` ON `weekly_reviews` (`week_start`)",
        "CREATE INDEX IF NOT EXISTS `index_weekly_reviews_delete_batch` ON `weekly_reviews` (`delete_batch`)",
        "CREATE TABLE IF NOT EXISTS `weekly_drafts` (`id` TEXT NOT NULL, `week_start` TEXT NOT NULL, `review_id` TEXT, `text` TEXT NOT NULL, `reason` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`review_id`) REFERENCES `weekly_reviews`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_weekly_drafts_review_id` ON `weekly_drafts` (`review_id`)",
        "CREATE INDEX IF NOT EXISTS `index_weekly_drafts_week_start` ON `weekly_drafts` (`week_start`)",
    )

    private val V5_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `note_topics` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `topic_id` TEXT, `body` TEXT NOT NULL, `body_rev` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `reviewed_at` INTEGER, `deleted_at` INTEGER, `delete_batch` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`topic_id`) REFERENCES `note_topics`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_notes_topic_id` ON `notes` (`topic_id`)",
        "CREATE INDEX IF NOT EXISTS `index_notes_delete_batch` ON `notes` (`delete_batch`)",
        "CREATE TABLE IF NOT EXISTS `note_sources` (`note_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `ref` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`note_id`, `kind`, `ref`), FOREIGN KEY(`note_id`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_note_sources_kind_ref` ON `note_sources` (`kind`, `ref`)",
        "CREATE TABLE IF NOT EXISTS `note_drafts` (`id` TEXT NOT NULL, `note_id` TEXT, `text` TEXT NOT NULL, `reason` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`note_id`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_note_drafts_note_id` ON `note_drafts` (`note_id`)",
    )

    private val V2_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `todo_lists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `notes` TEXT NOT NULL, `deadline` TEXT, `sort_order` INTEGER NOT NULL, `archived_at` INTEGER, `created_at` INTEGER NOT NULL, `deleted_at` INTEGER, `delete_batch` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `todo_sections` (`id` TEXT NOT NULL, `list_id` TEXT NOT NULL, `name` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `deleted_at` INTEGER, `delete_batch` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`list_id`) REFERENCES `todo_lists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_todo_sections_list_id` ON `todo_sections` (`list_id`)",
        "CREATE TABLE IF NOT EXISTS `todos` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `notes` TEXT NOT NULL, `list_id` TEXT, `section_id` TEXT, `plan_date` TEXT, `remind_time` TEXT, `strong_remind` INTEGER NOT NULL, `someday` INTEGER NOT NULL, `deadline` TEXT, `important` INTEGER NOT NULL, `snooze_at` INTEGER, `reminded_period` INTEGER, `repeat_rule` TEXT, `repeat_ended_at` INTEGER, `occurrence_count` INTEGER NOT NULL, `repeat_source_id` TEXT, `undo_before` TEXT, `undo_revision` INTEGER, `state_seq` INTEGER NOT NULL, `revision` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `completed_at` INTEGER, `deleted_at` INTEGER, `delete_batch` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`list_id`) REFERENCES `todo_lists`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`section_id`) REFERENCES `todo_sections`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`repeat_source_id`) REFERENCES `todos`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_todos_list_id` ON `todos` (`list_id`)",
        "CREATE INDEX IF NOT EXISTS `index_todos_section_id` ON `todos` (`section_id`)",
        "CREATE INDEX IF NOT EXISTS `index_todos_plan_date` ON `todos` (`plan_date`)",
        "CREATE INDEX IF NOT EXISTS `index_todos_completed_at` ON `todos` (`completed_at`)",
        "CREATE INDEX IF NOT EXISTS `index_todos_delete_batch` ON `todos` (`delete_batch`)",
        "CREATE INDEX IF NOT EXISTS `index_todos_repeat_source_id` ON `todos` (`repeat_source_id`)",
        "CREATE TABLE IF NOT EXISTS `checklist_items` (`id` TEXT NOT NULL, `todo_id` TEXT NOT NULL, `text` TEXT NOT NULL, `done` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`todo_id`) REFERENCES `todos`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_checklist_items_todo_id` ON `checklist_items` (`todo_id`)",
        "CREATE TABLE IF NOT EXISTS `diary_entries` (`id` TEXT NOT NULL, `date` TEXT NOT NULL, `body` TEXT NOT NULL, `body_rev` INTEGER NOT NULL, `weather_code` INTEGER, `temp_min` REAL, `temp_max` REAL, `city` TEXT, `lat` REAL, `lon` REAL, `weather_at` INTEGER, `weather_rev` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `delete_batch` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_diary_entries_date` ON `diary_entries` (`date`)",
        "CREATE INDEX IF NOT EXISTS `index_diary_entries_delete_batch` ON `diary_entries` (`delete_batch`)",
        "CREATE TABLE IF NOT EXISTS `diary_drafts` (`id` TEXT NOT NULL, `date` TEXT NOT NULL, `diary_id` TEXT, `text` TEXT NOT NULL, `reason` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`diary_id`) REFERENCES `diary_entries`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_diary_drafts_diary_id` ON `diary_drafts` (`diary_id`)",
        "CREATE INDEX IF NOT EXISTS `index_diary_drafts_date` ON `diary_drafts` (`date`)",
        "CREATE TABLE IF NOT EXISTS `holidays` (`date` TEXT NOT NULL, `name` TEXT NOT NULL, `is_off_day` INTEGER NOT NULL, PRIMARY KEY(`date`))",
        "CREATE TABLE IF NOT EXISTS `holiday_years` (`year` INTEGER NOT NULL, `source` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`year`))",
        "CREATE TABLE IF NOT EXISTS `app_meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
    )
}
