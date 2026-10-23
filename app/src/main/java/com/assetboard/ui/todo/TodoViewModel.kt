package com.assetboard.ui.todo

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.assetboard.AppContainer
import com.assetboard.data.InlineItem
import com.assetboard.data.TimeChange
import com.assetboard.data.Todo
import com.assetboard.data.TodoData
import com.assetboard.data.TodoDraft
import com.assetboard.data.TodoKind
import com.assetboard.data.UndoOutcome
import com.assetboard.domain.Habit
import com.assetboard.domain.todo.TodoViews
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.components.UndoEvent
import com.assetboard.ui.components.UndoMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** A snackbar message, optionally with a 5-second undo. */
typealias TodoEvent = UndoEvent

/** What a todo gathered from others is called until it is named; its edit starts with the title blank. */
internal const val GATHERED_TITLE = "新待办"

/** Actions shared by the todo screens. Every write carries the epoch bound at creation. */
class TodoViewModel(private val container: AppContainer) : ActionViewModel(container) {
    private val repo = container.todos
    val data: StateFlow<TodoData?> = repo.data
    private val _events = MutableSharedFlow<TodoEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<TodoEvent> = _events
    /** A completion record whose source cannot be restored: ask before deleting only the record. */
    val confirmRecord = MutableStateFlow<Todo?>(null)
    val missed get() = container.reminders.missed

    private fun short(t: Todo) = "「" + t.title.take(14) + (if (t.title.length > 14) "…" else "") + "」"

    fun toggle(t: Todo) = if (TodoViews.isOpen(t)) complete(t) else uncomplete(t)

    /** The keys of the habits checked on [date]. */
    fun habitsOn(date: LocalDate): Flow<Set<String>> = container.habits.observe(date)

    /** The habits checked off from today on, in order. */
    val activeHabits: Flow<List<Habit>> = container.habits.observeActive()

    fun toggleHabit(h: Habit) = action { container.habits.toggle(h) }

    /** The keys of the habits that have [date] off. */
    fun habitsSkippedOn(date: LocalDate): Flow<Set<String>> = container.habits.observeSkipped(date)

    /** A long press on a battery cell: today off for [h], or back on. */
    fun toggleHabitRest(h: Habit) = action {
        val rest = container.habits.toggleSkip(h)
        _events.tryEmit(TodoEvent(if (rest) "「${h.label}」今天休息，不算断签" else "「${h.label}」取消休息"))
    }

    fun addHabit(label: String, icon: String, color: Long, onDone: () -> Unit = {}) = action(onDone) { container.habits.add(label, icon, color) }

    fun updateHabit(key: String, label: String, icon: String, color: Long, onDone: () -> Unit = {}) = action(onDone) { container.habits.update(key, label, icon, color) }

    fun reorderHabits(keys: List<String>) = action { container.habits.reorder(keys) }

    fun removeHabit(key: String, onDone: () -> Unit = {}) = action(onDone) { container.habits.remove(key) }

    fun toggleChecklist(itemId: String) = action { repo.toggleChecklist(itemId) }

    fun deleteChecklistItem(itemId: String) = action {
        val item = repo.deleteChecklistItem(itemId) ?: return@action
        _events.tryEmit(TodoEvent("已删除检查项「${item.text.take(14)}」") { repo.restoreChecklistItem(item) })
    }

    fun complete(t: Todo) = action {
        val logged = repo.complete(t.id, t.stateSeq)
        _events.tryEmit(
            TodoEvent("已完成 ${short(t)}") {
                if (repo.uncomplete(logged) == UndoOutcome.NEEDS_CONFIRM) confirmRecord.value = repo.todo(logged)
            },
        )
    }

    fun uncomplete(t: Todo) = action {
        if (repo.uncomplete(t.id) == UndoOutcome.NEEDS_CONFIRM) confirmRecord.value = t
    }

    fun confirmDeleteRecord() {
        val t = confirmRecord.value ?: return
        confirmRecord.value = null
        action { repo.uncomplete(t.id, deleteOnly = true) }
    }

    fun skip(t: Todo) = action {
        val undo = repo.skip(t.id, t.stateSeq)
        _events.tryEmit(
            TodoEvent("已跳过本期") {
                if (!repo.undoSkip(undo)) _events.tryEmit(TodoEvent("这条重复待办已经改过，无法撤销跳过"))
            },
        )
    }

    fun delete(ids: List<String>) = action {
        if (ids.isEmpty()) return@action
        val batch = repo.deleteMany(ids)
        _events.tryEmit(TodoEvent(if (ids.size == 1) "已删除" else "已删除 ${ids.size} 条") { repo.restoreBatch(batch) })
    }

    fun setImportant(t: Todo, important: Boolean) = action { repo.setImportant(t.id, important) }

    fun reschedule(ids: List<String>, date: LocalDate?, someday: Boolean, time: TimeChange = TimeChange.Keep) = action { repo.reschedule(ids, date, someday, time) }

    /** An edit in place has ended: its title and checklist in one write. */
    fun saveInline(id: String, title: String, items: List<InlineItem>) = action { repo.editInline(id, title, items) }

    /** [ids] as the checklist of one new todo; [onMade] gets it, to edit in place. Undo brings them back. */
    fun gather(ids: List<String>, onMade: (String) -> Unit) = action {
        if (ids.isEmpty()) return@action
        val (id, batch) = repo.gather(ids, GATHERED_TITLE)
        onMade(id)
        _events.tryEmit(TodoEvent("${ids.size} 条已变成新待办的检查项") { repo.ungather(id, batch) })
    }

    fun move(ids: List<String>, listId: String?) = action { repo.moveMany(ids, listId) }

    fun reorder(ids: List<String>) = action { repo.reorder(ids) }

    /** [onResult] hears whether the todo was written; a failure also shows the error. */
    fun quickAdd(draft: TodoDraft, onResult: (saved: Boolean) -> Unit = {}) = action {
        try {
            repo.create(draft)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onResult(false)
            throw e
        }
        onResult(true)
    }

    fun createList(name: String, onCreated: (String) -> Unit) = action { onCreated(repo.createList(name)) }
    fun updateList(id: String, name: String, notes: String, deadline: LocalDate?) = action { repo.updateList(id, name, notes, deadline) }
    fun setArchived(id: String, archived: Boolean) = action { repo.setArchived(id, archived) }
    fun moveList(id: String, delta: Int) = action { repo.moveList(id, delta) }

    fun deleteList(id: String, onDone: () -> Unit) = action {
        val batch = repo.deleteList(id)
        onDone()
        _events.tryEmit(TodoEvent("清单已移到回收站") { repo.restoreBatch(batch) })
    }

    fun createSection(listId: String, name: String) = action { repo.createSection(listId, name) }
    fun renameSection(id: String, name: String) = action { repo.renameSection(id, name) }
    fun moveSection(id: String, delta: Int) = action { repo.moveSection(id, delta) }

    fun deleteSection(id: String) = action {
        val batch = repo.deleteSection(id)
        _events.tryEmit(TodoEvent("分区已移到回收站") { repo.restoreBatch(batch) })
    }

    fun undo(e: TodoEvent) {
        val u = e.undo ?: return
        action { u() }
    }

    fun clearMissed() = container.reminders.clearMissed()

    fun isRepeat(t: Todo) = t.kind == TodoKind.REPEAT_SOURCE
}

/** Shows [TodoViewModel.events]; undo stays offered for [com.assetboard.ui.components.UNDO_MS]. */
@Composable
fun TodoSnackbars(vm: TodoViewModel, host: SnackbarHostState) = UndoMessages(vm.events, host, vm::undo)
