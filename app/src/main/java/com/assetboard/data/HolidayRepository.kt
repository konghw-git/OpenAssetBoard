package com.assetboard.data

import com.assetboard.domain.AppClock
import android.content.Context
import androidx.room.withTransaction
import com.assetboard.domain.calendar.HolidayTable
import com.assetboard.domain.ensure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** One file of NateScarlet/holiday-cn. An empty [days] list means the year is not published yet. */
@Serializable
private data class HolidayCnFile(val year: Int, val days: List<HolidayCnDay> = emptyList())

@Serializable
private data class HolidayCnDay(val name: String, val date: String, val isOffDay: Boolean)

/**
 * Statutory holidays and make-up workdays: built into the APK for the current and next year,
 * updated weekly from November. A device-local cache, never backed up; it holds no user data, so
 * it is written outside the write gate.
 */
class HolidayRepository(private val context: Context, private val db: AppDatabase) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _table = MutableStateFlow(HolidayTable.EMPTY)
    val table: StateFlow<HolidayTable> = _table.asStateFlow()

    suspend fun init() = withContext(Dispatchers.IO) {
        val known = db.metaDao().holidayYears().map { it.year }.toSet()
        for (name in context.assets.list("holidays").orEmpty()) {
            val year = name.removeSuffix(".json").toIntOrNull() ?: continue
            if (year in known) continue
            val text = context.assets.open("holidays/$name").use { it.readBytes().decodeToString() }
            store(text, source = "builtin")
        }
        reload()
    }

    private suspend fun reload() {
        val years = db.metaDao().holidayYears().map { it.year }.toSet()
        val days = db.metaDao().holidaysBetween("0000", "9999")
            .associate { LocalDate.parse(it.date) to HolidayTable.DayMark(it.name, it.isOffDay) }
        _table.value = HolidayTable(years, days)
    }

    /** Returns false when the file holds no days (not published yet); nothing is written then. */
    private suspend fun store(text: String, source: String): Boolean {
        val file = json.decodeFromString(HolidayCnFile.serializer(), text)
        if (file.days.isEmpty()) return false
        // A single bad date would fail every later read, leaving the whole table empty.
        ensure(file.days.all { runCatching { LocalDate.parse(it.date) }.isSuccess }) { "节假日数据里有无效日期" }
        db.withTransaction {
            db.metaDao().clearHolidayYear(file.year.toString())
            db.metaDao().putHolidays(file.days.map { Holiday(it.date, it.name, it.isOffDay) })
            db.metaDao().putHolidayYear(HolidayYear(file.year, source, AppClock.millis()))
        }
        return true
    }

    /**
     * From November on, looks for next year's schedule (and refreshes the current one). Failures
     * are silent and reported by the return value (whether anything landed); the weekly worker
     * tries again rather than waiting out the six-day window.
     */
    suspend fun updateOnline(today: LocalDate = AppClock.today()): Boolean = withContext(Dispatchers.IO) {
        // November on: next year's schedule, and this year's again, as it can still change.
        val years = buildList {
            if (today.monthValue >= 11) add(today.year + 1)
            add(today.year)
        }
        var changed = false
        for (y in years) {
            val text = runCatching { download("https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$y.json") }.getOrNull() ?: continue
            if (runCatching { store(text, source = "online") }.getOrDefault(false)) changed = true
        }
        if (changed) reload()
        changed
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
