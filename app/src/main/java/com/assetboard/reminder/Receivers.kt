package com.assetboard.reminder

import com.assetboard.domain.AppClock
import android.app.AlarmManager
import android.app.NotificationManager
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.assetboard.data.Epoch
import com.assetboard.domain.LedgerException
import com.assetboard.domain.todo.TodoViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Runs [block] off the main thread while keeping the broadcast alive. */
private fun BroadcastReceiver.async(context: Context, block: suspend () -> Unit) {
    val pending = goAsync()
    ReminderScheduler.container(context).appScope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/** An alarm went off. The token is checked against the todo and the epoch before anything shows. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = ReminderToken.from(intent) ?: return
        if (intent.action == ACTION_TEST) {
            val container = ReminderScheduler.container(context)
            if (token.profile == container.profile.id) container.reminders.fireTest(intent.getBooleanExtra(EXTRA_STRONG, false))
            return
        }
        async(context) { runCatching { ReminderScheduler.container(context).reminders.fire(token) } }
    }

    companion object {
        const val ACTION_FIRE = "com.assetboard.REMINDER_FIRE"
        const val ACTION_TEST = "com.assetboard.REMINDER_TEST"
        const val EXTRA_STRONG = "strong"
    }
}

/** Buttons on reminder notifications and on the alarm screen. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = ReminderToken.from(intent) ?: return
        val action = intent.action ?: return
        async(context) { handle(context, action, token, intent) }
    }

    companion object {
        const val COMPLETE = "com.assetboard.TODO_COMPLETE"
        const val SNOOZE_10 = "com.assetboard.TODO_SNOOZE_10"
        const val SNOOZE_60 = "com.assetboard.TODO_SNOOZE_60"
        const val TOMORROW = "com.assetboard.TODO_TOMORROW"
        const val STOP = "com.assetboard.TODO_STOP"
        /** The ringing notification was swiped away: stop, and ring again in 10 minutes. */
        const val DISMISSED = "com.assetboard.TODO_DISMISSED"
        const val UNHANDLED = "com.assetboard.TODO_UNHANDLED"

        suspend fun handle(context: Context, action: String, token: ReminderToken, intent: Intent? = null) {
            val container = ReminderScheduler.container(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            val nid = Notifier.notificationId(token.id)
            if (token.profile != container.profile.id) {
                // A notification belonging to a profile that is no longer open.
                nm.cancel(nid)
                return
            }
            if (token.id == ReminderScheduler.TEST_ID) {
                nm.cancel(nid)
                return
            }
            if (action == UNHANDLED) {
                // The boot-time note may already have timed out (both are five minutes), so it is
                // not required to still be up: an open todo nobody answered gets the silent note
                // either way. A page that took the reminder over has cancelled this timer.
                val t = container.todos.todo(token.id)
                val open = t != null && t.stateSeq == token.seq && TodoViews.isOpen(t)
                // A ring already going (the page opened just as this fired) is left alone.
                if (open && t != null && RingService.ringingFor != token.id && token.epoch == container.gate.epochNow()) {
                    nm.notify(nid, Notifier.unhandled(context, t.title, token))
                }
                return
            }
            if (RingService.ringingFor == token.id) RingService.stop(context)
            if (action == STOP) {
                nm.cancel(nid)
                return
            }
            nm.cancel(nid)
            // The epoch comes from the token, never from the gate at this moment.
            val result = runCatching {
                withContext(Epoch(token.epoch)) {
                    val todos = container.todos
                    when (action) {
                        COMPLETE -> todos.complete(token.id, token.seq)
                        SNOOZE_10, DISMISSED -> todos.snooze(token.id, token.seq, 10)
                        SNOOZE_60 -> todos.snooze(token.id, token.seq, 60)
                        TOMORROW -> todos.snoozeTomorrow(token.id, token.seq)
                    }
                }
            }
            result.exceptionOrNull()?.let { e ->
                val msg = (e as? LedgerException)?.message ?: "操作失败"
                withContext(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
            }
        }
    }
}

/** Boot, app update, clock and time zone changes, exact-alarm permission granted. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return // the database is not readable before unlock
        val known = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
        if (intent.action !in known) return
        async(context) {
            val r = ReminderScheduler.container(context).reminders
            Notifier.ensureChannels(context)
            runCatching { r.syncAll(fromBoot = boot) }
            runCatching { r.scheduleDiaryReminder() }
            // A new day or zone may have come with the change; alarms do not survive a reboot.
            com.assetboard.widget.WidgetDay.schedule(context)
            com.assetboard.widget.WidgetRefresh.all(context)
        }
    }
}

/** 22:00 by default: a nudge to write, unless today's entry already has text. */
class DiaryReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        async(context) {
            val container = ReminderScheduler.container(context)
            val entry = runCatching { container.diary.entryOn(AppClock.today()) }.getOrNull()
            if (container.settings.diaryReminderEnabled.value && needsNudge(entry?.body)) {
                val nm = context.getSystemService(NotificationManager::class.java)
                val channel = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) null else nm.getNotificationChannel(Notifier.CH_DIARY)
                if (nm.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE) {
                    nm.notify(Notifier.DIARY_NOTIFICATION_ID, Notifier.diary(context))
                }
            }
            container.reminders.scheduleDiaryReminder()
        }
    }

    companion object {
        /** No reminder once today's entry has any text. */
        fun needsNudge(body: String?): Boolean = body.isNullOrBlank()
    }
}
