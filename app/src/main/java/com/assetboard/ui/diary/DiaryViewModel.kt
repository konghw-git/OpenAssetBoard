package com.assetboard.ui.diary

import com.assetboard.domain.AppClock
import androidx.lifecycle.viewModelScope
import com.assetboard.AppContainer
import com.assetboard.data.ai.AiDayTools
import com.assetboard.data.AppendResult
import com.assetboard.data.ai.ChatTurn
import com.assetboard.data.DiaryDraft
import com.assetboard.data.DiaryEntry
import com.assetboard.data.DiarySession
import com.assetboard.data.Note
import com.assetboard.data.net.Place
import com.assetboard.data.SourceRef
import com.assetboard.data.Todo
import com.assetboard.data.WeatherResult
import com.assetboard.domain.AiAction
import com.assetboard.domain.AiAgent
import com.assetboard.domain.AiEdit
import com.assetboard.domain.DocKind
import com.assetboard.domain.Mood
import com.assetboard.domain.NetWorthPoint
import com.assetboard.domain.todo.TodoViews
import com.assetboard.ui.ActionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import com.assetboard.data.Weeks
import com.assetboard.data.WeeklyReview
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.coroutineScope
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
    private val _date = MutableStateFlow(AppClock.today())
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
        val now = java.time.LocalDateTime.now(AppClock.clock)
        val due = when {
            now.dayOfWeek == java.time.DayOfWeek.MONDAY -> Weeks.start(now.toLocalDate()).minusWeeks(1)
            now.dayOfWeek == java.time.DayOfWeek.SUNDAY && now.hour >= 18 -> Weeks.start(now.toLocalDate())
            else -> null
        }
        if (due == null) flowOf(null) else container.weeks.observe(due).map { r -> due.takeIf { r == null || r.body.isBlank() } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val extracted = MutableSharedFlow<Extracted>(extraBufferCapacity = 2)

    /**
     * Days in a row with text, ending today (or yesterday while today is still empty). The day is
     * re-read while the streak is on screen: fixed at construction, it froze when the tab stayed
     * open across midnight, and counted the next day against a query that did not include it.
     */
    val streak: StateFlow<Int> = flow {
        while (true) {
            emit(AppClock.today())
            delay(60_000)
        }
    }.distinctUntilChanged().flatMapLatest { today ->
        diary.observeDatesWithText(today.minusDays(400), today)
            .map { dates -> DiaryStreak.of(dates.toSet(), today) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        open(AppClock.today())
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
            val saved = saveScope.async { old.saveBeforeLeaving() }.await()
            when {
                saved || dropUnsaved -> {
                    old.close()
                    show(d)
                }
                old.status.value == DiarySession.Status.UNSAVED -> unsavedLeave.value = d
                // A restore holds the gate and collects this text itself; otherwise a save is behind.
                else -> messages.emit(if (container.gate.frozen.value) "正在恢复数据，请稍后再切换日期" else "刚才的输入还没写完，请停手片刻再切换日期")
            }
        }
    }

    private fun show(d: LocalDate) {
        _date.value = d
        pastMidnight.value = false
        weather.value = WeatherUi.Idle
        openedAsToday = d.takeIf { it == AppClock.today() }
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

    /** The session's messages and status together: both end with the status job, on reload or on leaving. */
    private suspend fun watch(s: DiarySession): Unit = coroutineScope {
        launch { s.messages.collect { messages.emit(it) } }
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
        val today = AppClock.today()
        val opened = openedAsToday ?: return
        if (opened == today) return
        val s = _session.value
        if (s == null || !s.touched) open(today) else pastMidnight.value = true
    }

    fun goToday() = open(AppClock.today())

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

    // ------------------------------------------------------------ mood and AI

    /** The open day's mood; null clears it. */
    fun setMood(mood: Mood?) {
        val d = _date.value
        action { diary.setMood(d, mood?.value) }
    }

    /** The AI pass running right now, if any: its buttons stay busy meanwhile. */
    val aiBusy = MutableStateFlow<AiAction?>(null)

    /** An AI edit that changed the text: what it was before, for the snackbar's 撤销. */
    val tidied = MutableSharedFlow<Tidied>(extraBufferCapacity = 4)
    private var lastTidied: Tidied? = null

    /** AI 排版: only layout and punctuation, on the selection or the whole body. */
    fun aiFormat(selStart: Int = -1, selEnd: Int = -1) = aiEdit(AiAction.FORMAT, selStart, selEnd)

    /** AI 润色: only the wording, on the selection or the whole body. */
    fun aiPolish(selStart: Int = -1, selEnd: Int = -1) = aiEdit(AiAction.POLISH, selStart, selEnd)

    /** 生成日记: a fresh body for the day, written from its records — the model may pull more. */
    fun aiGenerate() = aiEdit(AiAction.GENERATE, -1, -1)

    /**
     * One AI pass over the open day's text, saved at once like any typing. Tapped again with
     * nothing left to change, it offers the last 撤销 afresh while that can still undo, rather
     * than covering it with 已经很整齐了.
     */
    private fun aiEdit(kind: AiAction, selStart: Int, selEnd: Int) {
        val s = _session.value ?: return
        if (s.status.value != DiarySession.Status.READY) return
        if (aiBusy.value != null) return
        val before = s.text.value
        val range = AiEdit.targetRange(before, selStart, selEnd)
        if (kind != AiAction.GENERATE && range.isEmpty()) {
            messages.tryEmit("先写点内容再用 AI")
            return
        }
        aiBusy.value = kind
        launchBound {
            try {
                val out = if (kind == AiAction.GENERATE) generate() else {
                    AiEdit.editText(container.ai::complete, kind, DocKind.DIARY, before, range)
                }
                // Left the day while the model worked: the result must not land in a closed session.
                if (_session.value !== s) {
                    messages.tryEmit("已切换日期，AI 的结果没有应用")
                    return@launchBound
                }
                // Typed on while the model worked: what was typed wins, the reply is dropped.
                if (s.text.value != before) {
                    messages.tryEmit("文本已改动，AI 的结果没有应用")
                    return@launchBound
                }
                val after = AiEdit.replace(before, range.first, range.last + 1, out).text
                if (after == before) {
                    val last = lastTidied?.takeIf { it.session === s && it.after == before }
                    if (last != null) tidied.tryEmit(last) else messages.tryEmit(unchangedText(kind))
                    return@launchBound
                }
                s.onInput(after)
                saveScope.launch { s.saveNow() }
                val t = Tidied(s, before, after, noticeText(kind))
                lastTidied = t
                tidied.tryEmit(t)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit("${failedLabel(kind)}失败：${e.message ?: "未知错误"}")
            } finally {
                aiBusy.value = null
            }
        }
    }

    /**
     * The diary agent: seed it with the day read fresh from the database and let it call the data
     * tools (everything but the notes) for whatever else it wants before writing.
     */
    private suspend fun generate(): String {
        val date = _date.value
        val tools = dayTools.tools(date)
        val system = AiEdit.DIARY_SYSTEM + "\n\n你可以调用以下工具查数据：\n" + AiAgent.toolsDoc(tools)
        val seed = AiEdit.daySummary(dayTools.dayFacts(date)) + "\n\n写这一天的日记。"
        return AiAgent.run(
            chat = { history: List<AiAgent.Turn> ->
                container.ai.chat(system, history.map { ChatTurn(it.role, it.content) })
            },
            tools = tools,
            seed = seed,
        )
    }

    /** Puts the text back as it was before [t], unless it was typed on since. */
    fun undoTidy(t: Tidied) {
        val s = _session.value?.takeIf { it === t.session && it.text.value == t.after } ?: return
        if (lastTidied === t) lastTidied = null
        s.onInput(t.before)
        saveScope.launch { s.saveNow() }
    }

    data class Tidied(val session: DiarySession, val before: String, val after: String, val notice: String)

    private fun unchangedText(kind: AiAction) = when (kind) {
        AiAction.FORMAT -> "已经很整齐了"
        AiAction.POLISH -> "润色后没有变化"
        AiAction.GENERATE -> "AI 没有生成内容"
    }

    private fun noticeText(kind: AiAction) = when (kind) {
        AiAction.FORMAT -> "已排版：标点、空行和段落都理好了"
        AiAction.POLISH -> "已润色"
        AiAction.GENERATE -> "已生成今天的日记"
    }

    private fun failedLabel(kind: AiAction) = when (kind) {
        AiAction.FORMAT -> "AI 排版"
        AiAction.POLISH -> "润色"
        AiAction.GENERATE -> "生成日记"
    }

    /** The agent's data window: every domain but the notes, read fresh when a tool runs. */
    private val dayTools = AiDayTools(
        container.diary, container.todos, container.habits, container.health, container.food,
        container.repository, container.countdowns, container.weeks, container.goal,
    )

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
                            settings.putLong(KEY_LOCATION_ASKED, AppClock.millis())
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
