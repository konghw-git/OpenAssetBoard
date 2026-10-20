package com.assetboard.ui.diary

import androidx.lifecycle.viewModelScope
import com.assetboard.AppContainer
import com.assetboard.data.AppendResult
import com.assetboard.data.DiaryDraft
import com.assetboard.data.DiaryEntry
import com.assetboard.data.DiarySession
import com.assetboard.data.Note
import com.assetboard.data.Place
import com.assetboard.data.SourceRef
import com.assetboard.data.Todo
import com.assetboard.data.WeatherResult
import com.assetboard.domain.NetWorthPoint
import com.assetboard.domain.todo.TodoViews
import com.assetboard.ui.ActionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import com.assetboard.data.Weeks
import com.assetboard.data.WeeklyReview
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.time.LocalDate

object DiaryStreak {
    fun of(days: Set<String>, today: LocalDate): Int {
        var d = if (today.toString() in days) today else today.minusDays(1)
        var n = 0
        while (d.toString() in days) { n++; d = d.minusDays(1) }
        return n
    }
}

sealed interface WeatherUi {
    data object Idle : WeatherUi
    data object Loading : WeatherUi
    data object Failed : WeatherUi
    /** No fix and no recent location: the user picks a city. */
    data object NeedCity : WeatherUi
}

/** What an extract into a note came to, for the page's snackbar; [noteId] offers 打开. */
data class Extracted(val message: String, val noteId: String?)

/** One stocktake of the day and how net worth moved since the one before it. */
data class DayStocktake(val point: NetWorthPoint, val change: Long?)

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(private val container: AppContainer) : ActionViewModel(container) {
    private val diary = container.diary
    private val _date = MutableStateFlow(LocalDate.now())
    val date: StateFlow<LocalDate> = _date.asStateFlow()
    private val _session = MutableStateFlow<DiarySession?>(null)
    val session: StateFlow<DiarySession?> = _session.asStateFlow()
    /** Set when the day changed under a session that has input: "已过零点 · 去今天". */
    val pastMidnight = MutableStateFlow(false)
    val weather = MutableStateFlow<WeatherUi>(WeatherUi.Idle)
    val askLocation = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * A day the user tried to go to while the open day's text could neither be saved nor drafted:
     * the page asks before that text is dropped.
     */
    val unsavedLeave = MutableStateFlow<LocalDate?>(null)

    /** The day the current session was opened on, if it was opened as "today". */
    private var openedAsToday: LocalDate? = null
    private var statusJob: Job? = null
    private var leaving: Job? = null
    private var loading: Job? = null
    private var autoWeatherTried = mutableSetOf<String>()

    /** Saves outlive the screen: the app scope plus the epoch bound for this screen. */
    private val saveScope: CoroutineScope = container.appScope + epoch

    val entry: StateFlow<DiaryEntry?> = _date.flatMapLatest { diary.observe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val drafts: StateFlow<List<DiaryDraft>> = combine(diary.observeDrafts(), _date) { all, d -> all.filter { it.date == d.toString() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val completed: StateFlow<List<Todo>> = _date.flatMapLatest { container.todos.observeCompletedOn(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stocktakes: StateFlow<List<DayStocktake>> = combine(container.repository.ledger, _date) { l, d ->
        val series = l?.netWorthSeries().orEmpty()
        series.mapIndexedNotNull { i, p ->
            if (TodoViews.localDate(p.snapshot.at) != d) null else DayStocktake(p, series.getOrNull(i - 1)?.let { p.netWorth - it.netWorth })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val onThisDay: StateFlow<List<DiaryEntry>> = _date.flatMapLatest { diary.observeOnThisDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val holidays = container.holidays.table

    /** Live notes with something taken from this day. */
    val fromDay: StateFlow<List<Note>> = _date.flatMapLatest { container.notes.observeFromDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The review of the week the page is on, for its row under the entry. */
    val weekReview: StateFlow<WeeklyReview?> = _date.flatMapLatest { container.weeks.observe(Weeks.start(it)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The week whose review is due and still empty: last week on a Monday, this week from
     * Sunday 18:00. Null the rest of the time, or once it has text.
     */
    val dueWeek: StateFlow<LocalDate?> = _date.flatMapLatest {
        val now = java.time.LocalDateTime.now()
        val due = when {
            now.dayOfWeek == java.time.DayOfWeek.MONDAY -> Weeks.start(now.toLocalDate()).minusWeeks(1)
            now.dayOfWeek == java.time.DayOfWeek.SUNDAY && now.hour >= 18 -> Weeks.start(now.toLocalDate())
            else -> null
        }
        if (due == null) flowOf(null) else container.weeks.observe(due).map { r -> due.takeIf { r == null || r.body.isBlank() } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val extracted = MutableSharedFlow<Extracted>(extraBufferCapacity = 2)

    /** Days in a row with text, ending today (or yesterday while today is still empty). */
    val streak: StateFlow<Int> = diary.observeDatesWithText(LocalDate.now().minusDays(400), LocalDate.now())
        .map { dates -> DiaryStreak.of(dates.toSet(), LocalDate.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        open(LocalDate.now())
    }

    fun open(d: LocalDate) = leaveThenOpen(d, dropUnsaved = false)

    /** The user copied the text that cannot be saved and goes to [d] anyway. */
    fun dropUnsavedAndOpen(d: LocalDate) {
        unsavedLeave.value = null
        leaveThenOpen(d, dropUnsaved = true)
    }

    /**
     * Leaving a day saves it first (D4), and the old session stays on screen until that landed:
     * text that could not even go into a draft is never dropped without asking.
     */
    private fun leaveThenOpen(d: LocalDate, dropUnsaved: Boolean) {
        val old = _session.value ?: return show(d)
        leaving?.cancel()
        leaving = viewModelScope.launch {
            // On the save scope, so the save lands even if this screen goes away meanwhile.
            val saved = saveScope.async { saveBeforeLeaving(old) }.await()
            when {
                saved || dropUnsaved -> {
                    old.close()
                    show(d)
                }
                old.status.value == DiarySession.Status.UNSAVED -> unsavedLeave.value = d
                // A restore holds the gate; it collects this text itself.
                else -> messages.emit("正在恢复数据，请稍后再切换日期")
            }
        }
    }

    /** True once nothing of [s] can be lost: the body is saved, or the text went into a draft. */
    private suspend fun saveBeforeLeaving(s: DiarySession): Boolean {
        // Typing can go on during a save; a few rounds catch up with it.
        repeat(3) {
            if (!s.saveNow()) return s.status.value == DiarySession.Status.ENDED
            if (!s.dirty) return true
        }
        return !s.dirty
    }

    private fun show(d: LocalDate) {
        _date.value = d
        pastMidnight.value = false
        weather.value = WeatherUi.Idle
        openedAsToday = d.takeIf { it == LocalDate.now() }
        _session.value = null
        statusJob?.cancel()
        loading?.cancel()
        loading = launchBound {
            val e = diary.entryOn(d)
            val s = DiarySession(diary, d, saveScope, onCreated = { id -> fetchWeather(id, null) })
            s.open(e)
            _session.value = s
            statusJob = launchBound { watch(s) }
            if (e != null && e.body.isNotBlank() && e.weatherAt == null && autoWeatherTried.add(e.id)) fetchWeather(e.id, null)
        }
    }

    private suspend fun watch(s: DiarySession) {
        launchBound { s.messages.collect { messages.emit(it) } }
        s.status.collect { st ->
            // The draft is written; reload the day from the database.
            if (st == DiarySession.Status.ENDED && _session.value === s) open(s.date)
        }
    }

    fun onInput(text: String) {
        _session.value?.onInput(text)
    }

    /** Leaving the page or going to the background. */
    fun flush() {
        val s = _session.value ?: return
        saveScope.launch { s.saveNow() }
    }

    /** Coming back to the foreground: the midnight rule. */
    fun onResume() {
        val today = LocalDate.now()
        val opened = openedAsToday ?: return
        if (opened == today) return
        val s = _session.value
        if (s == null || !s.touched) open(today) else pastMidnight.value = true
    }

    fun goToday() = open(LocalDate.now())

    fun delete(onDone: () -> Unit = {}) = action {
        val s = _session.value ?: return@action
        s.saveNow()
        val id = s.entryId ?: diary.entryOn(s.date)?.id ?: return@action
        diary.delete(id)
        open(s.date)
        onDone()
        messages.emit("日记已移到回收站")
    }

    fun appendDraft(draft: DiaryDraft) = action {
        val s = _session.value ?: return@action
        val current = s.text.value
        s.onInput(if (current.isEmpty()) draft.text else current.trimEnd() + "\n\n" + draft.text)
        if (s.saveNow()) diary.discardDraft(draft.id)
    }

    fun discardDraft(draft: DiaryDraft) = action { diary.discardDraft(draft.id) }

    // ------------------------------------------------------------ extract into notes

    /** A new note from parts of [date]'s text; [onDone] gets its id. */
    fun extractToNew(date: LocalDate, body: String, onDone: (String) -> Unit) = action {
        onDone(container.notes.create(body, SourceRef.day(date)))
    }

    /** Only the result is reported: an append that went into a draft or failed never reads as done. */
    fun extractAppend(date: LocalDate, note: Note, block: String) = action {
        when (val r = container.notes.append(note.id, block, SourceRef.day(date))) {
            AppendResult.Appended -> extracted.emit(Extracted("已追加到《${note.title}》", note.id))
            AppendResult.SavedAsDraft -> extracted.emit(Extracted("保存冲突，内容已存为草稿，可在笔记页处理", null))
            is AppendResult.Failed -> error.value = r.reason
        }
    }

    // ------------------------------------------------------------ weather

    fun retryWeather(place: Place? = null) {
        val id = entry.value?.id ?: return
        fetchWeather(id, place)
    }

    /**
     * Never blocks the body: a separate request, discarded if a newer one started meanwhile. The
     * day comes from the entry, never from the page, which may have moved on while locating.
     */
    private fun fetchWeather(entryId: String, manual: Place?) {
        val settings = container.settings
        if (!settings.weatherAllowed) return
        launchBound {
            var day: LocalDate? = null
            // Only a page still on that day shows the progress; the result lands in its entry anyway.
            fun show(ui: WeatherUi) { if (day == null || _date.value == day) weather.value = ui }
            try {
                val rev = diary.beginWeather(entryId)
                val e = diary.entry(entryId) ?: return@launchBound
                val date = LocalDate.parse(e.date).also { day = it }
                show(WeatherUi.Loading)
                val place = manual
                    ?: e.takeIf { it.lat != null && it.lon != null && it.city != null }?.let { Place(it.lat!!, it.lon!!, it.city) }
                    ?: run {
                        // Ask for the location permission once; after that, fall back quietly.
                        if (!container.weather.hasLocationPermission() && settings.long(KEY_LOCATION_ASKED) == 0L) {
                            settings.putLong(KEY_LOCATION_ASKED, System.currentTimeMillis())
                            askLocation.tryEmit(Unit)
                        }
                        container.weather.locate()
                    }
                if (place == null) {
                    show(WeatherUi.NeedCity)
                    return@launchBound
                }
                val w = container.weather.daily(place, date)
                    ?: WeatherResult(null, null, null, place.city, place.lat, place.lon)
                diary.writeWeather(entryId, rev, w)
                show(if (w.code == null) WeatherUi.Failed else WeatherUi.Idle)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                show(WeatherUi.Failed)
            }
        }
    }

    suspend fun searchCity(q: String): List<Place> = runCatching { container.weather.searchCity(q) }.getOrDefault(emptyList())

    private companion object {
        const val KEY_LOCATION_ASKED = "location_asked_at"
    }

    override fun onCleared() {
        val s = _session.value
        if (s != null) saveScope.launch { s.saveNow(); s.close() }
        super.onCleared()
    }
}
