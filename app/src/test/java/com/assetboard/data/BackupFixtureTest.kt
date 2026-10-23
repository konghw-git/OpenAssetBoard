package com.assetboard.data

import android.app.Application
import android.net.Uri
import com.assetboard.domain.Habit
import kotlinx.coroutines.plus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Backups as the app really wrote them, one file per backup format, frozen in
 * `src/test/resources/backups/` and never edited afterwards. Every later version must still
 * restore each of them. A test that builds an "old" file by editing today's export would change
 * along with the code and hide exactly the break it is meant to catch.
 *
 * A new format gets its file once: run [writeTheCurrentFormat] with `WRITE_BACKUP_FIXTURE=1`, add
 * the file, and a test for it here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupFixtureTest {
    private lateinit var f: Fixture

    @Before fun setUp() {
        f = Fixture()
        f.settings.setBackupTreeUri(null)
        File(f.context.filesDir, "prerestore").deleteRecursively()
    }

    @After fun tearDown() = f.close()

    private fun fixture(name: String): Uri {
        val text = javaClass.getResourceAsStream("/backups/$name")!!.use { it.readBytes().decodeToString() }
        return Uri.fromFile(File(f.context.cacheDir, name).apply { writeText(text) })
    }

    @Test fun formatTwelveRestoresRemindersAhead() = f.run {
        val uri = fixture("format-12.json")
        assertEquals(12, f.backup.inspect(uri).formatVersion)
        f.backup.restore(uri)
        val todos = f.db.todoDao().allTodos().associateBy { it.title }
        assertEquals(15, todos.getValue("写引言").remindBefore)
        assertEquals("09:30", todos.getValue("写引言").remindTime)
        assertEquals(0, todos.getValue("跑步").remindBefore)
    }

    /** A file from before reminders could go off ahead reads with every reminder at its time. */
    @Test fun formatElevenRemindsAtTheTime() = f.run {
        f.backup.restore(fixture("format-11.json"))
        assertTrue(f.db.todoDao().allTodos().all { it.remindBefore == 0 })
    }

    @Test fun formatElevenRestoresTheRubrics() = f.run {
        val uri = fixture("format-11.json")
        assertEquals(11, f.backup.inspect(uri).formatVersion)
        f.backup.restore(uri)
        val habits = f.db.habitDao().habits().associateBy { it.key }
        assertEquals(listOf("不少于 30 分钟", "出汗"), habits.getValue(Habit.WORKOUT.key).conditions)
        assertEquals("", habits.getValue(Habit.WATER.key).rubric)
        assertEquals(setOf("写引言", "跑步", "进回收站"), f.db.todoDao().allTodos().map { it.title }.toSet())
        assertEquals(2, f.db.foodDao().all().size)
    }

    @Test fun formatTenRestores() = f.run {
        val uri = fixture("format-10.json")
        val preview = f.backup.inspect(uri)
        assertEquals(10, preview.formatVersion)
        f.backup.restore(uri)

        assertEquals(listOf("银行", "基金"), f.db.dao().accounts().map { it.name })
        assertEquals(setOf("写引言", "跑步", "进回收站"), f.db.todoDao().allTodos().map { it.title }.toSet())
        val checklist = f.db.todoDao().allChecklist().sortedBy { it.sortOrder }
        assertEquals(listOf("查文献", "列提纲"), checklist.map { it.text })
        val diary = f.db.diaryDao().all().associateBy { it.date }
        assertEquals("今天写了日记", diary.getValue("2026-10-20").body)
        // The mood, and a day that holds nothing but one.
        assertEquals(4, diary.getValue("2026-10-20").mood)
        assertEquals("", diary.getValue("2026-10-19").body)
        assertEquals(2, diary.getValue("2026-10-19").mood)
        val checks = f.db.habitDao().all().associateBy { it.habit }
        assertEquals(false, checks.getValue(Habit.WORKOUT.key).skipped)
        assertEquals(true, checks.getValue(Habit.WATER.key).skipped)
        assertEquals(2, f.db.foodDao().all().size)
        assertEquals(2, f.db.healthDao().all().size)
        assertEquals(listOf("读书笔记\n第一章讲了复利"), f.db.noteDao().all().map { it.body })
        assertEquals(1, f.db.weekDao().all().size)
        assertEquals(1_000_000_00L, GoalRepository.parse(f.db.metaDao().get(GoalRepository.KEY)))
    }

    /** Writes the current format's file; skipped unless asked for. */
    @Test fun writeTheCurrentFormat() = f.run {
        assumeTrue(System.getenv("WRITE_BACKUP_FIXTURE") == "1")
        val g = f.ledger.createGroup("储蓄", false, 0)
        f.ledger.createAccount(AccountDraft(g, "银行"), 0, 100_00, null)
        val fund = f.ledger.createGroup("投资", false, 1)
        f.ledger.createAccount(AccountDraft(fund, "基金"), 0, 5_000_00, null)
        val list = f.todos.createList("论文")
        val paper = f.todos.create(TodoDraft("写引言", listId = list, planDate = date("2026-10-21"), remindTime = java.time.LocalTime.of(9, 30)))
        f.todos.editInline(paper, "写引言", listOf(InlineItem(null, "查文献"), InlineItem(null, "列提纲")))
        f.todos.setRemindBefore(paper, 15)
        val rep = f.todos.create(TodoDraft("跑步", planDate = date("2026-10-20")))
        f.todos.setRepeat(rep, com.assetboard.domain.todo.RepeatRule(com.assetboard.domain.todo.RepeatType.DAILY))
        f.todos.complete(rep, f.todos.todo(rep)!!.stateSeq)
        f.todos.delete(f.todos.create(TodoDraft("进回收站")))
        f.habits.toggle(Habit.WORKOUT)
        f.habits.toggleSkip(Habit.WATER)
        f.habits.update(Habit.WORKOUT.key, Habit.WORKOUT.label, Habit.WORKOUT.icon, Habit.WORKOUT.color, "不少于 30 分钟\n出汗")
        f.health.saveProfile(Sex.MALE, date("2001-08-02"), 1820, null)
        f.health.save(MeasurementDraft(null, date("2026-10-17"), 92_100, 2_580, "体脂秤"))
        f.health.save(MeasurementDraft(null, date("2026-10-20"), 91_800, null, null))
        f.food.save(FoodDraft(null, date("2026-10-20"), "燕麦奶", 100, 500, FoodUnit.ML, 40_870, true, 600, 1_600, 6_000, 40))
        f.food.save(FoodDraft(null, date("2026-10-19"), null, null, null, null, 650_000, false, null, null, null, null))
        val s = DiarySession(f.diary, date("2026-10-20"), f.scope + f.gate.bound(), clock = { f.now })
        s.open(null)
        s.onInput("今天写了日记")
        s.saveNow()
        s.close()
        f.diary.setMood(date("2026-10-20"), 4)
        f.diary.setMood(date("2026-10-19"), 2)
        f.notes.create("读书笔记\n第一章讲了复利", SourceRef.day(date("2026-10-20")))
        f.db.weekDao().insert(WeeklyReview(newId(), "2026-10-19", "这周按时跑步", bodyRev = 1, createdAt = f.now, updatedAt = f.now))
        GoalRepository(f.db, f.gate).set(1_000_000_00L)
        val out = File("src/test/resources/backups/format-${BackupManager.FORMAT_VERSION}.json")
        out.parentFile!!.mkdirs()
        out.writeText(f.backup.exportJson())
    }
}
