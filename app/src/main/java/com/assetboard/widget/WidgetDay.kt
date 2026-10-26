package com.assetboard.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.assetboard.domain.AppClock
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The day turning over on the home screen: today's todos, the days left, "3 天前". An inexact,
 * non-waking alarm just after midnight; a sleeping phone gets it when it wakes, which is when the
 * widgets are next looked at anyway. Set again each time it fires, on app start and on time changes.
 */
object WidgetDay {
    private const val WINDOW_MS = 10 * 60_000L

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val next = AppClock.today().plusDays(1).atTime(0, 0, 20).atZone(zone).toInstant().toEpochMilli()
        runCatching { am.setWindow(AlarmManager.RTC, next, WINDOW_MS, pending(context)) }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, WidgetDayReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class WidgetDayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.container.appScope.launch {
            try {
                WidgetRefresh.all(context.applicationContext)
                WidgetDay.schedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}
