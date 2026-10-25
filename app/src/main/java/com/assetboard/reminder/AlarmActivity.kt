package com.assetboard.reminder

import com.assetboard.domain.AppClock
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetboard.domain.Dates
import com.assetboard.ui.theme.AssetBoardTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * The full-screen alarm, shown over the lock screen. When it was opened from a boot-time
 * notification (no service ringing yet), it takes the ringing over from the notification channel.
 */
class AlarmActivity : ComponentActivity() {
    /** The alarm on screen; replaced when a second alarm reuses this single instance. */
    private var shown by mutableStateOf<Pair<ReminderToken, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        current = WeakReference(this)
        if (!show(intent, takeOverRinging = savedInstanceState == null)) return
        val container = ReminderScheduler.container(this)
        fun act(action: String) {
            // The token on screen at the moment of the tap, not the one the page opened with.
            val token = shown?.first ?: return
            container.appScope.launch { ReminderActionReceiver.handle(applicationContext, action, token) }
            finish()
        }
        setContent {
            val theme by container.settings.themeMode.collectAsState()
            AssetBoardTheme(theme, upIsRed = true) {
                AlarmContent(shown?.second.orEmpty(), onDone = { act(ReminderActionReceiver.COMPLETE) }, onSnooze = { act(ReminderActionReceiver.SNOOZE_10) }, onStop = { act(ReminderActionReceiver.STOP) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A second alarm while this one is still up: the page now belongs to the new todo.
        if (ReminderToken.from(intent) == null) return
        setIntent(intent)
        show(intent, takeOverRinging = true)
    }

    /** Binds the page to [intent]'s todo; false (and finishing) when it carries none. */
    private fun show(intent: Intent, takeOverRinging: Boolean): Boolean {
        val token = ReminderToken.from(intent) ?: run { finish(); return false }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (intent.getBooleanExtra(EXTRA_FROM_BOOT, false) && takeOverRinging) {
            val container = ReminderScheduler.container(this)
            container.appScope.launch {
                // The todo may be done or edited by now; then neither the note nor the ring is due.
                if (container.reminders.stillLive(token)) {
                    container.reminders.cancelUnhandled(token)
                    getSystemService(NotificationManager::class.java).cancel(Notifier.notificationId(token.id))
                    RingService.start(this@AlarmActivity, token, title)
                } else {
                    getSystemService(NotificationManager::class.java).cancel(Notifier.notificationId(token.id))
                }
            }
        }
        shown = token to title
        return true
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_FROM_BOOT = "from_boot"
        private var current: WeakReference<AlarmActivity>? = null

        fun finishAll() {
            current?.get()?.runOnUiThread { current?.get()?.finish() }
        }
    }
}

@Composable
private fun AlarmContent(title: String, onDone: () -> Unit, onSnooze: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(cs.primaryContainer).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // A ring can last five minutes: the clock must not stand still meanwhile.
        val now by produceState(AppClock.millis()) {
            while (true) {
                delay(1_000)
                value = AppClock.millis()
            }
        }
        Text(Dates.time(now), fontSize = 64.sp, fontWeight = FontWeight.Light, color = cs.onPrimaryContainer)
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = cs.onPrimaryContainer, textAlign = TextAlign.Center)
        Spacer(Modifier.height(64.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("完成", fontSize = 18.sp) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.onPrimaryContainer)) {
            Text("10 分钟后", fontSize = 18.sp)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onStop) { Text("停止响铃", color = cs.onPrimaryContainer) }
    }
}
