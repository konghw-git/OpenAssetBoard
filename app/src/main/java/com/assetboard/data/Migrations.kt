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

    /** Every step, in order, for [AppDatabase.build] and the tests that open an old file. */
    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)

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
