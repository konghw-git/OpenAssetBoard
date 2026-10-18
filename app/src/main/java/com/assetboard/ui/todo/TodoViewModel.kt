package com.assetboard.ui.todo

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.assetboard.AppContainer
import com.assetboard.data.Todo
import com.assetboard.data.TodoData
import com.assetboard.data.TodoDraft
import com.assetboard.data.TodoKind
import com.assetboard.data.UndoOutcome
import com.assetboard.domain.Habit
import com.assetboard.domain.todo.TodoViews
import com.assetboard.ui.ActionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/** A snackbar message, optionally with a 5-second undo. */
data class TodoEvent(val text: String, val undo: (suspend () -> Unit)? = null)

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

    fun habitsOn(date: LocalDate): Flow<Set<Habit>> = container.habits.observe(date)

    fun toggleHabit(h: Habit) = action { container.habits.toggle(h) }

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

    fun reschedule(ids: List<String>, date: LocalDate?, someday: Boolean) = action { repo.reschedule(ids, date, someday) }

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

/** Shows [TodoViewModel.events]; undo stays offered for 5 seconds. */
@Composable
fun TodoSnackbars(vm: TodoViewModel, host: SnackbarHostState) {
    LaunchedEffect(vm) {
        // A newer message replaces the one on screen.
        vm.events.collectLatest { e ->
            val result = withTimeoutOrNull(5_000) {
                host.showSnackbar(e.text, actionLabel = if (e.undo != null) "撤销" else null, duration = SnackbarDuration.Indefinite, withDismissAction = e.undo == null)
            }
            if (result == null) host.currentSnackbarData?.dismiss()
            if (result == SnackbarResult.ActionPerformed) vm.undo(e)
        }
    }
}
