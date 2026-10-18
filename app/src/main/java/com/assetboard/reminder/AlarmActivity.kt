package com.assetboard.reminder

import android.app.NotificationManager
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetboard.domain.Dates
import com.assetboard.ui.theme.AssetBoardTheme
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * The full-screen alarm, shown over the lock screen. When it was opened from a boot-time
 * notification (no service ringing yet), it takes the ringing over from the notification channel.
 */
class AlarmActivity : ComponentActivity() {
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
        val token = ReminderToken.from(intent) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (intent.getBooleanExtra(EXTRA_FROM_BOOT, false) && savedInstanceState == null) {
            getSystemService(NotificationManager::class.java).cancel(Notifier.notificationId(token.id))
            RingService.start(this, token, title)
        }
        val container = ReminderScheduler.container(this)
        fun act(action: String) {
            container.appScope.launch { ReminderActionReceiver.handle(applicationContext, action, token) }
            finish()
        }
        setContent {
            AssetBoardTheme(container.settings.themeMode.value, upIsRed = true) {
                AlarmContent(title, onDone = { act(ReminderActionReceiver.COMPLETE) }, onSnooze = { act(ReminderActionReceiver.SNOOZE_10) }, onStop = { act(ReminderActionReceiver.STOP) })
            }
        }
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
        Text(Dates.time(System.currentTimeMillis()), fontSize = 64.sp, fontWeight = FontWeight.Light, color = cs.onPrimaryContainer)
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
