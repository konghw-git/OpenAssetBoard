package com.assetboard.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.assetboard.MainActivity
import com.assetboard.R
import com.assetboard.data.Todo

/** Who a reminder or one of its buttons is about: the token checked before anything is written. */
data class ReminderToken(val id: String, val seq: Long, val epoch: Long) {
    fun into(intent: Intent): Intent = intent.putExtra(EXTRA_ID, id).putExtra(EXTRA_SEQ, seq).putExtra(EXTRA_EPOCH, epoch)

    companion object {
        const val EXTRA_ID = "todo_id"
        const val EXTRA_SEQ = "todo_seq"
        const val EXTRA_EPOCH = "data_epoch"

        fun from(intent: Intent): ReminderToken? {
            val id = intent.getStringExtra(EXTRA_ID) ?: return null
            if (!intent.hasExtra(EXTRA_SEQ) || !intent.hasExtra(EXTRA_EPOCH)) return null
            return ReminderToken(id, intent.getLongExtra(EXTRA_SEQ, -1), intent.getLongExtra(EXTRA_EPOCH, -1))
        }
    }
}

/** Notification channels and builders. Every notification about a todo uses [notificationId]. */
object Notifier {
    const val CH_REMINDER = "todo_reminder"
    const val CH_STRONG = "todo_strong"
    const val CH_STRONG_BOOT = "todo_strong_boot"
    const val CH_QUIET = "todo_quiet"
    const val CH_DIARY = "diary_reminder"

    const val EXTRA_SEQ = "posted_seq"
    const val DIARY_NOTIFICATION_ID = 7_100

    fun notificationId(todoId: String) = todoId.hashCode() and 0x3fffffff

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmAudio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_REMINDER, "待办提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "到点提醒，带完成和推迟按钮"
                },
                NotificationChannel(CH_STRONG, "强提醒（响铃中）", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "全屏响铃。铃声由 app 播放，这个渠道本身静音"
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(CH_STRONG_BOOT, "强提醒（开机补发）", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "重启后补发的强提醒，铃声由这个渠道播放"
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmAudio)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(CH_QUIET, "未处理与错过的提醒", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_DIARY, "日记提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "每晚提醒写日记"
                },
            ),
        )
    }

    fun openTodo(context: Context, todoId: String): PendingIntent = PendingIntent.getActivity(
        context,
        notificationId(todoId),
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("assetboard://todo/$todoId"))
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TODO)
            .putExtra(MainActivity.EXTRA_ID, todoId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun action(context: Context, action: String, token: ReminderToken): PendingIntent = PendingIntent.getBroadcast(
        context,
        notificationId(token.id),
        token.into(Intent(context, ReminderActionReceiver::class.java).setAction(action).setData(Uri.parse("assetboard://todo/${token.id}"))),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun fullScreen(context: Context, token: ReminderToken, title: String, fromBoot: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        notificationId(token.id),
        token.into(Intent(context, AlarmActivity::class.java))
            .setData(Uri.parse("assetboard://alarm/${token.id}"))
            .putExtra(AlarmActivity.EXTRA_TITLE, title)
            .putExtra(AlarmActivity.EXTRA_FROM_BOOT, fromBoot)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun lateText(lateMinutes: Long): String? = when {
        lateMinutes < 2 -> null
        lateMinutes < 60 -> "迟到 $lateMinutes 分钟"
        else -> "迟到 ${lateMinutes / 60} 小时 ${lateMinutes % 60} 分钟"
    }

    /** A reminder set to go off ahead says when the todo itself is: "14:00 开始". */
    private fun aheadText(t: Todo): String? = t.remindTime?.takeIf { t.remindBefore > 0 }?.let { "$it 开始" }

    /** The ordinary reminder: title, list name, and Done / 10 min / 1 h / Tomorrow. */
    fun reminder(context: Context, t: Todo, listName: String?, token: ReminderToken, lateMinutes: Long): Notification {
        val text = listOfNotNull(aheadText(t), listName ?: "收件箱", lateText(lateMinutes)).joinToString(" · ")
        return NotificationCompat.Builder(context, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(t.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openTodo(context, t.id))
            .setAutoCancel(true)
            .addExtras(android.os.Bundle().apply { putLong(EXTRA_SEQ, t.stateSeq) })
            .addAction(0, "完成", action(context, ReminderActionReceiver.COMPLETE, token))
            .addAction(0, "10 分钟后", action(context, ReminderActionReceiver.SNOOZE_10, token))
            .addAction(0, "1 小时后", action(context, ReminderActionReceiver.SNOOZE_60, token))
            .addAction(0, "明天", action(context, ReminderActionReceiver.TOMORROW, token))
            .build()
    }

    /** The ringing notification held by [RingService]; swiping it away means "10 minutes later". */
    fun ringing(context: Context, title: String, token: ReminderToken): Notification =
        NotificationCompat.Builder(context, CH_STRONG)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(title)
            .setContentText("强提醒 · 正在响铃")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen(context, token, title, fromBoot = false), true)
            .setContentIntent(fullScreen(context, token, title, fromBoot = false))
            .setDeleteIntent(action(context, ReminderActionReceiver.DISMISSED, token))
            .addExtras(android.os.Bundle().apply { putLong(EXTRA_SEQ, token.seq) })
            .addAction(0, "完成", action(context, ReminderActionReceiver.COMPLETE, token))
            .addAction(0, "10 分钟后", action(context, ReminderActionReceiver.SNOOZE_10, token))
            .addAction(0, "停止", action(context, ReminderActionReceiver.STOP, token))
            .build()

    /**
     * A strong reminder made up after a reboot: no foreground service may start from the boot
     * broadcast, so the channel plays the alarm sound, repeating until handled.
     */
    fun bootStrong(context: Context, t: Todo, token: ReminderToken, lateMinutes: Long): Notification =
        NotificationCompat.Builder(context, CH_STRONG_BOOT)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(t.title)
            .setContentText(listOfNotNull("强提醒", aheadText(t), lateText(lateMinutes)).joinToString(" · "))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen(context, token, t.title, fromBoot = true), true)
            .setContentIntent(fullScreen(context, token, t.title, fromBoot = true))
            .setDeleteIntent(action(context, ReminderActionReceiver.DISMISSED, token))
            .setTimeoutAfter(RingService.MAX_RING_MS)
            .addExtras(android.os.Bundle().apply { putLong(EXTRA_SEQ, t.stateSeq) })
            .addAction(0, "完成", action(context, ReminderActionReceiver.COMPLETE, token))
            .addAction(0, "10 分钟后", action(context, ReminderActionReceiver.SNOOZE_10, token))
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }

    /** After 5 minutes of ringing nobody answered: a silent note that stays. */
    fun unhandled(context: Context, title: String, token: ReminderToken): Notification =
        NotificationCompat.Builder(context, CH_QUIET)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(title)
            .setContentText("强提醒未处理")
            .setContentIntent(openTodo(context, token.id))
            .setAutoCancel(true)
            .addExtras(android.os.Bundle().apply { putLong(EXTRA_SEQ, token.seq) })
            .addAction(0, "完成", action(context, ReminderActionReceiver.COMPLETE, token))
            .addAction(0, "10 分钟后", action(context, ReminderActionReceiver.SNOOZE_10, token))
            .build()

    fun diary(context: Context): Notification =
        NotificationCompat.Builder(context, CH_DIARY)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle("今天还没写日记")
            .setContentText("花两分钟记下今天")
            .setContentIntent(
                PendingIntent.getActivity(
                    context, DIARY_NOTIFICATION_ID,
                    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DIARY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .build()
}
