package com.assetboard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    // ------------------------------------------------------------ observe (live rows only)

    @Query("SELECT * FROM todo_lists WHERE deleted_at IS NULL ORDER BY sort_order")
    fun observeLists(): Flow<List<TodoList>>

    @Query("SELECT * FROM todo_sections WHERE deleted_at IS NULL ORDER BY sort_order")
    fun observeSections(): Flow<List<TodoSection>>

    @Query("SELECT * FROM todos WHERE deleted_at IS NULL")
    fun observeTodos(): Flow<List<Todo>>

    @Query("SELECT * FROM checklist_items ORDER BY sort_order")
    fun observeChecklist(): Flow<List<ChecklistItem>>

    // ------------------------------------------------------------ trash

    @Query("SELECT * FROM todo_lists WHERE deleted_at IS NOT NULL")
    fun observeDeletedLists(): Flow<List<TodoList>>

    @Query("SELECT * FROM todo_sections WHERE deleted_at IS NOT NULL")
    fun observeDeletedSections(): Flow<List<TodoSection>>

    @Query("SELECT * FROM todos WHERE deleted_at IS NOT NULL")
    fun observeDeletedTodos(): Flow<List<Todo>>

    // ------------------------------------------------------------ single reads

    @Query("SELECT * FROM todos WHERE id = :id") suspend fun todo(id: String): Todo?
    @Query("SELECT * FROM todo_lists WHERE id = :id") suspend fun list(id: String): TodoList?
    @Query("SELECT * FROM todo_sections WHERE id = :id") suspend fun section(id: String): TodoSection?
    @Query("SELECT * FROM checklist_items WHERE todo_id = :todoId ORDER BY sort_order") suspend fun checklistOf(todoId: String): List<ChecklistItem>
    @Query("SELECT * FROM checklist_items WHERE id = :id") suspend fun checklistItem(id: String): ChecklistItem?

    @Query("SELECT * FROM todos WHERE deleted_at IS NULL AND kind != 'COMPLETION'") suspend fun liveTodos(): List<Todo>
    @Query("SELECT * FROM todos WHERE deleted_at IS NULL") suspend fun allLiveTodos(): List<Todo>
    @Query("SELECT * FROM todo_lists WHERE deleted_at IS NULL ORDER BY sort_order") suspend fun liveLists(): List<TodoList>
    @Query("SELECT * FROM todo_sections WHERE deleted_at IS NULL ORDER BY sort_order") suspend fun liveSections(): List<TodoSection>
    @Query("SELECT * FROM todos WHERE repeat_source_id = :sourceId") suspend fun completionsOf(sourceId: String): List<Todo>
    @Query("SELECT * FROM todos WHERE list_id = :listId AND deleted_at IS NULL AND kind != 'COMPLETION'") suspend fun liveTodosInList(listId: String): List<Todo>
    @Query("SELECT * FROM todos WHERE section_id = :sectionId AND deleted_at IS NULL AND kind != 'COMPLETION'") suspend fun liveTodosInSection(sectionId: String): List<Todo>
    @Query("SELECT * FROM todo_sections WHERE list_id = :listId AND deleted_at IS NULL") suspend fun liveSectionsOf(listId: String): List<TodoSection>
    @Query("SELECT * FROM todo_sections WHERE list_id = :listId") suspend fun allSectionsOf(listId: String): List<TodoSection>
    @Query("SELECT * FROM todos WHERE completed_at >= :from AND completed_at < :to AND deleted_at IS NULL ORDER BY completed_at")
    suspend fun completedBetween(from: Long, to: Long): List<Todo>
    @Query("SELECT * FROM todos WHERE completed_at >= :from AND completed_at < :to AND deleted_at IS NULL ORDER BY completed_at")
    fun observeCompletedBetween(from: Long, to: Long): Flow<List<Todo>>

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM todo_lists") suspend fun maxListOrder(): Int
    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM todo_sections WHERE list_id = :listId") suspend fun maxSectionOrder(listId: String): Int
    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM todos") suspend fun maxTodoOrder(): Int
    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM checklist_items WHERE todo_id = :todoId") suspend fun maxChecklistOrder(todoId: String): Int

    // ------------------------------------------------------------ batches

    @Query("SELECT * FROM todo_lists WHERE delete_batch = :batch") suspend fun listsInBatch(batch: String): List<TodoList>
    @Query("SELECT * FROM todo_sections WHERE delete_batch = :batch") suspend fun sectionsInBatch(batch: String): List<TodoSection>
    @Query("SELECT * FROM todos WHERE delete_batch = :batch") suspend fun todosInBatch(batch: String): List<Todo>
    @Query("SELECT DISTINCT delete_batch FROM todo_lists WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun expiredListBatches(before: Long): List<String>
    @Query("SELECT DISTINCT delete_batch FROM todo_sections WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun expiredSectionBatches(before: Long): List<String>
    @Query("SELECT DISTINCT delete_batch FROM todos WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun expiredTodoBatches(before: Long): List<String>
    @Query("SELECT DISTINCT delete_batch FROM todo_lists WHERE deleted_at IS NOT NULL") suspend fun allListBatches(): List<String>
    @Query("SELECT DISTINCT delete_batch FROM todo_sections WHERE deleted_at IS NOT NULL") suspend fun allSectionBatches(): List<String>
    @Query("SELECT DISTINCT delete_batch FROM todos WHERE deleted_at IS NOT NULL") suspend fun allTodoBatches(): List<String>

    @Query("DELETE FROM todos WHERE delete_batch = :batch AND deleted_at IS NOT NULL") suspend fun purgeTodosInBatch(batch: String)
    @Query("DELETE FROM todo_sections WHERE delete_batch = :batch AND deleted_at IS NOT NULL") suspend fun purgeSectionsInBatch(batch: String)
    @Query("DELETE FROM todo_lists WHERE delete_batch = :batch AND deleted_at IS NOT NULL") suspend fun purgeListsInBatch(batch: String)

    // ------------------------------------------------------------ writes

    @Insert suspend fun insertList(list: TodoList)
    @Insert suspend fun insertSection(section: TodoSection)
    @Insert suspend fun insertTodo(todo: Todo)
    @Insert suspend fun insertChecklistItem(item: ChecklistItem)
    @Insert suspend fun insertChecklistItems(items: List<ChecklistItem>)
    @Update suspend fun updateList(list: TodoList)
    @Update suspend fun updateLists(lists: List<TodoList>)
    @Update suspend fun updateSection(section: TodoSection)
    @Update suspend fun updateSections(sections: List<TodoSection>)
    @Update suspend fun updateTodo(todo: Todo)
    @Update suspend fun updateTodos(todos: List<Todo>)
    @Update suspend fun updateChecklistItem(item: ChecklistItem)
    @Update suspend fun updateChecklistItems(items: List<ChecklistItem>)
    @Query("DELETE FROM checklist_items WHERE id = :id") suspend fun deleteChecklistItem(id: String)
    @Query("DELETE FROM todos WHERE id = :id") suspend fun deleteTodoRow(id: String)

    // ------------------------------------------------------------ backup

    @Query("SELECT * FROM todo_lists") suspend fun allLists(): List<TodoList>
    @Query("SELECT * FROM todo_sections") suspend fun allSections(): List<TodoSection>
    @Query("SELECT * FROM todos") suspend fun allTodos(): List<Todo>
    @Query("SELECT * FROM checklist_items") suspend fun allChecklist(): List<ChecklistItem>
    @Insert suspend fun insertLists(items: List<TodoList>)
    @Insert suspend fun insertSections(items: List<TodoSection>)
    @Insert suspend fun insertTodos(items: List<Todo>)
    @Query("DELETE FROM checklist_items") suspend fun clearChecklist()
    @Query("DELETE FROM todos") suspend fun clearTodos()
    @Query("DELETE FROM todo_sections") suspend fun clearSections()
    @Query("DELETE FROM todo_lists") suspend fun clearLists()
}

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries WHERE date = :date AND deleted_at IS NULL LIMIT 1") suspend fun liveByDate(date: String): DiaryEntry?
    @Query("SELECT * FROM diary_entries WHERE date = :date AND deleted_at IS NULL LIMIT 1") fun observeByDate(date: String): Flow<DiaryEntry?>
    @Query("SELECT * FROM diary_entries WHERE id = :id") suspend fun entry(id: String): DiaryEntry?
    @Query("SELECT * FROM diary_entries WHERE deleted_at IS NULL AND date >= :from AND date <= :to ORDER BY date")
    fun observeLiveBetween(from: String, to: String): Flow<List<DiaryEntry>>
    @Query("SELECT * FROM diary_entries WHERE id = :id") fun observeEntry(id: String): Flow<DiaryEntry?>
    @Query("SELECT date FROM diary_entries WHERE deleted_at IS NULL AND body != '' AND date >= :from AND date <= :to")
    fun observeDatesWithText(from: String, to: String): Flow<List<String>>
    @Query("SELECT * FROM diary_entries WHERE deleted_at IS NULL AND body != '' AND substr(date, 6) = :monthDay AND date != :exclude ORDER BY date DESC")
    fun observeOnThisDay(monthDay: String, exclude: String): Flow<List<DiaryEntry>>
    @Query("SELECT * FROM diary_entries WHERE deleted_at IS NULL AND body LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY date DESC LIMIT 200")
    suspend fun search(q: String): List<DiaryEntry>
    @Query("SELECT * FROM diary_entries WHERE deleted_at IS NOT NULL") fun observeDeleted(): Flow<List<DiaryEntry>>
    @Query("SELECT * FROM diary_entries WHERE delete_batch = :batch") suspend fun inBatch(batch: String): List<DiaryEntry>
    @Query("SELECT DISTINCT delete_batch FROM diary_entries WHERE deleted_at IS NOT NULL AND deleted_at < :before") suspend fun expiredBatches(before: Long): List<String>
    @Query("SELECT DISTINCT delete_batch FROM diary_entries WHERE deleted_at IS NOT NULL") suspend fun allBatches(): List<String>
    @Query("DELETE FROM diary_entries WHERE delete_batch = :batch AND deleted_at IS NOT NULL") suspend fun purgeBatch(batch: String)

    @Insert suspend fun insert(entry: DiaryEntry)
    @Update suspend fun update(entry: DiaryEntry)

    /** The versioned body write: affects 0 rows when the session's view is stale. */
    @Query("UPDATE diary_entries SET body = :body, body_rev = body_rev + 1, updated_at = :now WHERE id = :id AND deleted_at IS NULL AND body_rev = :expectedRev")
    suspend fun updateBody(id: String, body: String, expectedRev: Long, now: Long): Int

    @Query("UPDATE diary_entries SET weather_rev = weather_rev + 1 WHERE id = :id") suspend fun bumpWeatherRev(id: String)
    @Query(
        "UPDATE diary_entries SET weather_code = :code, temp_min = :tMin, temp_max = :tMax, city = :city, lat = :lat, lon = :lon, weather_at = :at " +
            "WHERE id = :id AND weather_rev = :rev AND deleted_at IS NULL",
    )
    suspend fun writeWeather(id: String, rev: Long, code: Int?, tMin: Double?, tMax: Double?, city: String?, lat: Double?, lon: Double?, at: Long): Int

    @Query("SELECT * FROM diary_drafts ORDER BY created_at") fun observeDrafts(): Flow<List<DiaryDraft>>
    @Query("SELECT * FROM diary_drafts WHERE id = :id") suspend fun draft(id: String): DiaryDraft?
    @Insert suspend fun insertDraft(draft: DiaryDraft)
    @Query("DELETE FROM diary_drafts WHERE id = :id") suspend fun deleteDraft(id: String)

    @Query("SELECT * FROM diary_entries") suspend fun all(): List<DiaryEntry>
    @Query("SELECT * FROM diary_drafts") suspend fun allDrafts(): List<DiaryDraft>
    @Insert suspend fun insertAll(items: List<DiaryEntry>)
    @Insert suspend fun insertDrafts(items: List<DiaryDraft>)
    @Query("DELETE FROM diary_drafts") suspend fun clearDrafts()
    @Query("DELETE FROM diary_entries") suspend fun clear()
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM app_meta WHERE `key` = :key") suspend fun get(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(meta: AppMeta)

    @Query("SELECT * FROM holidays WHERE date >= :from AND date <= :to") suspend fun holidaysBetween(from: String, to: String): List<Holiday>
    @Query("SELECT * FROM holiday_years") suspend fun holidayYears(): List<HolidayYear>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putHolidays(items: List<Holiday>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putHolidayYear(year: HolidayYear)
    @Query("DELETE FROM holidays WHERE date LIKE :yearPrefix || '%'") suspend fun clearHolidayYear(yearPrefix: String)
}
