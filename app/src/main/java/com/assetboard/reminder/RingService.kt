package com.assetboard.reminder

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * Rings for a strong reminder: alarm sound and vibration, a full-screen notification, at most
 * five minutes. Started from the alarm receiver (an exempt case) or from the alarm screen; never
 * from the boot broadcast, which Android 15 forbids for media-playback services.
 */
class RingService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var token: ReminderToken? = null
    private var title: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRinging()
            return START_NOT_STICKY
        }
        val t = intent?.let { ReminderToken.from(it) } ?: run { stopSelf(); return START_NOT_STICKY }
        val title = intent.getStringExtra(AlarmActivity.EXTRA_TITLE).orEmpty()
        // Started with startForegroundService: it must be in the foreground at once, whatever the
        // check below says, or the system kills the app.
        // Every such start needs it, also while another reminder rings: that one keeps its notification.
        val current = token?.takeIf { it.id != t.id }
        ServiceCompat.startForeground(
            this,
            Notifier.notificationId((current ?: t).id),
            if (current != null) Notifier.ringing(this, this.title, current) else Notifier.ringing(this, title, t),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        // Then checked before anything sounds: the todo may be done, edited or gone by now.
        val container = ReminderScheduler.container(this)
        container.appScope.launch {
            // The test from the guide names no todo: it rings whatever the database says.
            val live = t.id == ReminderScheduler.TEST_ID || container.reminders.stillLive(t)
            handler.post {
                when {
                    live -> ring(t, title)
                    // Nothing else is ringing: leave the foreground and stop.
                    token == null || token?.id == t.id -> stopRinging()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun ring(t: ReminderToken, title: String) {
        val previous = token
        if (previous != null && previous.id != t.id) {
            // A second strong reminder takes over; the first stays as an unhandled note.
            getSystemService(NotificationManager::class.java).notify(Notifier.notificationId(previous.id), Notifier.unhandled(this, this.title, previous))
        }
        token = t
        this.title = title
        ringingFor = t.id
        val n = Notifier.ringing(this, title, t)
        ServiceCompat.startForeground(
            this, Notifier.notificationId(t.id), n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        startSound()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ timeout() }, MAX_RING_MS)
    }

    private fun startSound() {
        if (player == null) {
            player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(this@RingService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                    isLooping = true
                    prepare()
                    start()
                }
            }.getOrNull()
        }
        if (vibrator == null) {
            vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        }
    }

    /** Five minutes unanswered: stop, leave a silent note. */
    private fun timeout() {
        val t = token
        stopRinging()
        if (t != null) getSystemService(NotificationManager::class.java).notify(Notifier.notificationId(t.id), Notifier.unhandled(this, title, t))
    }

    private fun stopRinging() {
        handler.removeCallbacksAndMessages(null)
        player?.runCatching { stop(); release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        ringingFor = null
        token = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        AlarmActivity.finishAll()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.runCatching { release() }
        vibrator?.cancel()
        ringingFor = null
        super.onDestroy()
    }

    companion object {
        const val MAX_RING_MS = 5 * 60_000L
        private const val ACTION_STOP = "stop"

        @Volatile var ringingFor: String? = null
            private set

        /** Falls back to the channel-sound notification when the system refuses the service. */
        fun start(context: Context, token: ReminderToken, title: String) {
            val intent = token.into(Intent(context, RingService::class.java)).putExtra(AlarmActivity.EXTRA_TITLE, title)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                val container = ReminderScheduler.container(context)
                container.appScope.launch {
                    val todo = container.todos.todo(token.id) ?: return@launch
                    context.getSystemService(NotificationManager::class.java)
                        .notify(Notifier.notificationId(token.id), Notifier.bootStrong(context, todo, token, 0))
                }
            }
        }

        fun stop(context: Context) {
            if (ringingFor == null) return
            runCatching { context.startService(Intent(context, RingService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
