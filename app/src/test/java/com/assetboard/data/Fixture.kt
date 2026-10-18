package com.assetboard.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.assetboard.domain.LedgerException
import com.assetboard.domain.calendar.HolidayTable
import com.assetboard.domain.calendar.WorkdayCalendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** An in-memory database with every repository, a settable clock and a fixed time zone. */
class Fixture {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val gate = WriteGate(db, crashOnMissingEpoch = true)
    var now: Long = at("2026-10-20", "12:00")
    var calendar: WorkdayCalendar = HolidayTable.EMPTY
    val changed = mutableListOf<Set<String>>()
    val ledger = LedgerRepository(db, gate, scope, clock = { now })
    val todos = TodoRepository(db, gate, scope, calendar = { calendar }, clock = { now }, zone = { zone }) { changed += it }
    val diary = DiaryRepository(db, gate, clock = { now })
    val settings = SettingsStore(context)
    var effectsFail = false
    val effectCalls = mutableListOf<List<String>>()
    var restoredCount = 0
    val backup = BackupManager(
        context, db, settings, gate, diary,
        calendar = { calendar },
        effects = object : RestoreEffects {
            override suspend fun afterRestore(oldTodoIds: List<String>) {
                effectCalls += oldTodoIds
                if (effectsFail) throw IllegalStateException("模拟重排失败")
            }
        },
        onRestored = { restoredCount++ },
        clock = { now },
    )

    fun at(date: String, time: String = "12:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    fun setNow(date: String, time: String = "12:00") {
        now = at(date, time)
    }

    /** Runs [block] with the epoch bound now, as a freshly opened screen would. */
    fun run(block: suspend CoroutineScope.() -> Unit) = runBlocking(gate.bound(), block)

    fun close() {
        scope.cancel()
        db.close()
    }
}

inline fun expectRule(block: () -> Unit) {
    try {
        block()
        fail("expected LedgerException")
    } catch (e: LedgerException) {
        // expected
    }
}

fun date(s: String): LocalDate = LocalDate.parse(s)
