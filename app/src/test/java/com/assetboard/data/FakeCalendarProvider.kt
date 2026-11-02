package com.assetboard.data

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract

/** The provider side of [CalendarContract], in memory: calendars to list, events to keep. */
internal class FakeCalendarProvider : ContentProvider() {
    val events = mutableMapOf<Long, ContentValues>()
    private var next = 1L
    private val calendars = listOf(
        7L to 0xFF3D7A5B.toInt(),
        8L to 0xFF6289CF.toInt(),
    )

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        val cursor = MatrixCursor(columns)
        if (uri.toString().startsWith(CalendarContract.Calendars.CONTENT_URI.toString())) {
            calendars.forEachIndexed { i, (id, color) ->
                cursor.addRow(arrayOf<Any>(id, "日历${i + 1}", "local", color))
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val id = next++
        events[id] = ContentValues(values)
        return ContentUris.withAppendedId(uri, id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val id = ContentUris.parseId(uri)
        val row = events[id] ?: return 0
        row.putAll(values)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val id = ContentUris.parseId(uri)
        return if (events.remove(id) != null) 1 else 0
    }

    override fun getType(uri: Uri): String? = null
}
