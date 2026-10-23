package com.assetboard.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.assetboard.AssetBoardApp
import com.assetboard.MainActivity
import com.assetboard.domain.Dates
import com.assetboard.domain.Ledger
import com.assetboard.domain.Money

/** Home-screen card: net worth, last stocktake, and a button straight into a new stocktake. */
class NetWorthWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(NARROW, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as AssetBoardApp).container
        val ledger = container.repository.loadOnce()
        val totals = ledger.currentTotals()
        val lastAt = ledger.snapshots.maxOfOrNull { it.at }
        // The overview's eye: hidden there, hidden on the home screen too.
        val hide = container.settings.hideAmounts.value
        provideContent {
            GlanceTheme { Content(totals, lastAt, hide) }
        }
    }

    companion object {
        private val NARROW = DpSize(160.dp, 80.dp)
        private val WIDE = DpSize(260.dp, 100.dp)
        val OpenKey = ActionParameters.Key<String>(com.assetboard.MainActivity.EXTRA_OPEN)

        suspend fun refresh(context: Context) {
            runCatching { NetWorthWidget().updateAll(context) }
        }
    }
}

@Composable
private fun Content(totals: Ledger.Totals, lastAt: Long?, hide: Boolean) {
    fun amount(text: String) = if (hide) com.assetboard.ui.components.MASKED else text
    val colors = GlanceTheme.colors
    val wide = LocalSize.current.width >= 240.dp
    Row(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(24.dp)
            .background(colors.primaryContainer)
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(GlanceModifier.defaultWeight()) {
            Text("净资产", style = TextStyle(color = colors.onPrimaryContainer, fontSize = 12.sp))
            Text(
                amount("¥" + Money.format(totals.netWorth)),
                style = TextStyle(color = colors.onPrimaryContainer, fontSize = if (wide) 24.sp else 20.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            if (wide) {
                Text(
                    "资产 ${amount(Money.short(totals.assets))} · 负债 ${amount(Money.short(-totals.liabilities))}",
                    style = TextStyle(color = colors.onPrimaryContainer, fontSize = 11.sp),
                    maxLines = 1,
                )
            }
            Text(
                lastAt?.let { "上次盘点 " + Dates.relative(it) } ?: "还没有盘点",
                style = TextStyle(color = colors.onPrimaryContainer, fontSize = 11.sp),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(8.dp))
        Box(
            GlanceModifier
                .size(52.dp)
                .cornerRadius(26.dp)
                .background(colors.primary)
                .clickable(actionStartActivity<MainActivity>(actionParametersOf(NetWorthWidget.OpenKey to MainActivity.OPEN_STOCKTAKE))),
            contentAlignment = Alignment.Center,
        ) {
            Text("盘点", style = TextStyle(color = colors.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        }
    }
}

class NetWorthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NetWorthWidget()
}
