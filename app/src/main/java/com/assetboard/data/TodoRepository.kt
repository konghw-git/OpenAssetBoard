package com.assetboard.data

import com.assetboard.domain.LedgerException
import com.assetboard.domain.calendar.WorkdayCalendar
import com.assetboard.domain.ensure
import com.assetboard.domain.todo.Recurrence
import com.assetboard.domain.todo.ReminderState
import com.assetboard.domain.todo.RepeatRule
import com.assetboard.domain.todo.TodoInvariants
import com.assetboard.domain.todo.TodoViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Everything the todo screens and the widget need, in memory. */
data class TodoData(
    val lists: List<TodoList>,
    val sections: List<TodoSection>,
    /** Undeleted rows of every kind, completion records included. */
    val todos: List<Todo>,
    val checklist: List<ChecklistItem>,
) {
    val listById = lists.associateBy { it.id }
    val sectionById = sections.associateBy { it.id }
    val todoById = todos.associateBy { it.id }
    val checklistByTodo = checklist.groupBy { it.todoId }
}

/** A deleted batch, as the trash lists it. */
data class TrashBatch(
    val batch: String,
    val deletedAt: Long,
    val lists: List<TodoList>,
    val sections: List<TodoSection>,
    val todos: List<Todo>,
    val diary: List<DiaryEntry>,
    val notes: List<Note> = emptyList(),
    val weeks: List<WeeklyReview> = emptyList(),
)

/** The source's state before a period advanced it; restored by undo. */
@Serializable
data class UndoBefore(
    val planDate: String?,
    val deadline: String?,
    val checklist: Map<String, Boolean>,
    val occurrenceCount: Int,
    val remindedPeriod: Int?,
    val ended: Boolean,
) {
    fun encode(): String = codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { encodeDefaults = true }
        fun decode(s: String): UndoBefore = codec.decodeFromString(serializer(), s)
    }
}

/** Held in memory for the 5-second undo bar after a skip. */
data class SkipUndo(val sourceId: String, val before: UndoBefore, val revisionAfter: Int)

enum class UndoOutcome { RESTORED, NEEDS_CONFIRM, RECORD_DELETED }

/** A notification, widget or list action whose todo has moved on since it was issued. */
class StaleAction : LedgerException("这条已处理过")

data class TodoDraft(
    val title: String,
    val notes: String = "",
    val listId: String? = null,
    val sectionId: String? = null,
    val planDate: LocalDate? = null,
    val remindTime: LocalTime? = null,
    val strongRemind: Boolean = false,
    val someday: Boolean = false,
    val deadline: LocalDate? = null,
    val important: Boolean = false,
)

/** A checklist line as an edit in place left it: [id] for an item that was there, null for a new one. */
data class InlineItem(val id: String?, val text: String)

/** The affected checklists before a move, guarded against edits made after it. */
data class ChecklistMoveUndo(val before: List<ChecklistItem>, val revisionsAfter: Map<String, Int>)

/** What a reschedule does to the time of day: keeps it, clears it or sets it. */
sealed interface TimeChange {
    data object Keep : TimeChange
    data object Clear : TimeChange
    data class At(val time: LocalTime) : TimeChange
}

/**
 * Every todo write: validated against [TodoInvariants], one transaction under the [WriteGate],
 * then [onChanged] gets the ids whose reminders may have changed.
 */
class TodoRepository(
    private val db: AppDatabase,
    private val gate: WriteGate,
    scope: CoroutineScope,
    private val calendar: () -> WorkdayCalendar,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val onChanged: (Set<String>) -> Unit = {},
) {
    private val dao = db.todoDao()

    val data: StateFlow<TodoData?> = combine(dao.observeLists(), dao.observeSections(), dao.observeTodos(), dao.observeChecklist(), ::TodoData)
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, null)

    // Six sources: past the fixed-arity overloads of combine, so the list form, cast back per index.
    @Suppress("UNCHECKED_CAST")
    val trash: Flow<List<TrashBatch>> = combine(
        listOf(
            dao.observeDeletedLists(), dao.observeDeletedSections(), dao.observeDeletedTodos(),
            db.diaryDao().observeDeleted(), db.noteDao().observeDeleted(), db.weekDao().observeDeleted(),
        ),
    ) { arr ->
        val l = arr[0] as List<TodoList>
        val s = arr[1] as List<TodoSection>
        val t = arr[2] as List<Todo>
        val d = arr[3] as List<DiaryEntry>
        val n = arr[4] as List<Note>
        val w = arr[5] as List<WeeklyReview>
        val batches = (l.map { it.deleteBatch!! to it.deletedAt!! } + s.map { it.deleteBatch!! to it.deletedAt!! } +
            t.map { it.deleteBatch!! to it.deletedAt!! } + d.map { it.deleteBatch!! to it.deletedAt!! } + n.map { it.deleteBatch!! to it.deletedAt!! } +
            w.map { it.deleteBatch!! to it.deletedAt!! })
            .groupBy({ it.first }, { it.second })
        batches.map { (b, at) ->
            TrashBatch(
                b, at.max(), l.filter { it.deleteBatch == b }, s.filter { it.deleteBatch == b }, t.filter { it.deleteBatch == b },
                d.filter { it.deleteBatch == b }, n.filter { it.deleteBatch == b }, w.filter { it.deleteBatch == b },
            )
        }.sortedByDescending { it.deletedAt }
    }.flowOn(Dispatchers.Default)

    suspend fun loadOnce(): TodoData = TodoData(dao.liveLists(), dao.liveSections(), dao.allLiveTodos(), dao.allChecklist())

    suspend fun todo(id: String): Todo? = dao.todo(id)

    fun today(): LocalDate = TodoViews.localDate(clock(), zone())

    // ------------------------------------------------------------ plumbing

    private inner class Tx {
        val changed = mutableSetOf<String>()
        val now = clock()
        val today: LocalDate = TodoViews.localDate(now, zone())

        suspend fun save(t: Todo) {
            TodoInvariants.checkTodo(t, calendar())
            TodoInvariants.checkTodoRefs(
                t,
                listOfNotNull(t.listId?.let { dao.list(it) }).associateBy { it.id },
                listOfNotNull(t.sectionId?.let { dao.section(it) }).associateBy { it.id },
                listOfNotNull(t.repeatSourceId?.let { dao.todo(it) }).associateBy { it.id },
            )
            dao.updateTodo(t)
            changed += t.id
        }

        suspend fun insert(t: Todo) {
            TodoInvariants.checkTodo(t, calendar())
            dao.insertTodo(t)
            changed += t.id
        }
    }

    private suspend fun <T> tx(block: suspend Tx.() -> T): T {
        val tx = Tx()
        val result = gate.write { tx.block() }
        if (tx.changed.isNotEmpty()) onChanged(tx.changed)
        return result
    }

    private suspend fun live(id: String): Todo = dao.todo(id)?.takeIf { it.deletedAt == null } ?: throw LedgerException("待办不存在或已删除")

    /** Token check for actions issued earlier (notification, widget, a list row). */
    private suspend fun token(id: String, seq: Long?): Todo {
        val t = dao.todo(id) ?: throw StaleAction()
        if (t.deletedAt != null) throw StaleAction()
        if (seq != null && t.stateSeq != seq) throw StaleAction()
        return t
    }

    private fun Todo.bump(seq: Boolean = true) = copy(revision = revision + 1, stateSeq = if (seq) stateSeq + 1 else stateSeq)

    // ------------------------------------------------------------ create & edit

    suspend fun create(draft: TodoDraft): String = tx {
        ensure(draft.title.isNotBlank()) { "标题不能为空" }
        if (draft.listId != null) ensure(dao.list(draft.listId)?.deletedAt == null) { "清单不存在" }
        val section = draft.sectionId?.let { dao.section(it) }
        val t = Todo(
            id = newId(),
            title = draft.title.trim(),
            notes = draft.notes,
            listId = draft.listId,
            sectionId = section?.takeIf { it.listId == draft.listId && it.deletedAt == null }?.id,
            planDate = draft.planDate?.toString().takeIf { !draft.someday },
            remindTime = draft.remindTime?.toString().takeIf { draft.planDate != null && !draft.someday },
            strongRemind = draft.strongRemind,
            someday = draft.someday,
            deadline = draft.deadline?.toString(),
            important = draft.important,
            sortOrder = dao.maxTodoOrder() + 1,
            createdAt = now,
        )
        insert(ReminderState.afterManualEdit(t, now, zone()))
        t.id
    }

    suspend fun setTitle(id: String, title: String) = tx {
        ensure(title.isNotBlank()) { "标题不能为空" }
        val t = live(id)
        if (t.title != title.trim()) save(t.copy(title = title.trim()).bump(seq = false))
    }

    suspend fun setNotes(id: String, notes: String) = tx {
        val t = live(id)
        if (t.notes != notes) save(t.copy(notes = notes).bump(seq = false))
    }

    suspend fun setImportant(id: String, important: Boolean) = tx {
        val t = live(id)
        if (t.important != important) save(t.copy(important = important).bump(seq = false))
    }

    /** Plan date and reminder time; setting a date clears "someday". */
    suspend fun setPlan(id: String, date: LocalDate?, remindTime: LocalTime?, expectedSeq: Long? = null) = tx {
        val t = if (expectedSeq != null) token(id, expectedSeq) else live(id)
        ensure(t.kind != TodoKind.COMPLETION) { "完成记录不能修改" }
        if (t.kind == TodoKind.REPEAT_SOURCE) ensure(date != null) { "重复待办必须有计划日" }
        val next = t.copy(
            planDate = date?.toString(),
            remindTime = remindTime?.toString()?.takeIf { date != null },
            someday = if (date != null) false else t.someday,
        ).keepingAdvance()
        if (next.planDate == t.planDate && next.remindTime == t.remindTime && next.someday == t.someday) return@tx
        save(ReminderState.afterManualEdit(next, now, zone()).bump())
    }

    /** How far ahead of its time the reminder goes off: one of [ReminderState.ADVANCE_CHOICES]. */
    suspend fun setRemindBefore(id: String, minutes: Int) = tx {
        val t = live(id)
        ensure(t.kind != TodoKind.COMPLETION) { "完成记录不能修改" }
        ensure(minutes in 0..ReminderState.MAX_ADVANCE_MINUTES) { "提前提醒不合法" }
        ensure(minutes == 0 || t.remindTime != null) { "先设置提醒时刻，才能提前提醒" }
        if (t.remindBefore == minutes) return@tx
        save(ReminderState.afterManualEdit(t.copy(remindBefore = minutes), now, zone()).bump())
    }

    /** No reminder time, nothing to remind ahead of. */
    private fun Todo.keepingAdvance() = if (remindTime == null) copy(remindBefore = 0) else this

    suspend fun setStrongRemind(id: String, strong: Boolean) = tx {
        val t = live(id)
        if (t.strongRemind != strong) save(t.copy(strongRemind = strong).bump())
    }

    suspend fun setDeadline(id: String, deadline: LocalDate?) = tx {
        val t = live(id)
        ensure(t.kind != TodoKind.COMPLETION) { "完成记录不能修改" }
        if (t.deadline != deadline?.toString()) save(t.copy(deadline = deadline?.toString()).bump())
    }

    suspend fun setSomeday(id: String, someday: Boolean) = tx {
        val t = live(id)
        ensure(t.kind == TodoKind.ORDINARY) {
            if (t.kind == TodoKind.REPEAT_SOURCE) "重复待办可以跳过本期或删除，不能设为以后再说" else "完成记录不能修改"
        }
        if (t.someday == someday) return@tx
        val next = if (someday) t.copy(someday = true, planDate = null, remindTime = null, remindBefore = 0, snoozeAt = null) else t.copy(someday = false)
        save(next.bump())
    }

    suspend fun move(id: String, listId: String?, sectionId: String?) = tx {
        val t = live(id)
        ensure(t.kind != TodoKind.COMPLETION) { "完成记录不能移动" }
        if (listId != null) ensure(dao.list(listId)?.deletedAt == null) { "清单不存在" }
        val section = sectionId?.let { dao.section(it) }?.takeIf { it.deletedAt == null && it.listId == listId }
        save(t.copy(listId = listId, sectionId = section?.id, sortOrder = dao.maxTodoOrder() + 1).bump(seq = false))
    }

    /**
     * Batch reschedule from multi-select or the swipe sheet: the day, and with it the time of day
     * per [time]. Without a day there is no time.
     */
    suspend fun reschedule(ids: List<String>, date: LocalDate?, someday: Boolean, time: TimeChange = TimeChange.Keep) = tx {
        for (id in ids) {
            val t = dao.todo(id)?.takeIf { TodoViews.isOpen(it) } ?: continue
            if (someday) {
                if (t.kind != TodoKind.ORDINARY) continue
                if (!t.someday) save(t.copy(someday = true, planDate = null, remindTime = null, remindBefore = 0, snoozeAt = null).bump())
            } else {
                if (t.kind == TodoKind.REPEAT_SOURCE && date == null) continue
                val remind = when (time) {
                    TimeChange.Keep -> t.remindTime
                    TimeChange.Clear -> null
                    is TimeChange.At -> time.time.toString()
                }
                val next = t.copy(planDate = date?.toString(), remindTime = remind.takeIf { date != null }, someday = false).keepingAdvance()
                if (next.planDate == t.planDate && next.remindTime == t.remindTime && !t.someday) continue
                save(ReminderState.afterManualEdit(next, now, zone()).bump())
            }
        }
    }

    suspend fun moveMany(ids: List<String>, listId: String?) = tx {
        if (listId != null) ensure(dao.list(listId)?.deletedAt == null) { "清单不存在" }
        var order = dao.maxTodoOrder() + 1
        for (id in ids) {
            val t = dao.todo(id)?.takeIf { it.deletedAt == null && it.kind != TodoKind.COMPLETION } ?: continue
            save(t.copy(listId = listId, sectionId = null, sortOrder = order++).bump(seq = false))
        }
    }

    /**
     * A hand-set order for [idsInOrder], the rows of one view that trade places, leaving every
     * other todo where it was: they swap the places they hold in the one order all views share.
     * Places held twice (orders from before this rule) are first spread out over every todo.
     */
    suspend fun reorder(idsInOrder: List<String>) = tx {
        val ids = idsInOrder.distinct()
        var moved = ids.mapNotNull { dao.todo(it) }
        if (moved.map { it.sortOrder }.toSet().size < moved.size) {
            dao.allTodos().sortedWith(compareBy({ it.sortOrder }, { it.createdAt }, { it.id })).forEachIndexed { i, t ->
                if (t.sortOrder != i) dao.updateTodo(t.copy(sortOrder = i, revision = t.revision + 1))
            }
            moved = ids.mapNotNull { dao.todo(it) }
        }
        val places = moved.map { it.sortOrder }.sorted()
        moved.forEachIndexed { i, t ->
            if (t.sortOrder != places[i]) dao.updateTodo(t.copy(sortOrder = places[i], revision = t.revision + 1))
        }
    }

    // ------------------------------------------------------------ repeat

    /**
     * Sets, edits or removes repetition. Ordinary → repeat source records `count_base`; editing
     * keeps it; removing turns the current period into an ordinary todo. Both conversions bump
     * revision and state_seq, so earlier completion records can no longer be undone.
     */
    suspend fun setRepeat(id: String, rule: RepeatRule?) = tx {
        val t = live(id)
        when (t.kind) {
            TodoKind.COMPLETION -> throw LedgerException("完成记录不能设置重复")
            TodoKind.ORDINARY -> {
                if (rule == null) return@tx
                ensure(t.completedAt == null) { "已完成的待办不能设置重复" }
                ensure(t.planDate != null) { "请先选择计划日" }
                ensure(!t.someday) { "请先取消「以后再说」" }
                val r = rule.copy(countBase = t.occurrenceCount)
                checkCount(r, t.occurrenceCount)
                save(t.copy(kind = TodoKind.REPEAT_SOURCE, repeatRule = r.encode()).bump())
            }
            TodoKind.REPEAT_SOURCE -> {
                ensure(t.repeatEndedAt == null) { "这条重复待办已结束" }
                if (rule == null) {
                    save(t.copy(kind = TodoKind.ORDINARY, repeatRule = null).bump())
                } else {
                    val old = RepeatRule.decode(t.repeatRule!!)
                    val r = rule.copy(countBase = old.countBase)
                    checkCount(r, t.occurrenceCount)
                    save(t.copy(repeatRule = r.encode()).bump())
                }
            }
        }
    }

    private fun checkCount(rule: RepeatRule, occurrenceCount: Int) {
        val done = Recurrence.executed(rule, occurrenceCount)
        rule.count?.let { n -> ensure(n > done) { "已经执行了 $done 次，次数需要大于 $done" } }
    }

    // ------------------------------------------------------------ complete, skip, undo

    /** Completes an ordinary todo, or the current period of a repeat source. Returns the log row id. */
    suspend fun complete(id: String, expectedSeq: Long?): String = tx {
        val t = token(id, expectedSeq)
        ensure(TodoViews.isOpen(t)) { "这条已处理过" }
        when (t.kind) {
            TodoKind.ORDINARY -> {
                save(t.copy(completedAt = now, snoozeAt = null).bump())
                t.id
            }
            TodoKind.REPEAT_SOURCE -> {
                val items = dao.checklistOf(t.id)
                val before = undoBefore(t, items)
                val advanced = advance(t, items)
                val record = Todo(
                    id = newId(),
                    kind = TodoKind.COMPLETION,
                    title = t.title,
                    notes = t.notes,
                    listId = t.listId,
                    sectionId = t.sectionId,
                    planDate = t.planDate,
                    remindTime = t.remindTime,
                    remindBefore = t.remindBefore,
                    deadline = t.deadline,
                    important = t.important,
                    occurrenceCount = t.occurrenceCount,
                    repeatSourceId = t.id,
                    undoBefore = before.encode(),
                    undoRevision = advanced.revision,
                    sortOrder = t.sortOrder,
                    createdAt = now,
                    completedAt = now,
                )
                insert(record)
                if (items.isNotEmpty()) dao.insertChecklistItems(items.map { it.copy(id = newId(), todoId = record.id) })
                record.id
            }
            TodoKind.COMPLETION -> throw StaleAction()
        }
    }

    /** Skips the current period of a repeat source; the snapshot feeds the 5-second undo bar. */
    suspend fun skip(id: String, expectedSeq: Long?): SkipUndo = tx {
        val t = token(id, expectedSeq)
        ensure(t.kind == TodoKind.REPEAT_SOURCE && TodoViews.isOpen(t)) { "只有进行中的重复待办可以跳过" }
        val items = dao.checklistOf(t.id)
        val before = undoBefore(t, items)
        val advanced = advance(t, items)
        SkipUndo(t.id, before, advanced.revision)
    }

    private fun undoBefore(t: Todo, items: List<ChecklistItem>) = UndoBefore(
        planDate = t.planDate,
        deadline = t.deadline,
        checklist = items.associate { it.id to it.done },
        occurrenceCount = t.occurrenceCount,
        remindedPeriod = t.remindedPeriod,
        ended = t.repeatEndedAt != null,
    )

    /** Moves a repeat source past its current period, or ends it. Returns the saved row. */
    private suspend fun Tx.advance(t: Todo, items: List<ChecklistItem>): Todo {
        val rule = RepeatRule.decode(t.repeatRule!!)
        val plan = TodoViews.parse(t.planDate)!!
        val step = Recurrence.advance(rule, plan, t.occurrenceCount, today, doneOn = today, calendar = calendar())
        val base = t.copy(occurrenceCount = t.occurrenceCount + 1, snoozeAt = null).bump()
        val next = if (step.ended) {
            base.copy(repeatEndedAt = now)
        } else {
            val shift = ChronoUnit.DAYS.between(plan, step.nextPlan)
            if (items.any { it.done }) dao.updateChecklistItems(items.map { it.copy(done = false) })
            base.copy(planDate = step.nextPlan.toString(), deadline = TodoViews.parse(t.deadline)?.plusDays(shift)?.toString())
        }
        save(next)
        return next
    }

    /**
     * Unchecking a completion (or an ordinary todo). A completion record restores its source only
     * when the source is untouched since; otherwise, after [deleteOnly] confirmation, only the
     * record goes. A record never becomes an open todo.
     */
    suspend fun uncomplete(id: String, deleteOnly: Boolean = false): UndoOutcome = tx {
        val t = live(id)
        when (t.kind) {
            TodoKind.ORDINARY -> {
                ensure(t.completedAt != null) { "这条还没完成" }
                save(t.copy(completedAt = null).bump())
                UndoOutcome.RESTORED
            }
            TodoKind.COMPLETION -> {
                val src = t.repeatSourceId?.let { dao.todo(it) }
                val restorable = src != null && src.deletedAt == null && src.kind == TodoKind.REPEAT_SOURCE &&
                    src.revision == t.undoRevision && t.undoBefore != null
                when {
                    restorable -> {
                        restore(src!!, UndoBefore.decode(t.undoBefore!!))
                        dao.deleteTodoRow(t.id)
                        changed += t.id
                        UndoOutcome.RESTORED
                    }
                    !deleteOnly -> UndoOutcome.NEEDS_CONFIRM
                    else -> {
                        dao.deleteTodoRow(t.id)
                        changed += t.id
                        UndoOutcome.RECORD_DELETED
                    }
                }
            }
            TodoKind.REPEAT_SOURCE -> throw LedgerException("重复待办没有完成状态")
        }
    }

    /** Undo bar after a skip; same condition as undoing a completion. Returns false when it cannot. */
    suspend fun undoSkip(undo: SkipUndo): Boolean = tx {
        val src = dao.todo(undo.sourceId)
        if (src == null || src.deletedAt != null || src.kind != TodoKind.REPEAT_SOURCE || src.revision != undo.revisionAfter) return@tx false
        restore(src, undo.before)
        true
    }

    private suspend fun Tx.restore(src: Todo, before: UndoBefore) {
        val items = dao.checklistOf(src.id)
        dao.updateChecklistItems(items.map { it.copy(done = before.checklist[it.id] ?: it.done) })
        save(
            src.copy(
                planDate = before.planDate,
                deadline = before.deadline,
                occurrenceCount = before.occurrenceCount,
                remindedPeriod = before.remindedPeriod,
                repeatEndedAt = if (before.ended) src.repeatEndedAt else null,
                snoozeAt = null,
            ).bump(),
        )
    }

    // ------------------------------------------------------------ reminders

    suspend fun snooze(id: String, expectedSeq: Long?, minutes: Long) = tx {
        val t = token(id, expectedSeq)
        if (!TodoViews.isOpen(t)) throw StaleAction()
        save(t.copy(snoozeAt = now + minutes * 60_000).bump())
    }

    suspend fun snoozeTomorrow(id: String, expectedSeq: Long?) = tx {
        val t = token(id, expectedSeq)
        if (!TodoViews.isOpen(t)) throw StaleAction()
        save(ReminderState.tomorrow(t, today, now, zone()).bump())
    }

    /**
     * Records that the due reminder was delivered (or written off as missed). Not a user change:
     * neither revision nor state_seq moves, so buttons on the notification stay valid and a
     * just-completed period can still be undone.
     */
    suspend fun markReminded(id: String, expectedSeq: Long) = tx {
        val t = token(id, expectedSeq)
        val next = ReminderState.delivered(t)
        if (next != t) {
            dao.updateTodo(next)
            changed += id
        }
    }

    // ------------------------------------------------------------ checklist

    /**
     * What an edit in place on the list leaves behind, written at once when it ends: the title (a
     * blank one keeps the old), and the checklist as [items] in their order. An item with an id
     * keeps its tick and takes the text; one without is new; a blank one, or one no longer listed,
     * goes. Nothing is written when nothing changed.
     */
    suspend fun editInline(id: String, title: String, items: List<InlineItem>) = tx {
        // Gone while it was being edited (an undo took it): nothing left to write to.
        val t = dao.todo(id)?.takeIf { it.deletedAt == null } ?: return@tx
        ensure(t.kind != TodoKind.COMPLETION) { "完成记录不能修改" }
        val existing = dao.checklistOf(id).associateBy { it.id }
        val kept = items.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
        var changed = false
        for (gone in existing.keys - kept.mapNotNull { it.id }.toSet()) {
            dao.deleteChecklistItem(gone)
            changed = true
        }
        kept.forEachIndexed { i, item ->
            val old = item.id?.let(existing::get)
            if (old == null) {
                dao.insertChecklistItem(ChecklistItem(newId(), id, item.text, sortOrder = i))
                changed = true
            } else if (old.text != item.text || old.sortOrder != i) {
                dao.updateChecklistItem(old.copy(text = item.text, sortOrder = i))
                changed = true
            }
        }
        val name = title.trim().ifEmpty { t.title }
        if (changed || name != t.title) save(t.copy(title = name).bump(seq = false))
    }

    /**
     * [ids] become the checklist of one new todo titled [title], in the order given, and go to
     * the trash as one batch. The new todo sits where the first of them did and shows wherever
     * all of them showed: their list and section when they share one, the earliest plan date and
     * deadline among them, 以后再说 when all were, important when any was. A repeating todo would
     * stop repeating, so it cannot be gathered. Returns the new todo's id and the batch, for
     * [ungather].
     */
    suspend fun gather(ids: List<String>, title: String): Pair<String, String> = tx {
        ensure(title.isNotBlank()) { "标题不能为空" }
        val todos = ids.distinct().map { live(it) }
        ensure(todos.isNotEmpty()) { "没有选中待办" }
        ensure(todos.none { it.kind != TodoKind.ORDINARY || it.completedAt != null }) { "重复待办和已完成的待办不能变成检查项" }
        val list = todos.map { it.listId }.distinct().singleOrNull()
        val section = todos.map { it.sectionId }.distinct().singleOrNull().takeIf { list != null }
        val someday = todos.all { it.someday }
        val t = Todo(
            id = newId(),
            title = title.trim(),
            listId = list,
            sectionId = section,
            planDate = if (someday) null else todos.mapNotNull { it.planDate }.minOrNull(),
            someday = someday,
            deadline = todos.mapNotNull { it.deadline }.minOrNull(),
            important = todos.any { it.important },
            sortOrder = todos.first().sortOrder,
            createdAt = now,
        )
        insert(ReminderState.afterManualEdit(t, now, zone()))
        todos.forEachIndexed { i, x -> dao.insertChecklistItem(ChecklistItem(newId(), t.id, x.title, sortOrder = i)) }
        val batch = newId()
        todos.forEach { trashTodo(it, batch) }
        t.id to batch
    }

    /** Undoes [gather]: the gathered todo goes for good and the ones in [batch] come back. */
    suspend fun ungather(id: String, batch: String) = tx {
        dao.todo(id)?.takeIf { it.deletedAt == null }?.let { t ->
            val gone = newId()
            trashTodo(t, gone)
            purge(gone)
        }
        for (t in dao.todosInBatch(batch)) if (t.deletedAt != null) reviveTodo(t)
    }

    /**
     * [ids] join [targetId]'s checklist after its own items, in the order given, and go to the
     * trash as one batch; the target stays as it was otherwise. Returns the items made and the
     * batch, for [unmerge].
     */
    suspend fun mergeInto(targetId: String, ids: List<String>): Pair<List<String>, String> = tx {
        val target = live(targetId)
        ensure(target.kind != TodoKind.COMPLETION && TodoViews.isOpen(target)) { "只能并入还没完成的待办" }
        val todos = ids.distinct().filter { it != targetId }.map { live(it) }
        ensure(todos.isNotEmpty()) { "没有选中其他待办" }
        ensure(todos.none { it.kind != TodoKind.ORDINARY || it.completedAt != null }) { "重复待办和已完成的待办不能变成检查项" }
        var order = dao.maxChecklistOrder(targetId) + 1
        val made = todos.map { x -> newId().also { dao.insertChecklistItem(ChecklistItem(it, targetId, x.title, sortOrder = order++)) } }
        save(target.bump(seq = false))
        val batch = newId()
        todos.forEach { trashTodo(it, batch) }
        made to batch
    }

    /** Undoes [mergeInto]: the items made go and the todos of [batch] come back. */
    suspend fun unmerge(targetId: String, items: List<String>, batch: String) = tx {
        for (id in items) dao.deleteChecklistItem(id)
        dao.todo(targetId)?.takeIf { it.deletedAt == null }?.let { save(it.bump(seq = false)) }
        for (t in dao.todosInBatch(batch)) if (t.deletedAt != null) reviveTodo(t)
    }

    /**
     * Each of [ids] with a checklist lets it go, the opposite of [gather]: every item becomes a
     * todo of its own in the todo's place (its list, section, plan day, 以后再说, deadline and flag),
     * a ticked one already done, and the todo itself goes to the trash; all of them as one batch.
     * Todos without a checklist are left alone. Returns the todos made and the batch, for
     * [undissolve].
     */
    suspend fun dissolve(ids: List<String>): Pair<List<String>, String> = tx {
        val groups = ids.distinct().map { live(it) }.map { it to dao.checklistOf(it.id).sortedBy { i -> i.sortOrder } }.filter { it.second.isNotEmpty() }
        ensure(groups.isNotEmpty()) { "选中的待办都没有检查项" }
        ensure(groups.none { (t, _) -> t.kind != TodoKind.ORDINARY || t.completedAt != null }) { "重复待办和已完成的待办不能解散" }
        val batch = newId()
        val made = mutableListOf<String>()
        for ((t, items) in groups) {
            // The items take the todo's place in the order all views share; what came after moves down to make room.
            val room = items.size - 1
            if (room > 0) {
                for (o in dao.allTodos()) if (o.sortOrder > t.sortOrder) dao.updateTodo(o.copy(sortOrder = o.sortOrder + room, revision = o.revision + 1))
            }
            items.forEachIndexed { i, item ->
                val n = Todo(
                    id = newId(),
                    title = item.text.trim().ifEmpty { "检查项" },
                    listId = t.listId,
                    sectionId = t.sectionId,
                    planDate = t.planDate,
                    someday = t.someday,
                    deadline = t.deadline,
                    important = t.important,
                    sortOrder = t.sortOrder + i,
                    createdAt = now,
                    completedAt = if (item.done) now else null,
                )
                insert(ReminderState.afterManualEdit(n, now, zone()))
                made += n.id
            }
            trashTodo(t, batch)
        }
        made to batch
    }

    /** Undoes [dissolve]: the todos made go for good and the ones in [batch] come back. */
    suspend fun undissolve(made: List<String>, batch: String) = tx {
        val gone = newId()
        for (id in made) dao.todo(id)?.takeIf { it.deletedAt == null }?.let { trashTodo(it, gone) }
        purge(gone)
        for (t in dao.todosInBatch(batch)) if (t.deletedAt != null) reviveTodo(t)
    }

    suspend fun addChecklistItem(todoId: String, text: String): String = tx {
        val t = live(todoId)
        ensure(t.kind != TodoKind.COMPLETION) { "完成记录不能修改" }
        val id = newId()
        dao.insertChecklistItem(ChecklistItem(id, todoId, text.trim(), sortOrder = dao.maxChecklistOrder(todoId) + 1))
        dao.updateTodo(t.bump(seq = false))
        id
    }

    suspend fun setChecklistText(itemId: String, text: String) = tx {
        val item = dao.checklistItem(itemId) ?: return@tx
        val t = live(item.todoId)
        if (item.text == text) return@tx
        dao.updateChecklistItem(item.copy(text = text))
        dao.updateTodo(t.bump(seq = false))
    }

    suspend fun toggleChecklist(itemId: String) = tx {
        val item = dao.checklistItem(itemId) ?: return@tx
        val t = live(item.todoId)
        ensure(t.kind != TodoKind.COMPLETION) { "完成记录不能修改" }
        dao.updateChecklistItem(item.copy(done = !item.done))
        dao.updateTodo(t.bump(seq = false))
    }

    /** Move the existing item before [beforeId], or to the end, keeping its id, text and tick. */
    suspend fun moveChecklistItem(itemId: String, targetId: String, beforeId: String?): ChecklistMoveUndo? = tx {
        val item = dao.checklistItem(itemId) ?: throw LedgerException("检查项不存在")
        val source = live(item.todoId)
        val target = if (targetId == source.id) source else live(targetId)
        ensure(TodoViews.isOpen(source) && TodoViews.isOpen(target)) { "只能移动未完成待办的检查项" }
        if (beforeId == itemId && targetId == source.id) return@tx null
        val parents = listOf(source, target).distinctBy { it.id }
        val before = parents.flatMap { dao.checklistOf(it.id) }
        val destination = before.filter { it.todoId == targetId && it.id != itemId }.toMutableList()
        val at = if (beforeId == null) destination.size else destination.indexOfFirst { it.id == beforeId }
        ensure(at >= 0) { "目标检查项已变化，请重新拖动" }
        destination.add(at, item.copy(todoId = targetId))
        if (source.id == targetId && destination.map { it.id } == before.map { it.id }) return@tx null
        val next = destination.mapIndexed { i, x -> x.copy(sortOrder = i) } +
            if (source.id == targetId) emptyList() else before.filter { it.todoId == source.id && it.id != itemId }.mapIndexed { i, x -> x.copy(sortOrder = i) }
        val old = before.associateBy { it.id }
        val changed = next.filter { it != old[it.id] }
        if (changed.isEmpty()) return@tx null
        dao.updateChecklistItems(changed)
        val after = parents.map { it.bump(seq = false) }
        after.forEach { save(it) }
        ChecklistMoveUndo(before, after.associate { it.id to it.revision })
    }

    /** Refuse a stale undo rather than overwrite a later tick, edit, deletion or move. */
    suspend fun undoChecklistMove(undo: ChecklistMoveUndo) = tx {
        val parents = undo.revisionsAfter.map { (id, revision) ->
            live(id).also { ensure(it.revision == revision) { "检查项已经改过，无法撤销此次移动" } }
        }
        dao.updateChecklistItems(undo.before)
        parents.forEach { save(it.bump(seq = false)) }
    }

    /** Returns the item as it was, for [restoreChecklistItem]; null if it was already gone. */
    suspend fun deleteChecklistItem(itemId: String): ChecklistItem? = tx {
        val item = dao.checklistItem(itemId) ?: return@tx null
        val t = live(item.todoId)
        dao.deleteChecklistItem(itemId)
        dao.updateTodo(t.bump(seq = false))
        item
    }

    /** Undoes [deleteChecklistItem]: the item comes back in its place, if its todo is still there. */
    suspend fun restoreChecklistItem(item: ChecklistItem) = tx {
        val t = live(item.todoId)
        if (dao.checklistItem(item.id) != null) return@tx
        dao.insertChecklistItem(item)
        dao.updateTodo(t.bump(seq = false))
    }

    // ------------------------------------------------------------ lists & sections

    suspend fun createList(name: String): String = tx {
        ensure(name.isNotBlank()) { "清单名称不能为空" }
        val id = newId()
        dao.insertList(TodoList(id, name.trim(), sortOrder = dao.maxListOrder() + 1, createdAt = now))
        id
    }

    suspend fun updateList(id: String, name: String, notes: String, deadline: LocalDate?) = tx {
        ensure(name.isNotBlank()) { "清单名称不能为空" }
        val l = dao.list(id)?.takeIf { it.deletedAt == null } ?: return@tx
        dao.updateList(l.copy(name = name.trim(), notes = notes, deadline = deadline?.toString()))
    }

    suspend fun setArchived(id: String, archived: Boolean) = tx {
        val l = dao.list(id)?.takeIf { it.deletedAt == null } ?: return@tx
        dao.updateList(l.copy(archivedAt = if (archived) now else null))
    }

    suspend fun moveList(id: String, delta: Int) = tx {
        val lists = dao.liveLists().toMutableList()
        val i = lists.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in lists.indices) return@tx
        lists.add(j, lists.removeAt(i))
        dao.updateLists(lists.mapIndexed { idx, l -> l.copy(sortOrder = idx) })
    }

    /** The lists on screen, [idsInOrder], in that order; the archived ones, not among them, after them as they were. */
    suspend fun reorderLists(idsInOrder: List<String>) = tx {
        val lists = dao.liveLists()
        val byId = lists.associateBy { it.id }
        val shown = idsInOrder.distinct().mapNotNull(byId::get)
        val next = (shown + lists.filter { it !in shown }).mapIndexed { i, l -> l.copy(sortOrder = i) }
        val changed = next.filter { n -> byId.getValue(n.id).sortOrder != n.sortOrder }
        if (changed.isNotEmpty()) dao.updateLists(changed)
    }

    suspend fun createSection(listId: String, name: String): String = tx {
        ensure(name.isNotBlank()) { "分区名称不能为空" }
        ensure(dao.list(listId)?.deletedAt == null) { "清单不存在" }
        val id = newId()
        dao.insertSection(TodoSection(id, listId, name.trim(), dao.maxSectionOrder(listId) + 1))
        id
    }

    suspend fun renameSection(id: String, name: String) = tx {
        ensure(name.isNotBlank()) { "分区名称不能为空" }
        val s = dao.section(id)?.takeIf { it.deletedAt == null } ?: return@tx
        dao.updateSection(s.copy(name = name.trim()))
    }

    suspend fun moveSection(id: String, delta: Int) = tx {
        val s = dao.section(id) ?: return@tx
        val all = dao.liveSectionsOf(s.listId).sortedBy { it.sortOrder }.toMutableList()
        val i = all.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in all.indices) return@tx
        all.add(j, all.removeAt(i))
        dao.updateSections(all.mapIndexed { idx, x -> x.copy(sortOrder = idx) })
    }

    // ------------------------------------------------------------ delete & trash

    private suspend fun Tx.trashTodo(t: Todo, batch: String) {
        dao.updateTodo(t.copy(deletedAt = now, deleteBatch = batch, snoozeAt = null).bump())
        changed += t.id
    }

    /** Returns the batch id, for the undo bar. A repeat source's completion records stay in the log. */
    suspend fun delete(id: String): String = tx {
        val t = live(id)
        val batch = newId()
        trashTodo(t, batch)
        batch
    }

    suspend fun deleteMany(ids: List<String>): String = tx {
        val batch = newId()
        for (id in ids) dao.todo(id)?.takeIf { it.deletedAt == null }?.let { trashTodo(it, batch) }
        batch
    }

    /** The section and its todos not already in the trash, as one batch. */
    suspend fun deleteSection(id: String): String = tx {
        val s = dao.section(id)?.takeIf { it.deletedAt == null } ?: throw LedgerException("分区不存在")
        val batch = newId()
        dao.updateSection(s.copy(deletedAt = now, deleteBatch = batch))
        for (t in dao.liveTodosInSection(id)) trashTodo(t, batch)
        batch
    }

    /** The list with its live sections and live todos, as one batch. Completion records stay in the log. */
    suspend fun deleteList(id: String): String = tx {
        val l = dao.list(id)?.takeIf { it.deletedAt == null } ?: throw LedgerException("清单不存在")
        val batch = newId()
        dao.updateList(l.copy(deletedAt = now, deleteBatch = batch))
        for (s in dao.liveSectionsOf(id)) dao.updateSection(s.copy(deletedAt = now, deleteBatch = batch))
        for (t in dao.liveTodosInList(id)) trashTodo(t, batch)
        batch
    }

    private suspend fun Tx.reviveTodo(t: Todo) {
        val list = t.listId?.let { dao.list(it) }
        val listOk = list != null && list.deletedAt == null
        val section = t.sectionId?.let { dao.section(it) }
        val sectionOk = listOk && section != null && section.deletedAt == null && section.listId == t.listId
        save(
            t.copy(
                deletedAt = null,
                deleteBatch = null,
                listId = if (listOk) t.listId else null,
                sectionId = if (sectionOk) t.sectionId else null,
            ).bump(),
        )
    }

    /** Restores exactly the rows of [batch], in the state they were deleted in. */
    suspend fun restoreBatch(batch: String) = tx {
        for (l in dao.listsInBatch(batch)) if (l.deletedAt != null) dao.updateList(l.copy(deletedAt = null, deleteBatch = null))
        for (s in dao.sectionsInBatch(batch)) {
            if (s.deletedAt == null) continue
            val list = dao.list(s.listId)
            ensure(list?.deletedAt == null) { "请先恢复清单「${list?.name}」" }
            dao.updateSection(s.copy(deletedAt = null, deleteBatch = null))
        }
        for (t in dao.todosInBatch(batch)) if (t.deletedAt != null) reviveTodo(t)
    }

    suspend fun restoreTodo(id: String) = tx {
        val t = dao.todo(id)?.takeIf { it.deletedAt != null } ?: return@tx
        reviveTodo(t)
    }

    suspend fun restoreSection(id: String) = tx {
        val s = dao.section(id)?.takeIf { it.deletedAt != null } ?: return@tx
        val list = dao.list(s.listId)
        ensure(list?.deletedAt == null) { "所属清单「${list?.name}」在回收站里，请先恢复清单" }
        dao.updateSection(s.copy(deletedAt = null, deleteBatch = null))
    }

    /** Sections of the batch's lists that sit in other batches; purging the list takes them too. */
    suspend fun sectionsPurgedAlongside(batch: String): Int =
        dao.listsInBatch(batch).filter { it.deletedAt != null }.sumOf { l -> dao.allSectionsOf(l.id).count { it.deleteBatch != batch } }

    /** Permanently deletes the rows still deleted under [batch]. */
    suspend fun purgeBatch(batch: String) = tx { purge(batch) }

    private suspend fun Tx.purge(batch: String) {
        for (t in dao.todosInBatch(batch)) if (t.deletedAt != null) changed += t.id
        dao.purgeTodosInBatch(batch)
        dao.purgeSectionsInBatch(batch)
        dao.purgeListsInBatch(batch)
        db.diaryDao().purgeBatch(batch)
        db.noteDao().purgeBatch(batch)
        db.weekDao().purgeBatch(batch)
    }

    suspend fun emptyTrash() = tx {
        val batches = dao.allTodoBatches() + dao.allSectionBatches() + dao.allListBatches() + db.diaryDao().allBatches() + db.noteDao().allBatches() + db.weekDao().allBatches()
        for (b in batches.toSet()) purge(b)
    }

    /** Daily: batches deleted more than 30 days ago. */
    suspend fun purgeExpired() = tx {
        val before = now - TRASH_DAYS * 24 * 3600 * 1000
        val batches = dao.expiredTodoBatches(before) + dao.expiredSectionBatches(before) + dao.expiredListBatches(before) +
            db.diaryDao().expiredBatches(before) + db.noteDao().expiredBatches(before) + db.weekDao().expiredBatches(before)
        for (b in batches.toSet()) purge(b)
    }

    // ------------------------------------------------------------ reads for the diary page

    fun observeCompletedOn(date: LocalDate): Flow<List<Todo>> {
        val z = zone()
        val from = date.atStartOfDay(z).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        return dao.observeCompletedBetween(from, to)
    }

    companion object {
        const val TRASH_DAYS = 30L
    }
}
