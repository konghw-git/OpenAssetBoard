package com.assetboard.domain

/**
 * The five things checked off every day, outside the todo list. [key] is what the database and
 * backups store, so it never changes; the order is the order they are shown in.
 */
enum class Habit(val key: String, val label: String) {
    SUPPLEMENTS("supplements", "吃补剂"),
    WORKOUT("workout", "健身"),
    DIET("diet", "控制饮食"),
    WATER("water", "喝够水"),
    SLEEP("sleep", "早睡早起");

    companion object {
        fun of(key: String): Habit? = entries.firstOrNull { it.key == key }
    }
}
