package com.assetboard.reminder

import com.assetboard.domain.AppClock
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.assetboard.AppContainer
import com.assetboard.MainActivity
import com.assetboard.data.RestoreEffects
import com.assetboard.data.StaleAction
import com.assetboard.data.Todo
import com.assetboard.data.WriteRejected
import com.assetboard.domain.todo.ReminderPlan
import com.assetboard.domain.todo.ReminderState
import com.assetboard.domain.todo.TodoViews
import com.assetboard.widget.TodoWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A reminder that could not be shown: more than a day late, or notifications were off. */
@Serializable
data class MissedReminder(val todoId: String, val title: String, val at: Long, val notificationsOff: Boolean)

/**
 * Turns reminder state into alarms. Every alarm and button carries a [ReminderToken]; a token
 * that no longer matches the todo (state_seq) or the data (epoch) is dropped when it fires.
 */
class ReminderScheduler(private val context: Context, private val container: AppContainer) : RestoreEffects {
    private val am = context.getSystemService(AlarmManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val settings = container.settings
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    // Declared before [_missed]: its initializer reads the persisted list through this.
    private val missedSerializer = ListSerializer(MissedReminder.serializer())

    private val _missed = MutableStateFlow(loadMissed())
    val missed: StateFlow<List<MissedReminder>> = _missed.asStateFlow()

    // ------------------------------------------------------------ permissions

    /**
     * Whether [token] still names an open todo as this app knows it: the todo can have been
     * completed, edited or restored away while the alarm or the page was on its way.
     */
    suspend fun stillLive(token: ReminderToken): Boolean = runCatching {
        if (token.profile != container.profile.id || token.epoch != container.gate.epochNow()) return@runCatching false
        val t = container.todos.todo(token.id) ?: return@runCatching false
        t.stateSeq == token.seq && TodoViews.isOpen(t)
    }.getOrDefault(false)

    fun notificationsEnabled(): Boolean = nm.areNotificationsEnabled()

    /** The app's switch and, since Android 8, the channel's own: a channel set to "off" drops what is posted to it. */
    fun channelEnabled(channelId: String): Boolean {
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = runCatching { nm.getNotificationChannel(channelId) }.getOrNull() ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    fun fullScreenAllowed(): Boolean = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()

    // ------------------------------------------------------------ entry points

    /** After every todo write: reschedule those todos and withdraw their stale notifications. */
    fun onTodosChanged(ids: Set<String>) {
        container.appScope.launch { runCatching { sync(ids, fromBoot = false) } }
    }

    suspend fun onAppStart() {
        Notifier.ensureChannels(context)
        if (settings.rescheduleNeeded()) settings.setRescheduleNeeded(false)
        syncAll(fromBoot = false)
        scheduleDiaryReminder()
    }

    /** Boot, time change, exact-alarm grant, app start, after a restore. */
    suspend fun syncAll(fromBoot: Boolean) {
        val ids = container.todos.loadOnce().todos.map { it.id }.toSet()
        sync(ids, fromBoot)
    }

    override suspend fun afterRestore(oldTodoIds: List<String>) {
        cancelEverything(oldTodoIds)
        syncAll(fromBoot = false)
        scheduleDiaryReminder()
        TodoWidget.refresh(context)
    }

    /** Before another profile's database opens: nothing from this one may ring or stay posted. */
    fun cancelEverything(oldTodoIds: List<String>) {
        RingService.stop(context)
        nm.cancelAll()
        for (id in oldTodoIds) cancelAlarm(id)
        cancelDiaryReminder()
        cancelTestAlarms()
    }

    private suspend fun sync(ids: Set<String>, fromBoot: Boolean) = lock.withLock {
        // A background task binds the epoch when it starts.
        val epoch = container.gate.bound()
        withContext(epoch) {
            val now = AppClock.millis()
            for (id in ids) {
                val t = container.todos.todo(id)
                syncOne(id, t, now, epoch.value, fromBoot)
            }
        }
    }

    private suspend fun syncOne(id: String, t: Todo?, now: Long, epoch: Long, fromBoot: Boolean) {
        cancelAlarm(id)
        withdrawIfStale(id, t)
        if (t == null || !TodoViews.isOpen(t)) return
        when (val plan = ReminderState.plan(t, now)) {
            ReminderPlan.None -> Unit
            is ReminderPlan.At -> setAlarm(t, plan.at, ReminderToken(t.id, t.stateSeq, epoch, container.profile.id))
            is ReminderPlan.Late -> deliver(t, ReminderToken(t.id, t.stateSeq, epoch, container.profile.id), plan.lateMinutes, fromBoot)
            is ReminderPlan.Missed -> {
                addMissed(MissedReminder(t.id, t.title, plan.at, notificationsOff = false))
                markReminded(t)
            }
        }
    }

    /** A posted notification whose buttons carry an outdated state_seq is withdrawn. */
    private fun withdrawIfStale(id: String, t: Todo?) {
        val nid = Notifier.notificationId(id)
        val posted = nm.activeNotifications.firstOrNull { it.id == nid } ?: return
        val seq = posted.notification.extras.getLong(Notifier.EXTRA_SEQ, -1)
        if (t == null || t.deletedAt != null || seq != t.stateSeq) {
            nm.cancel(nid)
            if (RingService.ringingFor == id) RingService.stop(context)
        }
    }

    /**
     * Called by the alarm receiver once the token checked out. An alarm the system held back for
     * more than a day is written off as missed, as [syncOne] would have.
     */
    suspend fun fire(token: ReminderToken) = lock.withLock {
        if (token.profile != container.profile.id) return@withLock
        val t = container.todos.todo(token.id) ?: return@withLock
        if (token.epoch != container.gate.epochNow() || t.stateSeq != token.seq || !TodoViews.isOpen(t)) return@withLock
        val now = AppClock.millis()
        withContext(com.assetboard.data.Epoch(token.epoch)) {
            when (val plan = ReminderState.plan(t, now)) {
                ReminderPlan.None -> Unit
                // Up to a minute early counts as on time.
                is ReminderPlan.At -> if (plan.at > now + 60_000) setAlarm(t, plan.at, token) else deliver(t, token, 0, fromBoot = false)
                is ReminderPlan.Late -> deliver(t, token, plan.lateMinutes, fromBoot = false)
                is ReminderPlan.Missed -> {
                    addMissed(MissedReminder(t.id, t.title, plan.at, notificationsOff = false))
                    markReminded(t)
                }
            }
        }
    }

    private suspend fun deliver(t: Todo, token: ReminderToken, lateMinutes: Long, fromBoot: Boolean) {
        // A blocked channel drops the notification, so the period would be lost; it goes missed instead.
        val canPost = if (!t.strongRemind) channelEnabled(Notifier.CH_REMINDER) else fromBoot && channelEnabled(Notifier.CH_STRONG_BOOT)
        if (!canPost && !(t.strongRemind && !fromBoot && notificationsEnabled())) {
            addMissed(MissedReminder(t.id, t.title, ReminderState.pending(t)?.first ?: AppClock.millis(), notificationsOff = true))
        } else if (t.strongRemind) {
            if (fromBoot) {
                nm.notify(Notifier.notificationId(t.id), Notifier.bootStrong(context, t, token, lateMinutes))
                scheduleUnhandled(token, t.title)
            } else {
                RingService.start(context, token, t.title)
            }
        } else {
            val listName = t.listId?.let { container.todos.loadOnce().listById[it]?.name }
            nm.notify(Notifier.notificationId(t.id), Notifier.reminder(context, t, listName, token, lateMinutes))
        }
        markReminded(t)
    }

    private suspend fun markReminded(t: Todo) {
        try {
            container.todos.markReminded(t.id, t.stateSeq)
        } catch (e: StaleAction) {
            // The todo moved on meanwhile; its own change rescheduled it.
        } catch (e: WriteRejected) {
            // A restore is running; it reschedules everything after committing.
        }
    }

    // ------------------------------------------------------------ alarms

    private fun alarmIntent(id: String, token: ReminderToken?, flags: Int): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .setData(Uri.parse("assetboard://todo/$id"))
        token?.into(intent)
        return PendingIntent.getBroadcast(context, Notifier.notificationId(id), intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelAlarm(id: String) {
        alarmIntent(id, null, PendingIntent.FLAG_NO_CREATE)?.let {
            am.cancel(it)
            it.cancel()
        }
        cancelUnhandledAlarm(id)
    }

    private fun cancelUnhandledAlarm(id: String) {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(ReminderActionReceiver.UNHANDLED)
            .setData(Uri.parse("assetboard://unhandled/$id"))
        PendingIntent.getBroadcast(
            context, Notifier.notificationId(id) xor 0x1000, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let {
            am.cancel(it)
            it.cancel()
        }
    }

    private fun setAlarm(t: Todo, at: Long, token: ReminderToken) {
        val pi = alarmIntent(t.id, token, PendingIntent.FLAG_UPDATE_CURRENT)!!
        val exact = exactAllowed()
        when {
            exact && (t.strongRemind || settings.allRemindersAsAlarms.value) -> {
                val show = PendingIntent.getActivity(
                    context, Notifier.notificationId(t.id),
                    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TODO).putExtra(MainActivity.EXTRA_ID, t.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            }
            exact -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else -> am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pi)
        }
    }

    /** The boot reminder was taken over by its page or answered: nothing left to write off in five minutes. */
    fun cancelUnhandled(token: ReminderToken) {
        unhandledIntent(token, "")?.let {
            am.cancel(it)
            it.cancel()
        }
    }

    private fun unhandledIntent(token: ReminderToken, title: String): PendingIntent? =
        PendingIntent.getBroadcast(
            context, Notifier.notificationId(token.id) xor 0x1000,
            token.into(Intent(context, ReminderActionReceiver::class.java).setAction(ReminderActionReceiver.UNHANDLED).setData(Uri.parse("assetboard://unhandled/${token.id}")))
                .putExtra(AlarmActivity.EXTRA_TITLE, title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Five minutes after a boot-time strong reminder: swap it for a silent "unhandled" note. */
    private fun scheduleUnhandled(token: ReminderToken, title: String) {
        val pi = unhandledIntent(token, title) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, AppClock.millis() + RingService.MAX_RING_MS, pi)
    }

    // ------------------------------------------------------------ test from the guide

    /** A reminder 15 seconds from now that touches no data; it shows exactly like a real one. */
    fun scheduleTest(strong: Boolean) {
        val token = ReminderToken(TEST_ID, 0, container.gate.epochNow(), container.profile.id)
        val intent = token.into(Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_TEST).setData(Uri.parse("assetboard://test/$strong")))
            .putExtra(ReminderReceiver.EXTRA_STRONG, strong)
        val pi = PendingIntent.getBroadcast(context, if (strong) 7_201 else 7_200, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = AppClock.millis() + 15_000
        if (exactAllowed() && strong) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi), pi)
        } else if (exactAllowed()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, pi)
        }
    }

    fun fireTest(strong: Boolean) {
        val token = ReminderToken(TEST_ID, 0, container.gate.epochNow(), container.profile.id)
        if (strong) {
            RingService.start(context, token, "测试强提醒")
        } else {
            val t = Todo(TEST_ID, title = "测试提醒", createdAt = 0)
            nm.notify(Notifier.notificationId(TEST_ID), Notifier.reminder(context, t, "提醒设置检查", token, 0))
        }
    }

    // ------------------------------------------------------------ diary reminder

    fun scheduleDiaryReminder() {
        val pi = PendingIntent.getBroadcast(
            context, Notifier.DIARY_NOTIFICATION_ID,
            Intent(context, DiaryReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(pi)
        if (!settings.diaryReminderEnabled.value) return
        val minutes = settings.diaryReminderMinutes.value
        val zone = ZoneId.systemDefault()
        val now = AppClock.millis()
        val today = AppClock.today().atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(zone).toInstant().toEpochMilli()
        // Tomorrow by the calendar, not by 24 hours: a day is 23 or 25 hours when the clocks change.
        val at = if (today <= now) java.time.Instant.ofEpochMilli(today).atZone(zone).plusDays(1).toInstant().toEpochMilli() else today
        am.setWindow(AlarmManager.RTC_WAKEUP, at, 5 * 60_000L, pi)
    }

    fun cancelDiaryReminder() {
        PendingIntent.getBroadcast(
            context, Notifier.DIARY_NOTIFICATION_ID,
            Intent(context, DiaryReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let {
            am.cancel(it)
            it.cancel()
        }
    }

    private fun cancelTestAlarms() {
        for ((code, strong) in listOf(7_200 to false, 7_201 to true)) {
            val intent = Intent(context, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_TEST)
                .setData(Uri.parse("assetboard://test/$strong"))
            PendingIntent.getBroadcast(
                context, code, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let {
                am.cancel(it)
                it.cancel()
            }
        }
    }

    // ------------------------------------------------------------ missed reminders

    private fun loadMissed(): List<MissedReminder> =
        settings.string(KEY_MISSED)?.let { runCatching { json.decodeFromString(missedSerializer, it) }.getOrNull() }.orEmpty()

    @Synchronized
    fun addMissed(m: MissedReminder) {
        val next = (_missed.value.filterNot { it.todoId == m.todoId && it.at == m.at } + m).takeLast(50)
        settings.putString(KEY_MISSED, json.encodeToString(missedSerializer, next))
        _missed.value = next
    }

    @Synchronized
    fun clearMissed() {
        settings.putString(KEY_MISSED, null)
        _missed.value = emptyList()
    }

    fun refreshMissed() {
        _missed.value = loadMissed()
    }

    companion object {
        private const val KEY_MISSED = "missed_reminders"
        const val TEST_ID = "__reminder_test__"

        fun container(context: Context): AppContainer = (context.applicationContext as com.assetboard.AssetBoardApp).container

        fun hasNotificationPermission(context: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
