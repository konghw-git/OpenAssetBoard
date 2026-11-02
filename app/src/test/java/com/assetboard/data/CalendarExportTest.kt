package com.assetboard.data

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Writing a todo into the phone calendar: one event, updated in place, moved or removed on request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CalendarExportTest {
    private lateinit var context: Context
    private lateinit var provider: FakeCalendarProvider
    private lateinit var settings: SettingsStore
    private lateinit var exporter: CalendarExporter

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        provider = FakeCalendarProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        settings = SettingsStore(context)
        exporter = CalendarExporter(context, settings)
    }

    private val date = LocalDate.of(2026, 11, 2)

    @Test fun aTimedTodoIsAThirtyMinuteBlockAndWritesAgainInPlace() {
        val calendar = exporter.calendars().first()
        exporter.choose(calendar)
        val start = date.atTime(9, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val id = exporter.write("todo", calendar.id, CalendarEvent("买菜", "清单：周末", date, LocalTime.of(9, 30)))!!
        val values = provider.events.getValue(id)
        assertEquals(start, values.getAsLong(CalendarContract.Events.DTSTART))
        assertEquals(start + 30 * 60_000L, values.getAsLong(CalendarContract.Events.DTEND))
        assertEquals(0, values.getAsInteger(CalendarContract.Events.ALL_DAY))

        // Writing again updates that event rather than making a second one.
        val again = exporter.write("todo", calendar.id, CalendarEvent("周末买菜", "", date, LocalTime.of(9, 30)))!!
        assertEquals(id, again)
        assertEquals(1, provider.events.size)
        assertEquals("周末买菜", provider.events.getValue(id).getAsString(CalendarContract.Events.TITLE))
    }

    @Test fun aTodoWithoutATimeIsAnAllDayEventInUtc() {
        val calendar = exporter.calendars().first()
        val id = exporter.write("todo", calendar.id, CalendarEvent("生日", "", date, null))!!
        val values = provider.events.getValue(id)
        assertEquals(1, values.getAsInteger(CalendarContract.Events.ALL_DAY))
        assertEquals(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), values.getAsLong(CalendarContract.Events.DTSTART))
        assertEquals(date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), values.getAsLong(CalendarContract.Events.DTEND))
        assertEquals("UTC", values.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
    }

    @Test fun choosingAnotherCalendarMovesTheEvent() {
        val calendars = exporter.calendars()
        assertEquals(2, calendars.size)
        val first = calendars[0]
        val second = calendars[1]
        val id = exporter.write("todo", first.id, CalendarEvent("跑步", "", date, LocalTime.of(7, 0)))!!

        val moved = exporter.write("todo", second.id, CalendarEvent("跑步", "", date, LocalTime.of(7, 0)))!!
        assertNull("the event in the first calendar should be gone", provider.events[id])
        assertEquals(1, provider.events.size)
        assertEquals(second.id, provider.events.getValue(moved).getAsLong(CalendarContract.Events.CALENDAR_ID))
        assertEquals(second.id, exporter.link("todo")!!.calendarId)
    }

    @Test fun removingTakesTheEventOutAndForgetsTheLink() {
        val calendar = exporter.calendars().first()
        val id = exporter.write("todo", calendar.id, CalendarEvent("交房租", "", date, null))!!
        assertTrue(exporter.remove("todo"))
        assertNull(provider.events[id])
        assertNull(exporter.link("todo"))
    }

    @Test fun withoutThePermissionNothingIsReadOrWritten() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        assertTrue(exporter.calendars().isEmpty())
        assertNull(exporter.write("todo", 7, CalendarEvent("买菜", "", date, null)))
        assertTrue(provider.events.isEmpty())
    }

}
