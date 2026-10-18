package com.assetboard.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.AccountBalanceWallet
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
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
import com.assetboard.ui.account.AccountEditScreen
import com.assetboard.ui.diary.DiaryCalendarScreen
import com.assetboard.ui.diary.DiaryScreen
import com.assetboard.ui.diary.DiarySearchScreen
import com.assetboard.ui.overview.OverviewScreen
import com.assetboard.ui.record.FlowEditScreen
import com.assetboard.ui.record.ValuationEditScreen
import com.assetboard.ui.settings.GroupsScreen
import com.assetboard.ui.settings.PreRestoreScreen
import com.assetboard.ui.settings.ReminderGuideScreen
import com.assetboard.ui.settings.SettingsScreen
import com.assetboard.ui.settings.TrashScreen
import com.assetboard.ui.stocktake.StocktakeScreen
import com.assetboard.ui.todo.TodoHomeScreen
import com.assetboard.ui.trend.TrendScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
    const val OVERVIEW = "overview"
    const val TODO_HOME = "todo_home"
    const val DIARY = "diary"
    const val TREND = "trend"
    const val SETTINGS = "settings"
    const val GROUPS = "groups"
    const val TRASH = "trash"
    const val PRE_RESTORE = "pre_restore"
    const val REMINDER_GUIDE = "reminder_guide"
    const val DIARY_SEARCH = "diary_search"
    fun stocktake(snapshotId: String? = null) = "stocktake?snapshotId=${snapshotId.orEmpty()}"
    fun account(id: String) = "account/$id"
    fun accountEdit(id: String? = null, groupId: String? = null) = "account_edit?id=${id.orEmpty()}&groupId=${groupId.orEmpty()}"
    fun valuation(accountId: String, id: String? = null) = "valuation?accountId=$accountId&id=${id.orEmpty()}"
    fun flow(accountId: String? = null, id: String? = null, kind: String? = null) =
        "flow?accountId=${accountId.orEmpty()}&id=${id.orEmpty()}&kind=${kind.orEmpty()}"
    fun diaryCalendar(date: String) = "diary_calendar?date=$date"
}

/** Something outside asked for a page: a shortcut, a notification, the widget. */
sealed interface OpenRequest {
    data object Stocktake : OpenRequest
    data class Todo(val id: String) : OpenRequest
    data object TodoToday : OpenRequest
    data object Diary : OpenRequest
    data object QuickAdd : OpenRequest
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    ASSETS(Routes.OVERVIEW, "资产", Icons.Outlined.AccountBalanceWallet, Icons.Filled.AccountBalanceWallet),
    TODO(Routes.TODO_HOME, "待办", Icons.Outlined.TaskAlt, Icons.Filled.TaskAlt),
    DIARY(Routes.DIARY, "日记", Icons.Outlined.EditNote, Icons.Filled.EditNote),
}

private const val PICKED_DATE = "picked_date"

private fun optional(name: String) = navArgument(name) { type = NavType.StringType; defaultValue = "" }

private fun NavBackStackEntry.arg(name: String): String? = arguments?.getString(name)?.takeIf { it.isNotEmpty() }

/** Switches tabs the Material way: each tab keeps its own stack. */
private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/**
 * The app shell: three tabs, each with its own stack, rebuilt from scratch after every restore
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
    Box(Modifier.fillMaxSize()) {
        key(generation) { Shell(open, onOpenHandled) }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
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
    LaunchedEffect(open) {
        when (open) {
            null -> return@LaunchedEffect
            OpenRequest.Stocktake -> { nav.switchTab(Routes.OVERVIEW); nav.navigate(Routes.stocktake()) { launchSingleTop = true } }
            is OpenRequest.Todo, OpenRequest.TodoToday, OpenRequest.QuickAdd -> {
                nav.popBackStack(Routes.TODO_HOME, inclusive = false)
                nav.switchTab(Routes.TODO_HOME)
                todoRequest = open
            }
            OpenRequest.Diary -> nav.switchTab(Routes.DIARY)
        }
        onOpenHandled()
    }
    val back: () -> Unit = { nav.popBackStack() }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = Tab.entries.any { it.route == route }
    val cs = MaterialTheme.colorScheme

    Scaffold(
        containerColor = cs.background,
        bottomBar = {
            if (showBar) {
                // A soft lift off the page instead of a hard edge; the selected tab gets the filled icon.
                NavigationBar(
                    containerColor = cs.surfaceContainer,
                    tonalElevation = 0.dp,
                    modifier = Modifier.shadow(16.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.10f))
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                ) {
                    Tab.entries.forEach { tab ->
                        val selected = entry?.destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.switchTab(tab.route) },
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
            startDestination = Routes.OVERVIEW,
            modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
            enterTransition = { slideInHorizontally { it / 5 } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally { it / 5 } + fadeOut() },
        ) {
            val settings = { nav.navigate(Routes.SETTINGS) }
            composable(Routes.OVERVIEW, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
                OverviewScreen(
                    onOpenAccount = { nav.navigate(Routes.account(it)) },
                    onStocktake = { nav.navigate(Routes.stocktake()) },
                    onEditStocktake = { nav.navigate(Routes.stocktake(it)) },
                    onTrend = { nav.navigate(Routes.TREND) },
                    onSettings = settings,
                    onAddAccount = { groupId -> nav.navigate(Routes.accountEdit(groupId = groupId)) },
                )
            }
            composable(Routes.TODO_HOME, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
                TodoHomeScreen(
                    onSettings = settings,
                    onGuide = { nav.navigate(Routes.REMINDER_GUIDE) },
                    request = todoRequest,
                    onRequestHandled = { todoRequest = null },
                )
            }
            composable(Routes.DIARY, enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) { e ->
                val picked by e.savedStateHandle.getStateFlow<String?>(PICKED_DATE, null).collectAsStateWithLifecycle()
                DiaryScreen(
                    pickedDate = picked,
                    onPickedHandled = { e.savedStateHandle[PICKED_DATE] = null },
                    onCalendar = { nav.navigate(Routes.diaryCalendar(java.time.LocalDate.now().toString())) },
                    onSearch = { nav.navigate(Routes.DIARY_SEARCH) },
                    onSettings = settings,
                    onStocktake = { nav.navigate(Routes.stocktake()) },
                )
            }
            val pickDate: (java.time.LocalDate) -> Unit = { d ->
                nav.getBackStackEntry(Routes.DIARY).savedStateHandle[PICKED_DATE] = d.toString()
                nav.popBackStack(Routes.DIARY, inclusive = false)
            }
            composable("diary_calendar?date={date}", arguments = listOf(optional("date"))) {
                DiaryCalendarScreen(initial = it.arg("date"), onBack = back, onPick = pickDate)
            }
            composable(Routes.DIARY_SEARCH) { DiarySearchScreen(onBack = back, onPick = pickDate) }
            composable(Routes.TRASH) { TrashScreen(onBack = back) }
            composable(Routes.PRE_RESTORE) { PreRestoreScreen(onBack = back) }
            composable(Routes.REMINDER_GUIDE) { ReminderGuideScreen(onBack = back) }
            composable("stocktake?snapshotId={snapshotId}", arguments = listOf(optional("snapshotId"))) {
                StocktakeScreen(snapshotId = it.arg("snapshotId"), onBack = back)
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
                    onDeleted = { nav.popBackStack(Routes.OVERVIEW, inclusive = false) },
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
