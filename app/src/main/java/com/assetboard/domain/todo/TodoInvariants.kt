package com.assetboard.domain.todo

import com.assetboard.data.ChecklistItem
import com.assetboard.data.DiaryDraft
import com.assetboard.data.DiaryEntry
import com.assetboard.data.Todo
import com.assetboard.data.TodoKind
import com.assetboard.data.TodoList
import com.assetboard.data.TodoSection
import com.assetboard.domain.calendar.WorkdayCalendar
import com.assetboard.domain.ensure
import java.time.LocalDate
import java.time.LocalTime

/**
 * The business invariants of the todo and diary tables. Repository writes check the rows they
 * touch; backup import checks the whole file. Both use these same functions.
 */
object TodoInvariants {
    private fun dateOk(s: String?) = s == null || runCatching { LocalDate.parse(s) }.isSuccess
    private fun timeOk(s: String?) = s == null || runCatching { LocalTime.parse(s) }.isSuccess

    /** Rules 2–4, 7 and 9 for one todo: everything that needs no other row. */
    fun checkTodo(t: Todo, calendar: WorkdayCalendar) {
        val name = "「${t.title.take(20)}」"
        ensure(dateOk(t.planDate) && dateOk(t.deadline)) { "待办$name 的日期无法解析" }
        ensure(timeOk(t.remindTime)) { "待办$name 的提醒时刻无法解析" }
        ensure(t.remindTime == null || t.planDate != null) { "待办$name 的提醒必须挂在计划日上" }
        ensure((t.deletedAt == null) == (t.deleteBatch == null)) { "待办$name 的删除标记不完整" }
        ensure(t.occurrenceCount >= 0) { "待办$name 的次数不合法" }
        when (t.kind) {
            TodoKind.REPEAT_SOURCE -> {
                ensure(t.completedAt == null) { "重复待办$name 不能有完成时间" }
                ensure(!t.someday) { "重复待办$name 不能设为以后再说" }
                ensure(t.repeatSourceId == null) { "重复待办$name 不能有来源" }
                val rule = RepeatRule.decodeOrNull(t.repeatRule)
                ensure(rule != null) { "重复待办$name 的重复规则无法解析" }
                Recurrence.validate(rule!!, TodoViews.parse(t.planDate), t.occurrenceCount, calendar)
            }
            TodoKind.COMPLETION -> {
                ensure(t.completedAt != null) { "完成记录$name 缺少完成时间" }
                ensure(t.repeatRule == null) { "完成记录$name 不能有重复规则" }
            }
            TodoKind.ORDINARY -> {
                ensure(t.repeatRule == null) { "普通待办$name 不能有重复规则" }
                ensure(t.repeatSourceId == null) { "普通待办$name 不能有来源" }
            }
        }
    }

    /** Rules 1, 5 and 8 for one todo against its neighbours. */
    fun checkTodoRefs(t: Todo, lists: Map<String, TodoList>, sections: Map<String, TodoSection>, todos: Map<String, Todo>) {
        val name = "「${t.title.take(20)}」"
        ensure(t.listId == null || t.listId in lists) { "待办$name 指向不存在的清单" }
        if (t.sectionId != null) {
            val s = sections[t.sectionId]
            ensure(s != null) { "待办$name 指向不存在的分区" }
            ensure(s!!.listId == t.listId) { "待办$name 的分区不属于它的清单" }
        }
        if (t.repeatSourceId != null) {
            val src = todos[t.repeatSourceId]
            ensure(src != null) { "完成记录$name 指向不存在的重复待办" }
            ensure(src!!.kind != TodoKind.COMPLETION) { "完成记录$name 的来源不能是完成记录" }
        }
    }

    /** Rule 6: at most one undeleted entry per date. */
    fun checkDiaryDates(entries: List<DiaryEntry>) {
        entries.filter { it.deletedAt == null }.groupBy { it.date }.forEach { (date, same) ->
            ensure(same.size <= 1) { "$date 有 ${same.size} 篇日记" }
        }
    }

    /** The whole todo and diary part of a backup. */
    fun checkAll(
        lists: List<TodoList>,
        sections: List<TodoSection>,
        todos: List<Todo>,
        checklist: List<ChecklistItem>,
        diary: List<DiaryEntry>,
        drafts: List<DiaryDraft>,
        calendar: WorkdayCalendar,
    ) {
        fun <T> unique(items: List<T>, id: (T) -> String, what: String) =
            ensure(items.map(id).toSet().size == items.size) { "备份里有重复的$what" }
        unique(lists, { it.id }, "清单")
        unique(sections, { it.id }, "分区")
        unique(todos, { it.id }, "待办")
        unique(checklist, { it.id }, "检查项")
        unique(diary, { it.id }, "日记")
        unique(drafts, { it.id }, "草稿")

        for (l in lists) {
            ensure(dateOk(l.deadline)) { "清单「${l.name}」的日期无法解析" }
            ensure((l.deletedAt == null) == (l.deleteBatch == null)) { "清单「${l.name}」的删除标记不完整" }
        }
        val listById = lists.associateBy { it.id }
        for (s in sections) {
            ensure(s.listId in listById) { "分区「${s.name}」指向不存在的清单" }
            ensure((s.deletedAt == null) == (s.deleteBatch == null)) { "分区「${s.name}」的删除标记不完整" }
        }
        val sectionById = sections.associateBy { it.id }
        val todoById = todos.associateBy { it.id }
        for (t in todos) {
            checkTodo(t, calendar)
            checkTodoRefs(t, listById, sectionById, todoById)
        }
        for (c in checklist) ensure(c.todoId in todoById) { "检查项指向不存在的待办" }
        for (e in diary) {
            ensure(dateOk(e.date)) { "日记日期「${e.date}」无法解析" }
            ensure((e.deletedAt == null) == (e.deleteBatch == null)) { "${e.date} 的日记删除标记不完整" }
        }
        checkDiaryDates(diary)
        val diaryIds = diary.map { it.id }.toSet()
        for (d in drafts) {
            ensure(dateOk(d.date)) { "草稿日期「${d.date}」无法解析" }
            ensure(d.diaryId == null || d.diaryId in diaryIds) { "草稿指向不存在的日记" }
        }
    }
}
