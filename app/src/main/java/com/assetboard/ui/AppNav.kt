package com.assetboard.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.assetboard.AppContainer
import com.assetboard.data.Epoch
import com.assetboard.domain.LedgerException
import com.assetboard.ui.account.AccountDetailScreen
import com.assetboard.ui.components.DustHost
import com.assetboard.ui.week.WeekScreen
import com.assetboard.ui.components.DustState
import com.assetboard.ui.components.GlassSnackbarHost
import com.assetboard.ui.components.LocalDust
import com.assetboard.ui.components.backdropSource
import com.assetboard.ui.components.rememberBackdrop
import com.assetboard.ui.account.AccountEditScreen
import com.assetboard.ui.diary.DiaryCalendarScreen
import com.assetboard.ui.diary.DiaryScreen
import com.assetboard.ui.diary.DiarySearchScreen
import com.assetboard.ui.health.HealthScreen
import com.assetboard.ui.notes.NoteScreen
import com.assetboard.ui.notes.NoteSearchScreen
import com.assetboard.ui.notes.NoteTopicsScreen
import com.assetboard.ui.notes.NotesScreen
import com.assetboard.ui.overview.OverviewScreen
import com.assetboard.ui.record.FlowEditScreen
import com.assetboard.ui.record.ValuationEditScreen
import com.assetboard.ui.settings.GroupsScreen
import com.assetboard.ui.settings.PreRestoreScreen
import com.assetboard.ui.settings.ReminderGuideScreen
import com.assetboard.ui.settings.UpdatePrompt
import com.assetboard.ui.settings.SettingsScreen
import com.assetboard.ui.settings.TrashScreen
import com.assetboard.ui.stocktake.StocktakeScreen
import com.assetboard.ui.todo.TodoHomeScreen
import com.assetboard.ui.trend.TrendScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/**
 * Shared error plumbing: rule violations surface as a dialog, never as a crash. The data epoch is
 * bound when the ViewModel is created and travels with every write it launches, so a screen
 * opened before a restore cannot write into the restored data.
 */
open class ActionViewModel(container: AppContainer) : ViewModel() {
    val error = MutableStateFlow<String?>(null)

    protected val epoch: Epoch = container.gate.bound()

    /** viewModelScope plus the bound epoch; launch every write from here. */
    protected fun launchBound(block: suspend CoroutineScope.() -> Unit) = viewModelScope.launch(epoch, block = block)

    protected fun action(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch(epoch) {
            try {
                block()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LedgerException) {
                error.value = e.message
            } catch (e: Exception) {
                error.value = e.message ?: e.toString()
            }
        }
    }
}

object Routes {
    /** The four tabs, side by side in one pager. */
    const val HOME = "home"
    const val TREND = "trend"
    const val SETTINGS = "settings"
    const val GROUPS = "groups"
    const val TRASH = "trash"
    const val PRE_RESTORE = "pre_restore"
    const val REMINDER_GUIDE = "reminder_guide"
    const val DIARY_SEARCH = "diary_search"
    const val NOTE_SEARCH = "note_search"
    const val NOTE_TOPICS = "note_topics"
    /** A note's page; without [id] a new note, placed in [topicId] on its first write. */
    fun note(id: String? = null, topicId: String? = null) = "note?id=${id.orEmpty()}&topic=${topicId.orEmpty()}"
    fun health(record: Boolean = false) = "health?record=${if (record) "1" else ""}"
    fun stocktake(snapshotId: String? = null) = "stocktake?snapshotId=${snapshotId.orEmpty()}"
    fun account(id: String) = "account/$id"
    fun accountEdit(id: String? = null, groupId: String? = null) = "account_edit?id=${id.orEmpty()}&groupId=${groupId.orEmpty()}"
    fun valuation(accountId: String, id: String? = null) = "valuation?accountId=$accountId&id=${id.orEmpty()}"
    fun flow(accountId: String? = null, id: String? = null, kind: String? = null) =
        "flow?accountId=${accountId.orEmpty()}&id=${id.orEmpty()}&kind=${kind.orEmpty()}"
    fun diaryCalendar(date: String) = "diary_calendar?date=$date"
    /** A week's review page, by any day of the week. */
    fun week(day: java.time.LocalDate) = "week?start=$day"
}

/** Something outside asked for a page: a shortcut, a notification, the widget. */
sealed interface OpenRequest {
    data object Stocktake : OpenRequest
    data class Todo(val id: String) : OpenRequest
    data object TodoToday : OpenRequest
    data object Diary : OpenRequest
    data object QuickAdd : OpenRequest
}

/** The tabs in pager order. */
private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    ASSETS("资产", Icons.Outlined.AccountBalanceWallet, Icons.Filled.AccountBalanceWallet),
    TODO("待办", Icons.Outlined.TaskAlt, Icons.Filled.TaskAlt),
    DIARY("日记", Icons.Outlined.EditNote, Icons.Filled.EditNote),
    NOTES("笔记", Icons.Outlined.AutoStories, Icons.Filled.AutoStories),
}

private const val PICKED_DATE = "picked_date"

/** Finger speed along the bottom bar, px/s, past which a release goes on to the next tab. */
private const val BAR_FLICK = 700f

private fun optional(name: String) = navArgument(name) { type = NavType.StringType; defaultValue = "" }

private fun NavBackStackEntry.arg(name: String): String? = arguments?.getString(name)?.takeIf { it.isNotEmpty() }

/**
 * The app shell: four tabs in a pager (swipe the page, tap the bar or slide along it), pages pushed over them, rebuilt from scratch after every restore
 * (fresh ViewModels, fresh epoch). Cold start always lands on the assets tab.
 */
@Composable
fun AppNav(open: OpenRequest?, onOpenHandled: () -> Unit) {
    val container = LocalContainer.current
    val generation by container.restoreGeneration.collectAsStateWithLifecycle()
    val frozen by container.gate.frozen.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(container) {
        container.announcements.collect {
            container.announcementHandled()
            snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }
    val dust = remember { DustState() }
    val backdrop = rememberBackdrop()
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalDust provides dust) {
            Box(Modifier.fillMaxSize().backdropSource(backdrop)) { key(generation) { Shell(open, onOpenHandled) } }
        }
        // Above every page, so a deleted card's dust can drift over the rest of the list.
        DustHost(dust)
        GlassSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp), backdrop)
        UpdatePrompt()
        if (frozen) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = MaterialTheme.shapes.large) {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text("正在恢复数据…", modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Shell(open: OpenRequest?, onOpenHandled: () -> Unit) {
    val nav = rememberNavController()
    // Todo requests are handed to the todo tab, which shows them in place (sheet, view, composer).
    var todoRequest by remember { mutableStateOf<OpenRequest?>(null) }
    val pager = rememberPagerState { Tab.entries.size }
    val scope = rememberCoroutineScope()
    // Swiping is off while the diary is being typed in, so a sideways stroke never leaves the page.
    var diaryEditing by remember { mutableStateOf(false) }
    fun showTab(tab: Tab) {
        nav.popBackStack(Routes.HOME, inclusive = false)
        scope.launch { pager.scrollToPage(tab.ordinal) }
    }
    // A note's source: the diary tab, on that day. Only setting the date would leave it pending
    // until the diary page happened to be composed.
    val openDiary: (java.time.LocalDate) -> Unit = { d ->
        nav.getBackStackEntry(Routes.HOME).savedStateHandle[PICKED_DATE] = d.toString()
        showTab(Tab.DIARY)
    }
    val openNote: (String) -> Unit = { id -> nav.navigate(Routes.note(id)) }
    val openWeek: (java.time.LocalDate) -> Unit = { d -> nav.navigate(Routes.week(d)) }
    LaunchedEffect(open) {
        when (open) {
            null -> return@LaunchedEffect
            OpenRequest.Stocktake -> { showTab(Tab.ASSETS); nav.navigate(Routes.stocktake()) { launchSingleTop = true } }
            is OpenRequest.Todo, OpenRequest.TodoToday, OpenRequest.QuickAdd -> {
                showTab(Tab.TODO)
                todoRequest = open
            }
            OpenRequest.Diary -> showTab(Tab.DIARY)
        }
        onOpenHandled()
    }
    val back: () -> Unit = { nav.popBackStack() }
    val entry by nav.currentBackStackEntryAsState()
    val showBar = entry?.destination?.route == Routes.HOME
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    // A finger sliding along the bar drags the pages with it, a tab's width per page, the
    // highlight following the finger; let go, and it settles on the nearest tab.
    var barWidth by remember { mutableIntStateOf(0) }
    var barDragging by remember { mutableStateOf(false) }
    val barDrag = rememberDraggableState { d ->
        val tab = barWidth.toFloat() / Tab.entries.size
        val page = pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing
        if (tab > 0f) pager.dispatchRawDelta(d * page / tab)
    }
    LaunchedEffect(pager.currentPage) { if (barDragging) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }

    Scaffold(
        containerColor = cs.background,
        bottomBar = {
            if (showBar) {
                // A soft lift off the page instead of a hard edge; the selected tab gets the filled icon.
                NavigationBar(
                    containerColor = cs.surfaceContainer,
                    tonalElevation = 0.dp,
                    modifier = Modifier.shadow(16.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.10f))
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .onSizeChanged { barWidth = it.width }
                        .draggable(
                            barDrag, Orientation.Horizontal,
                            onDragStarted = {
                                pager.stopScroll(MutatePriority.UserInput)
                                barDragging = true
                            },
                            onDragStopped = { v ->
                                barDragging = false
                                val at = pager.currentPage + pager.currentPageOffsetFraction
                                val to = when {
                                    v > BAR_FLICK -> ceil(at)
                                    v < -BAR_FLICK -> floor(at)
                                    else -> round(at)
                                }.toInt().coerceIn(0, Tab.entries.lastIndex)
                                pager.animateScrollToPage(to)
                            },
                        ),
                ) {
                    Tab.entries.forEach { tab ->
                        val selected = pager.currentPage == tab.ordinal
                        NavigationBarItem(
                            selected = selected,
                            onClick = { scope.launch { pager.animateScrollToPage(tab.ordinal) } },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, null) },
                            label = { Text(tab.label, fontWeight = if (selected) FontWeight.SemiBold else null) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = cs.primary,
                                selectedTextColor = cs.primary,
                                indicatorColor = cs.primary.copy(alpha = 0.12f),
                                unselectedIconColor = cs.onSurfaceVariant,
                                unselectedTextColor = cs.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
            enterTransition = { slideInHorizontally { it / 5 } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally { it / 5 } + fadeOut() },
        ) {
            val settings = { nav.navigate(Routes.SETTINGS) }
            composable(Routes.HOME, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) { e ->
                // Back from another tab goes to the assets tab first, as the bar did before.
                BackHandler(pager.currentPage != Tab.ASSETS.ordinal) { scope.launch { pager.animateScrollToPage(Tab.ASSETS.ordinal) } }
                // Strokes over a row work the row; everything that is not a row turns the tabs.
                HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !(diaryEditing && pager.currentPage == Tab.DIARY.ordinal), key = { it }) { page ->
                    when (Tab.entries[page]) {
                        Tab.ASSETS -> OverviewScreen(
                            onOpenAccount = { nav.navigate(Routes.account(it)) },
                            onStocktake = { nav.navigate(Routes.stocktake()) },
                            onEditStocktake = { nav.navigate(Routes.stocktake(it)) },
                            onTrend = { nav.navigate(Routes.TREND) },
                            onSettings = settings,
                            onAddAccount = { groupId -> nav.navigate(Routes.accountEdit(groupId = groupId)) },
                            onHealth = { record -> nav.navigate(Routes.health(record)) },
                        )
                        Tab.TODO -> TodoHomeScreen(
                            onSettings = settings,
                            onGuide = { nav.navigate(Routes.REMINDER_GUIDE) },
                            request = todoRequest,
                            onRequestHandled = { todoRequest = null },
                        )
                        Tab.DIARY -> {
                            val picked by e.savedStateHandle.getStateFlow<String?>(PICKED_DATE, null).collectAsStateWithLifecycle()
                            DisposableEffect(Unit) { onDispose { diaryEditing = false } }
                            DiaryScreen(
                                pickedDate = picked,
                                onPickedHandled = { e.savedStateHandle[PICKED_DATE] = null },
                                onCalendar = { day -> nav.navigate(Routes.diaryCalendar(day.toString())) },
                                onSearch = { nav.navigate(Routes.DIARY_SEARCH) },
                                onSettings = settings,
                                onStocktake = { nav.navigate(Routes.stocktake()) },
                                // Leaving the tab ends the typing first; the focused text keeps the
                                // page composed, so its own dispose would never come.
                                active = pager.targetPage == Tab.DIARY.ordinal,
                                onEditingChange = { diaryEditing = it },
                                onOpenNote = openNote,
                                onOpenWeek = openWeek,
                            )
                        }
                        Tab.NOTES -> NotesScreen(
                            onOpen = openNote,
                            onNew = { topicId -> nav.navigate(Routes.note(topicId = topicId)) },
                            onSearch = { nav.navigate(Routes.NOTE_SEARCH) },
                            onTopics = { nav.navigate(Routes.NOTE_TOPICS) },
                            onSettings = settings,
                        )
                    }
                }
            }
            val pickDate: (java.time.LocalDate) -> Unit = { d ->
                nav.getBackStackEntry(Routes.HOME).savedStateHandle[PICKED_DATE] = d.toString()
                nav.popBackStack(Routes.HOME, inclusive = false)
            }
            composable("diary_calendar?date={date}", arguments = listOf(optional("date"))) {
                DiaryCalendarScreen(initial = it.arg("date"), onBack = back, onPick = pickDate, onWeek = openWeek)
            }
            composable(Routes.DIARY_SEARCH) { DiarySearchScreen(onBack = back, onPick = pickDate) }
            composable("note?id={id}&topic={topic}", arguments = listOf(optional("id"), optional("topic"))) {
                NoteScreen(onBack = back, onOpenDiary = openDiary, onOpenWeek = openWeek)
            }
            composable("week?start={start}", arguments = listOf(optional("start"))) {
                WeekScreen(onBack = back, onOpenDiary = openDiary, onOpenNote = openNote)
            }
            composable(Routes.NOTE_SEARCH) {
                // The results stay underneath: back from a note returns to them.
                NoteSearchScreen(onBack = back, onOpen = openNote)
            }
            composable(Routes.NOTE_TOPICS) { NoteTopicsScreen(onBack = back) }
            composable(Routes.TRASH) { TrashScreen(onBack = back) }
            composable(Routes.PRE_RESTORE) { PreRestoreScreen(onBack = back) }
            composable(Routes.REMINDER_GUIDE) { ReminderGuideScreen(onBack = back) }
            composable("stocktake?snapshotId={snapshotId}", arguments = listOf(optional("snapshotId"))) {
                StocktakeScreen(snapshotId = it.arg("snapshotId"), onBack = back)
            }
            composable("health?record={record}", arguments = listOf(optional("record"))) {
                HealthScreen(onBack = back, startRecording = it.arg("record") != null)
            }
            composable(Routes.TREND) {
                TrendScreen(
                    onBack = back,
                    onOpenStocktake = { id -> nav.navigate(Routes.stocktake(id)) },
                    onNewStocktake = { nav.navigate(Routes.stocktake()) },
                )
            }
            composable("account/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                val id = it.arg("id")!!
                AccountDetailScreen(
                    accountId = id,
                    onBack = back,
                    onEdit = { nav.navigate(Routes.accountEdit(id)) },
                    onNewValuation = { nav.navigate(Routes.valuation(id)) },
                    onOpenValuation = { vid -> nav.navigate(Routes.valuation(id, vid)) },
                    onNewFlow = { kind -> nav.navigate(Routes.flow(accountId = id, kind = kind)) },
                    onOpenFlow = { fid -> nav.navigate(Routes.flow(accountId = id, id = fid)) },
                    onOpenStocktake = { sid -> nav.navigate(Routes.stocktake(sid)) },
                )
            }
            composable("account_edit?id={id}&groupId={groupId}", arguments = listOf(optional("id"), optional("groupId"))) {
                AccountEditScreen(
                    accountId = it.arg("id"),
                    initialGroupId = it.arg("groupId"),
                    onBack = back,
                    onCreated = { newId ->
                        nav.popBackStack()
                        nav.navigate(Routes.account(newId))
                    },
                    onDeleted = { nav.popBackStack(Routes.HOME, inclusive = false) },
                    onRecordOutflow = { accountId -> nav.navigate(Routes.flow(accountId = accountId, kind = "EXTERNAL_OUT")) },
                )
            }
            composable("valuation?accountId={accountId}&id={id}", arguments = listOf(optional("accountId"), optional("id"))) {
                ValuationEditScreen(accountId = it.arg("accountId")!!, valuationId = it.arg("id"), onBack = back)
            }
            composable("flow?accountId={accountId}&id={id}&kind={kind}", arguments = listOf(optional("accountId"), optional("id"), optional("kind"))) {
                FlowEditScreen(accountId = it.arg("accountId"), flowId = it.arg("id"), initialKind = it.arg("kind"), onBack = back)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = back,
                    onGroups = { nav.navigate(Routes.GROUPS) },
                    onTrash = { nav.navigate(Routes.TRASH) },
                    onPreRestore = { nav.navigate(Routes.PRE_RESTORE) },
                    onGuide = { nav.navigate(Routes.REMINDER_GUIDE) },
                )
            }
            composable(Routes.GROUPS) {
                GroupsScreen(onBack = back)
            }
        }
    }
}
