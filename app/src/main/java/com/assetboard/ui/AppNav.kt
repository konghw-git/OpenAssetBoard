package com.assetboard.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.assetboard.AppContainer
import com.assetboard.domain.LedgerException
import com.assetboard.ui.account.AccountDetailScreen
import com.assetboard.ui.account.AccountEditScreen
import com.assetboard.ui.overview.OverviewScreen
import com.assetboard.ui.record.FlowEditScreen
import com.assetboard.ui.record.ValuationEditScreen
import com.assetboard.ui.settings.GroupsScreen
import com.assetboard.ui.settings.SettingsScreen
import com.assetboard.ui.stocktake.StocktakeScreen
import com.assetboard.ui.trend.TrendScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Shared error plumbing: rule violations surface as a dialog, never as a crash. */
open class ActionViewModel : ViewModel() {
    val error = MutableStateFlow<String?>(null)

    protected fun action(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
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
    const val TREND = "trend"
    const val SETTINGS = "settings"
    const val GROUPS = "groups"
    fun stocktake(snapshotId: String? = null) = "stocktake?snapshotId=${snapshotId.orEmpty()}"
    fun account(id: String) = "account/$id"
    fun accountEdit(id: String? = null, groupId: String? = null) = "account_edit?id=${id.orEmpty()}&groupId=${groupId.orEmpty()}"
    fun valuation(accountId: String, id: String? = null) = "valuation?accountId=$accountId&id=${id.orEmpty()}"
    fun flow(accountId: String? = null, id: String? = null, kind: String? = null) =
        "flow?accountId=${accountId.orEmpty()}&id=${id.orEmpty()}&kind=${kind.orEmpty()}"
}

private fun optional(name: String) = navArgument(name) { type = NavType.StringType; defaultValue = "" }

private fun NavBackStackEntry.arg(name: String): String? = arguments?.getString(name)?.takeIf { it.isNotEmpty() }

@Composable
fun AppNav(openStocktake: Boolean, onOpenHandled: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(openStocktake) {
        if (openStocktake) {
            nav.navigate(Routes.stocktake()) { launchSingleTop = true }
            onOpenHandled()
        }
    }
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(
        navController = nav,
        startDestination = Routes.OVERVIEW,
        enterTransition = { slideInHorizontally { it / 5 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 5 } + fadeOut() },
    ) {
        composable(Routes.OVERVIEW) {
            OverviewScreen(
                onOpenAccount = { nav.navigate(Routes.account(it)) },
                onStocktake = { nav.navigate(Routes.stocktake()) },
                onEditStocktake = { nav.navigate(Routes.stocktake(it)) },
                onTrend = { nav.navigate(Routes.TREND) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onAddAccount = { groupId -> nav.navigate(Routes.accountEdit(groupId = groupId)) },
            )
        }
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
            SettingsScreen(onBack = back, onGroups = { nav.navigate(Routes.GROUPS) })
        }
        composable(Routes.GROUPS) {
            GroupsScreen(onBack = back)
        }
    }
}
