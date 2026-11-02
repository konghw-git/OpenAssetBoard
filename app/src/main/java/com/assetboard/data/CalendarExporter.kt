package com.assetboard.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** One calendar the app may write into, as the picker shows it. */
data class CalendarChoice(val id: Long, val name: String, val account: String, val color: Int)

/** Where a todo's event lives on this device; device-local, never backed up. */
data class CalendarLink(val calendarId: Long, val eventId: Long)

/** A todo as a calendar entry: a timed block when it has a time, an all-day entry otherwise. */
data class CalendarEvent(
    val title: String,
    val description: String,
    val date: LocalDate,
    val time: LocalTime?,
    val durationMinutes: Long = 30,
)

/**
 * Writes todos into the phone's own calendar through [CalendarContract], the way the open-source
 * calendar exporters do: one event per todo, the event id kept so a later write updates rather
 * than duplicates it. Timed todos become a block of [CalendarEvent.durationMinutes]; the rest are
 * all-day entries. All of it lives on this device and is not part of backups.
 */
@SuppressLint("MissingPermission") // Every entry below checks [hasPermission] before touching the provider.
class CalendarExporter(private val context: Context, private val settings: SettingsStore) {

    fun hasPermission(): Boolean = required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** The calendars this app may add events to, by account and name. */
    fun calendars(): List<CalendarChoice> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, selection,
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null,
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(CalendarChoice(c.getLong(0), c.getString(1) ?: "日历", c.getString(2).orEmpty(), c.getInt(3)))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** The calendar picked last, if it still exists and can still be written to. */
    fun chosen(): CalendarChoice? {
        val id = settings.string(KEY_CALENDAR)?.toLongOrNull() ?: return null
        return calendars().firstOrNull { it.id == id }
    }

    fun choose(calendar: CalendarChoice) {
        settings.putString(KEY_CALENDAR, calendar.id.toString())
        settings.putString(KEY_CALENDAR_NAME, calendar.name)
    }

    /** The name of the chosen calendar, shown without querying the provider again. */
    fun chosenName(): String? = settings.string(KEY_CALENDAR_NAME)

    fun link(todoId: String): CalendarLink? = settings.string(linkKey(todoId))?.split('|')?.let { p ->
        val calendar = p.getOrNull(0)?.toLongOrNull() ?: return@let null
        val event = p.getOrNull(1)?.toLongOrNull() ?: return@let null
        CalendarLink(calendar, event)
    }

    /**
     * Inserts the todo's event, or updates the one written before. Choosing another calendar
     * moves it: the old event is removed and a new one made. Returns the event id, or null when
     * the provider refused (no permission, a calendar gone meanwhile).
     */
    fun write(todoId: String, calendarId: Long, event: CalendarEvent): Long? {
        if (!hasPermission()) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title.take(200))
            put(CalendarContract.Events.DESCRIPTION, event.description)
            if (event.time != null) {
                val zone = ZoneId.systemDefault()
                val start = event.date.atTime(event.time).atZone(zone).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + event.durationMinutes * 60_000)
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            } else {
                // An all-day event is written in UTC midnight, the convention every calendar app reads.
                put(CalendarContract.Events.DTSTART, event.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, event.date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            }
        }
        return runCatching {
            val old = link(todoId)
            if (old != null && old.calendarId != calendarId) {
                resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, old.eventId), null, null)
                settings.putString(linkKey(todoId), null)
            }
            val update = old?.takeIf { it.calendarId == calendarId }
            if (update != null) {
                val rows = resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, update.eventId), values, null, null)
                if (rows > 0) return@runCatching update.eventId
            }
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return@runCatching null
            val id = uri.lastPathSegment?.toLongOrNull() ?: return@runCatching null
            settings.putString(linkKey(todoId), "$calendarId|$id")
            id
        }.getOrNull()
    }

    /** Takes the todo's event out of the calendar; says whether there was one to take out. */
    fun remove(todoId: String): Boolean {
        val old = link(todoId) ?: return false
        settings.putString(linkKey(todoId), null)
        if (!hasPermission()) return false
        return runCatching {
            context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, old.eventId), null, null) > 0
        }.getOrDefault(false)
    }

    companion object {
        val required = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        private const val KEY_CALENDAR = "calendar_id"
        private const val KEY_CALENDAR_NAME = "calendar_name"
        private fun linkKey(todoId: String) = "calendar_event:$todoId"
    }
}
