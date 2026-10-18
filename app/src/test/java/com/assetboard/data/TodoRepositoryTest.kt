package com.assetboard.data

import android.app.Application
import com.assetboard.domain.calendar.HolidayTable
import com.assetboard.domain.todo.IntervalUnit
import com.assetboard.domain.todo.ReminderPlan
import com.assetboard.domain.todo.ReminderState
import com.assetboard.domain.todo.RepeatRule
import com.assetboard.domain.todo.RepeatType
import com.assetboard.domain.todo.TodoInvariants
import com.assetboard.domain.todo.TodoView
import com.assetboard.domain.todo.TodoViews
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

/** Section 11 of the design note: groups V10, P, T and N10–N18 against the real repository. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TodoRepositoryTest {
    private lateinit var f: Fixture
    private val repo get() = f.todos

    @Before fun setUp() {
        f = Fixture()
    }

    @After fun tearDown() = f.close()

    private suspend fun get(id: String) = repo.todo(id)!!
    private suspend fun log() = TodoViews.logbook(repo.loadOnce().todos)
    private suspend fun inAnyView(id: String): Boolean {
        val d = repo.loadOnce()
        val today = repo.today()
        val t = d.todoById[id] ?: return false
        return TodoViews.isOpen(t) && (TodoView.entries.filter { it != TodoView.LOGBOOK }.any { TodoViews.matches(it, t, today) } || t.listId != null)
    }

    private suspend fun repeating(rule: RepeatRule, plan: String, deadline: String? = null, remind: String? = null, strong: Boolean = false): String {
        val id = repo.create(TodoDraft("重复", planDate = date(plan), deadline = deadline?.let(::date), remindTime = remind?.let(LocalTime::parse), strongRemind = strong))
        repo.setRepeat(id, rule)
        return id
    }

    private suspend fun completeCurrent(id: String) = repo.complete(id, get(id).stateSeq)
    private suspend fun skipCurrent(id: String) = repo.skip(id, get(id).stateSeq)

    private val daily = RepeatRule(RepeatType.DAILY)
    private fun count(n: Int, base: RepeatRule = daily) = base.copy(count = n)

    // ------------------------------------------------------------ P: repeat, tokens, undo

    @Test fun p1_monthlyDoesNotMakeUpMissedPeriods() = f.run {
        val id = repeating(RepeatRule(RepeatType.MONTHLY_DAY, day = 15), "2026-09-15")
        f.setNow("2026-10-20")
        completeCurrent(id)
        assertEquals("2026-11-15", get(id).planDate)
        assertEquals(1, log().size)
    }

    @Test fun p2_afterCompletionInterval() = f.run {
        val id = repeating(RepeatRule(RepeatType.AFTER_COMPLETION, interval = 90, unit = IntervalUnit.DAY), "2026-10-20")
        completeCurrent(id)
        assertEquals("2027-01-18", get(id).planDate)
    }

    @Test fun p3_day31FallsOnMonthEnd() = f.run {
        f.setNow("2026-10-31")
        val id = repeating(RepeatRule(RepeatType.MONTHLY_DAY, day = 31), "2026-10-31")
        completeCurrent(id)
        assertEquals("2026-11-30", get(id).planDate)
    }

    @Test fun p4_weeklyShiftsDeadlineAndResetsChecklist() = f.run {
        val id = repeating(RepeatRule(RepeatType.WEEKLY, weekdays = listOf(2)), "2026-10-20", deadline = "2026-10-22")
        val item = repo.addChecklistItem(id, "带钥匙")
        repo.toggleChecklist(item)
        completeCurrent(id)
        val t = get(id)
        assertEquals("2026-10-27", t.planDate)
        assertEquals("2026-10-29", t.deadline)
        assertFalse(f.db.todoDao().checklistOf(id).single().done)
        // The record keeps the period's checklist as it was.
        val record = log().single()
        assertTrue(f.db.todoDao().checklistOf(record.id).single().done)
    }

    @Test fun p5_workdayFollowsTheOfficialSchedule() = f.run {
        f.calendar = HolidayTable(
            setOf(2026),
            mapOf(
                date("2026-10-26") to HolidayTable.DayMark("测试假期", isOffDay = true),
                date("2026-10-31") to HolidayTable.DayMark("调休", isOffDay = false),
            ),
        )
        f.setNow("2026-10-23")
        val id = repeating(RepeatRule(RepeatType.WORKDAY), "2026-10-23")
        completeCurrent(id)
        assertEquals("2026-10-27", get(id).planDate)
        val id2 = repeating(RepeatRule(RepeatType.WORKDAY), "2026-10-30")
        f.setNow("2026-10-30")
        completeCurrent(id2)
        assertEquals("2026-10-31", get(id2).planDate)
    }

    @Test fun p7_secondCompletionWithAnOldTokenIsRejected() = f.run {
        val id = repeating(daily, "2026-10-20")
        val oldSeq = get(id).stateSeq
        repo.complete(id, oldSeq) // the widget
        expectRule { repo.complete(id, oldSeq) } // the old notification
        assertEquals("2026-10-21", get(id).planDate)
        assertTrue(TodoViews.isOpen(get(id)))
        assertEquals(1, log().size)
    }

    @Test fun p8_undoRestoresEverything() = f.run {
        val id = repeating(daily, "2026-10-20")
        val item = repo.addChecklistItem(id, "a")
        repo.toggleChecklist(item)
        val record = completeCurrent(id)
        val seqBeforeUndo = get(id).stateSeq
        assertEquals(UndoOutcome.RESTORED, repo.uncomplete(record))
        val t = get(id)
        assertEquals("2026-10-20", t.planDate)
        assertEquals(0, t.occurrenceCount)
        assertTrue(f.db.todoDao().checklistOf(id).single().done)
        assertNull(repo.todo(record))
        assertTrue(t.stateSeq > seqBeforeUndo)
    }

    @Test fun p9_undoAfterTheNextPeriodWasEditedOnlyDeletesTheRecord() = f.run {
        val id = repeating(daily, "2026-10-20")
        val record = completeCurrent(id)
        repo.setPlan(id, date("2026-10-25"), null)
        assertEquals(UndoOutcome.NEEDS_CONFIRM, repo.uncomplete(record))
        assertNotNull(repo.todo(record))
        assertEquals(UndoOutcome.RECORD_DELETED, repo.uncomplete(record, deleteOnly = true))
        assertEquals("2026-10-25", get(id).planDate)
        assertEquals(1, get(id).occurrenceCount)
    }

    @Test fun p10_threeTimesCompleteSkipComplete() = f.run {
        val id = repeating(count(3), "2026-10-20")
        completeCurrent(id)
        skipCurrent(id)
        completeCurrent(id)
        assertEquals(2, log().size)
        assertNotNull(get(id).repeatEndedAt)
        assertFalse(inAnyView(id))
    }

    @Test fun p11_threeTimesCompleteCompleteSkip() = f.run {
        val id = repeating(count(3), "2026-10-20")
        completeCurrent(id)
        completeCurrent(id)
        skipCurrent(id)
        assertEquals(2, log().size)
        assertNotNull(get(id).repeatEndedAt)
    }

    @Test fun p12_earlyCompletionKeepsTheDateButNotTheToken() = f.run {
        f.setNow("2026-10-19")
        val id = repeating(RepeatRule(RepeatType.AFTER_COMPLETION, interval = 1), "2026-10-20")
        val token = get(id).stateSeq
        repo.complete(id, token)
        assertEquals("2026-10-20", get(id).planDate)
        expectRule { repo.complete(id, token) }
        assertEquals(1, log().size)
        assertEquals(1, get(id).occurrenceCount)
    }

    @Test fun p13_undoingTheLastPeriodReopensTheSeries() = f.run {
        val id = repeating(count(3), "2026-10-20")
        completeCurrent(id)
        skipCurrent(id)
        val third = completeCurrent(id)
        assertNotNull(get(id).repeatEndedAt)
        assertEquals(UndoOutcome.RESTORED, repo.uncomplete(third))
        val t = get(id)
        assertNull(t.repeatEndedAt)
        assertEquals("2026-10-22", t.planDate)
        assertEquals(1, log().size)
        assertTrue(inAnyView(id))
    }

    @Test fun p14_buttonsFromBeforeAnUndoAreRejected() = f.run {
        val id = repeating(daily, "2026-10-20")
        val record = completeCurrent(id)
        val issued = get(id).stateSeq
        repo.uncomplete(record)
        expectRule { repo.snooze(id, issued, 10) }
        expectRule { repo.complete(id, issued) }
    }

    @Test fun p15_undoSkip() = f.run {
        val id = repeating(daily, "2026-10-20")
        val undo = skipCurrent(id)
        assertEquals("2026-10-21", get(id).planDate)
        assertTrue(repo.undoSkip(undo))
        assertEquals("2026-10-20", get(id).planDate)
        assertEquals(0, get(id).occurrenceCount)
        assertTrue(log().isEmpty())
    }

    @Test fun p16_repeatSourceCannotBeSomeday() = f.run {
        val id = repeating(daily, "2026-10-20")
        expectRule { repo.setSomeday(id, true) }
    }

    @Test fun p17_recordOfAPurgedSourceStaysARecord() = f.run {
        val id = repeating(daily, "2026-10-20")
        val r1 = completeCurrent(id)
        completeCurrent(id)
        repo.purgeBatch(repo.delete(id))
        assertNull(repo.todo(id))
        assertEquals(UndoOutcome.NEEDS_CONFIRM, repo.uncomplete(r1))
        val rec = get(r1)
        assertEquals(TodoKind.COMPLETION, rec.kind)
        assertNull(rec.repeatSourceId)
        assertFalse(TodoViews.isOpen(rec))
        assertEquals(2, log().size)
    }

    @Test fun p18_undoAfterCompletingWhileSnoozed() = f.run {
        val id = repeating(daily, "2026-10-20", remind = "09:00")
        repo.snooze(id, get(id).stateSeq, 10)
        val record = completeCurrent(id)
        repo.uncomplete(record)
        val t = get(id)
        assertEquals("2026-10-20", t.planDate)
        assertEquals(0, t.occurrenceCount)
        assertNull(t.snoozeAt)
    }

    @Test fun p19_invalidRulesAreRejectedByRepositoryAndImport() = f.run {
        val id = repo.create(TodoDraft("x", planDate = date("2026-10-20")))
        val bad = listOf(
            RepeatRule(RepeatType.DAILY, interval = 0),
            RepeatRule(RepeatType.WEEKLY, weekdays = emptyList()),
            RepeatRule(RepeatType.MONTHLY_DAY, day = 32),
            RepeatRule(RepeatType.DAILY, count = 0),
        )
        for (r in bad) expectRule { repo.setRepeat(id, r) }
        assertEquals(TodoKind.ORDINARY, get(id).kind)
        for (r in bad) {
            val row = get(id).copy(kind = TodoKind.REPEAT_SOURCE, repeatRule = r.encode())
            expectRule { TodoInvariants.checkAll(emptyList(), emptyList(), listOf(row), emptyList(), emptyList(), emptyList(), f.calendar) }
        }
    }

    @Test fun p21_quickAddedTodoBecomesARepeatSource() = f.run {
        val id = repo.create(TodoDraft("倒垃圾", planDate = date("2026-10-20")))
        repo.setRepeat(id, daily)
        val t = get(id)
        assertEquals(TodoKind.REPEAT_SOURCE, t.kind)
        assertEquals("2026-10-20", t.planDate)
        assertEquals(0, t.occurrenceCount)
    }

    @Test fun p22_removingRepetitionKeepsTheCurrentPeriod() = f.run {
        val id = repeating(daily, "2026-10-20")
        val r1 = completeCurrent(id)
        completeCurrent(id)
        repo.setRepeat(id, null)
        val t = get(id)
        assertEquals(TodoKind.ORDINARY, t.kind)
        assertEquals("2026-10-22", t.planDate)
        assertEquals(2, log().size)
        assertEquals(UndoOutcome.NEEDS_CONFIRM, repo.uncomplete(r1))
    }

    @Test fun p23_repeatNeedsAPlanDate() = f.run {
        val id = repo.create(TodoDraft("没日期"))
        expectRule { repo.setRepeat(id, daily) }
    }

    @Test fun p24_countStartsWhenTheRuleIsSet() = f.run {
        val id = repeating(daily, "2026-10-20")
        completeCurrent(id)
        completeCurrent(id)
        repo.setRepeat(id, null)
        repo.setRepeat(id, count(3))
        assertEquals(2, RepeatRule.decode(get(id).repeatRule!!).countBase)
        repeat(3) { completeCurrent(id) }
        assertNotNull(get(id).repeatEndedAt)
        assertEquals(5, get(id).occurrenceCount)
        assertEquals(5, log().size)
    }

    @Test fun p25_editingTheRuleKeepsTheCountBase() = f.run {
        val id = repeating(count(3), "2026-10-20")
        completeCurrent(id)
        repo.setRepeat(id, count(3, RepeatRule(RepeatType.WEEKLY, weekdays = listOf(1, 3, 5))))
        assertEquals(0, RepeatRule.decode(get(id).repeatRule!!).countBase)
        completeCurrent(id)
        assertNull(get(id).repeatEndedAt)
        completeCurrent(id)
        assertNotNull(get(id).repeatEndedAt)
    }

    @Test fun p26_countNotAboveWhatAlreadyRanIsRejected() = f.run {
        val id = repeating(count(3), "2026-10-20")
        completeCurrent(id)
        completeCurrent(id)
        expectRule { repo.setRepeat(id, count(2)) }
        expectRule { repo.setRepeat(id, count(1)) }
        repo.setRepeat(id, count(4))
    }

    // ------------------------------------------------------------ V10

    @Test fun v10_repeatSourceNeverEntersTheLog() = f.run {
        val id = repeating(daily, "2026-10-20")
        completeCurrent(id)
        val logged = log()
        assertEquals(1, logged.size)
        assertTrue(logged.none { it.id == id })
        val completedToday = f.db.todoDao().completedBetween(f.at("2026-10-20", "00:00"), f.at("2026-10-21", "00:00"))
        assertEquals(1, completedToday.size)
    }

    // ------------------------------------------------------------ T: trash

    @Test fun t1_deleteAndRestoreAList() = f.run {
        val list = repo.createList("论文")
        val ids = (1..5).map { repo.create(TodoDraft("t$it", listId = list, planDate = date("2026-10-21"), remindTime = LocalTime.of(9, 0))) }
        val batch = repo.deleteList(list)
        assertTrue(repo.loadOnce().todos.none { it.id in ids })
        f.changed.clear()
        repo.restoreBatch(batch)
        val d = repo.loadOnce()
        assertTrue(ids.all { d.todoById[it]?.listId == list })
        assertTrue(f.changed.flatten().containsAll(ids))
    }

    @Test fun t2_earlierDeletionKeepsItsOwnBatch() = f.run {
        val list = repo.createList("L")
        val a = repo.create(TodoDraft("A", listId = list))
        repo.create(TodoDraft("B", listId = list))
        repo.delete(a)
        val aDeletedAt = get(a).deletedAt
        f.now += 60_000
        repo.restoreBatch(repo.deleteList(list))
        assertNotNull(get(a).deletedAt)
        assertEquals(aDeletedAt, get(a).deletedAt)
    }

    @Test fun t3_t4_restoringOneFromADeletedListGoesToTheInbox() = f.run {
        val list = repo.createList("L")
        val a = repo.create(TodoDraft("A", listId = list))
        val b = repo.create(TodoDraft("B", listId = list))
        repo.deleteList(list)
        repo.restoreTodo(a)
        assertNull(get(a).listId)
        assertTrue(TodoViews.inInbox(get(a)))
        assertNotNull(get(b).deletedAt)
        f.now += 31L * 24 * 3600 * 1000
        repo.purgeExpired()
        assertNull(repo.todo(b))
        assertNull(f.db.todoDao().list(list))
        assertNull(get(a).deletedAt)
    }

    @Test fun t5_sectionOfADeletedListCannotBeRestored() = f.run {
        val list = repo.createList("L")
        val s = repo.createSection(list, "S")
        repo.deleteList(list)
        expectRule { repo.restoreSection(s) }
    }

    @Test fun t7_purgingAListKeepsTodosOfOtherBatches() = f.run {
        val list = repo.createList("L")
        val a = repo.create(TodoDraft("A", listId = list))
        repo.delete(a)
        repo.purgeBatch(repo.deleteList(list))
        val row = get(a)
        assertNotNull(row.deletedAt)
        assertNull(row.listId)
        repo.restoreTodo(a)
        assertTrue(TodoViews.inInbox(get(a)))
    }

    @Test fun t8_purgingASourceKeepsItsRecords() = f.run {
        val id = repeating(daily, "2026-10-20")
        val records = (1..3).map { completeCurrent(id) }
        repo.purgeBatch(repo.delete(id))
        assertEquals(3, log().size)
        for (r in records) {
            val row = get(r)
            assertNull(row.repeatSourceId)
            assertEquals(TodoKind.COMPLETION, row.kind)
        }
        assertEquals(UndoOutcome.NEEDS_CONFIRM, repo.uncomplete(records.last()))
    }

    @Test fun t9_purgingAListTakesItsEarlierDeletedSections() = f.run {
        val list = repo.createList("L")
        val s = repo.createSection(list, "S")
        val inS = repo.create(TodoDraft("in S", listId = list, sectionId = s))
        repo.deleteSection(s)
        val batch = repo.deleteList(list)
        assertEquals(1, repo.sectionsPurgedAlongside(batch))
        repo.purgeBatch(batch)
        assertNull(f.db.todoDao().section(s))
        val row = get(inS)
        assertNotNull(row.deletedAt)
        assertNull(row.sectionId)
        repo.restoreTodo(inS)
        assertTrue(TodoViews.inInbox(get(inS)))
    }

    @Test fun t10_restoreKeepsThePlanDate() = f.run {
        f.setNow("2026-10-10")
        val id = repeating(RepeatRule(RepeatType.MONTHLY_DAY, day = 15), "2026-10-15")
        val batch = repo.delete(id)
        f.setNow("2026-10-20")
        repo.restoreBatch(batch)
        val t = get(id)
        assertEquals("2026-10-15", t.planDate)
        assertEquals(0, t.occurrenceCount)
        assertTrue(TodoViews.isOverdue(t, repo.today()))
        assertTrue(log().isEmpty())
    }

    @Test fun t11_restoredEndedSourceStaysEnded() = f.run {
        val id = repeating(count(1), "2026-10-20")
        completeCurrent(id)
        repo.restoreBatch(repo.delete(id))
        assertNotNull(get(id).repeatEndedAt)
        assertFalse(inAnyView(id))
    }

    // ------------------------------------------------------------ N: reminder state through the repository

    private suspend fun plan(id: String) = ReminderState.plan(get(id), f.now, f.zone)

    private suspend fun fired(id: String) = repo.markReminded(id, get(id).stateSeq)

    @Test fun n2_completingClearsTheSnooze() = f.run {
        val id = repo.create(TodoDraft("x", planDate = date("2026-10-20"), remindTime = LocalTime.of(13, 0)))
        repo.snooze(id, get(id).stateSeq, 10)
        repo.complete(id, get(id).stateSeq)
        assertNull(get(id).snoozeAt)
        assertEquals(ReminderPlan.None, plan(id))
    }

    @Test fun n3_n4_tomorrow() = f.run {
        f.setNow("2026-10-20", "09:00")
        val src = repeating(daily, "2026-10-20", remind = "09:00")
        repo.snoozeTomorrow(src, get(src).stateSeq)
        assertEquals("2026-10-20", get(src).planDate)
        assertEquals(f.at("2026-10-21", "09:00"), get(src).snoozeAt)
        val plain = repo.create(TodoDraft("x", planDate = date("2026-10-20"), remindTime = LocalTime.of(10, 0)))
        repo.snoozeTomorrow(plain, get(plain).stateSeq)
        assertEquals("2026-10-21", get(plain).planDate)
    }

    @Test fun n5_editingTheTimeInvalidatesOldButtons() = f.run {
        val id = repo.create(TodoDraft("x", planDate = date("2026-10-20"), remindTime = LocalTime.of(13, 0)))
        val issued = get(id).stateSeq
        repo.setPlan(id, date("2026-10-20"), LocalTime.of(14, 0))
        expectRule { repo.snooze(id, issued, 10) }
    }

    @Test fun n10_newPeriodAlreadyDueIsDeliveredLate() = f.run {
        f.setNow("2026-10-19", "09:00")
        val id = repeating(daily, "2026-10-19", remind = "09:00")
        fired(id)
        repo.snoozeTomorrow(id, get(id).stateSeq)
        f.setNow("2026-10-20", "09:00")
        fired(id) // the snooze, today 09:00
        f.setNow("2026-10-20", "09:05")
        completeCurrent(id) // yesterday's period
        assertEquals("2026-10-20", get(id).planDate)
        val p = plan(id)
        assertTrue("expected a late delivery, got $p", p is ReminderPlan.Late && p.lateMinutes == 5L)
    }

    @Test fun n11_undoDoesNotRemindAgain() = f.run {
        f.setNow("2026-10-20", "09:00")
        val id = repeating(daily, "2026-10-20", remind = "09:00")
        fired(id)
        f.setNow("2026-10-20", "10:00")
        repo.uncomplete(completeCurrent(id))
        assertEquals(ReminderPlan.None, plan(id))
    }

    @Test fun n12_timeMovedIntoThePastDoesNotFire() = f.run {
        val id = repo.create(TodoDraft("x", planDate = date("2026-10-20"), remindTime = LocalTime.of(15, 0)))
        repo.setPlan(id, date("2026-10-20"), LocalTime.of(11, 0))
        assertEquals(ReminderPlan.None, plan(id))
    }

    @Test fun n13_deleteAndRestoreDoesNotRemindAgain() = f.run {
        f.setNow("2026-10-20", "09:00")
        val id = repo.create(TodoDraft("x", planDate = date("2026-10-20"), remindTime = LocalTime.of(9, 0)))
        // Created at 09:00 sharp counts as past: reminded already. Use a later time.
        repo.setPlan(id, date("2026-10-20"), LocalTime.of(9, 30))
        f.setNow("2026-10-20", "09:30")
        fired(id)
        repo.restoreBatch(repo.delete(id))
        assertEquals(ReminderPlan.None, plan(id))
    }

    private suspend fun firedAtNineAndTen(): String {
        f.setNow("2026-10-20", "08:00")
        val id = repeating(daily, "2026-10-20", remind = "09:00")
        f.setNow("2026-10-20", "09:00")
        fired(id)
        repo.snooze(id, get(id).stateSeq, 60)
        f.setNow("2026-10-20", "10:00")
        fired(id)
        return id
    }

    @Test fun n14_consumedSnoozeStaysConsumedAfterUndo() = f.run {
        val id = firedAtNineAndTen()
        repo.uncomplete(completeCurrent(id))
        assertEquals(ReminderPlan.None, plan(id))
    }

    @Test fun n15_consumedSnoozeStaysConsumedAfterRestore() = f.run {
        val id = firedAtNineAndTen()
        repo.restoreBatch(repo.delete(id))
        assertEquals(ReminderPlan.None, plan(id))
    }

    @Test fun n16_pendingSnoozeIsVoidedByCompleteAndUndo() = f.run {
        f.setNow("2026-10-20", "08:00")
        val id = repeating(daily, "2026-10-20", remind = "09:00")
        f.setNow("2026-10-20", "09:00")
        fired(id)
        repo.snooze(id, get(id).stateSeq, 60)
        f.setNow("2026-10-20", "09:30")
        repo.uncomplete(completeCurrent(id))
        assertNull(get(id).snoozeAt)
        assertEquals(ReminderPlan.None, plan(id))
    }

    @Test fun n17_movingTheTimeLaterRemindsOnceMore() = f.run {
        f.setNow("2026-10-20", "08:00")
        val id = repo.create(TodoDraft("x", planDate = date("2026-10-20"), remindTime = LocalTime.of(9, 0)))
        f.setNow("2026-10-20", "09:00")
        fired(id)
        repo.setPlan(id, date("2026-10-20"), LocalTime.of(15, 0))
        assertEquals(ReminderPlan.At(f.at("2026-10-20", "15:00"), snooze = false), plan(id))
    }

    @Test fun n18_ordinaryTomorrowRemindsTomorrow() = f.run {
        f.setNow("2026-10-20", "08:00")
        val id = repo.create(TodoDraft("x", planDate = date("2026-10-20"), remindTime = LocalTime.of(9, 0)))
        f.setNow("2026-10-20", "09:00")
        fired(id)
        repo.snoozeTomorrow(id, get(id).stateSeq)
        assertEquals(ReminderPlan.At(f.at("2026-10-21", "09:00"), snooze = false), plan(id))
    }

    @Test fun deletedTodoHasNoReminder() = f.run {
        val id = repo.create(TodoDraft("x", planDate = date("2026-10-20"), remindTime = LocalTime.of(15, 0)))
        f.changed.clear()
        repo.delete(id)
        assertTrue(f.changed.flatten().contains(id))
        assertEquals(ReminderPlan.None, plan(id))
    }

    @Test fun sectionMustBelongToTheList() = f.run {
        val l1 = repo.createList("1")
        val l2 = repo.createList("2")
        val s = repo.createSection(l1, "S")
        val id = repo.create(TodoDraft("x", listId = l2, sectionId = s))
        assertNull(get(id).sectionId)
        val row = get(id).copy(sectionId = s)
        expectRule {
            TodoInvariants.checkTodoRefs(row, mapOf(l1 to f.db.todoDao().list(l1)!!, l2 to f.db.todoDao().list(l2)!!), mapOf(s to f.db.todoDao().section(s)!!), emptyMap())
        }
    }
}
