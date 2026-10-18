package com.assetboard.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
