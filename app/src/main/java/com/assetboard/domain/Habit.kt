package com.assetboard.domain

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * One of the things checked off every day, outside the todo list. [key] is what each day's checks
 * store, so it never changes; [position] is the order they are shown in. [icon] names one of the
 * app's habit icons and [color] is ARGB.
 *
 * A habit belongs to the days from [addedOn] (null: from the start) up to the day before
 * [removedOn]: removing one keeps the days it was part of as they were, while a new one does not
 * show as missed on days before it existed.
 */
@Serializable
@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey val key: String,
    val label: String,
    val icon: String,
    val color: Long,
    val position: Int,
    @ColumnInfo(name = "added_on") val addedOn: String? = null,
    @ColumnInfo(name = "removed_on") val removedOn: String? = null,
) {
    val active: Boolean get() = removedOn == null

    /** Whether [date] is one of this habit's days. */
    fun on(date: LocalDate): Boolean =
        (addedOn == null || !LocalDate.parse(addedOn).isAfter(date)) && (removedOn == null || date.isBefore(LocalDate.parse(removedOn)))

    companion object {
        /** The five the app starts with; their keys are the ones stored before habits could change. */
        val SUPPLEMENTS = Habit("supplements", "吃补剂", "medication", 0xFFD9822B, 0)
        val WORKOUT = Habit("workout", "健身", "fitness", 0xFFD0533F, 1)
        val DIET = Habit("diet", "控制饮食", "restaurant", 0xFF5E9B3A, 2)
        val WATER = Habit("water", "喝够水", "water", 0xFF3D8FD1, 3)
        val SLEEP = Habit("sleep", "早睡早起", "bedtime", 0xFF7A62C2, 4)
        val DEFAULTS = listOf(SUPPLEMENTS, WORKOUT, DIET, WATER, SLEEP)

        /** The battery has room for this many. */
        const val MAX_ACTIVE = 7
        const val MAX_LABEL = 8

        /**
         * The habits a day shows, in order: the ones it belonged to, and any checked that day
         * though since removed, so a record never loses a tick.
         */
        fun shownOn(date: LocalDate, all: List<Habit>, checked: Set<String>): List<Habit> =
            all.filter { it.on(date) || it.key in checked }.sortedBy { it.position }
    }
}

/** A day's habits and which of them were checked. */
data class HabitDay(val shown: List<Habit>, val checked: Set<String>) {
    val done: Int get() = shown.count { it.key in checked }
}
