package com.assetboard

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.assetboard.domain.LedgerException
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.todo.QuickAddDefaults
import com.assetboard.ui.todo.QuickAddForm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Quick add from the widget's +: a sheet over the home screen, keyboard up at once. The epoch is
 * bound when the sheet opens.
 */
class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AssetBoardApp).container
        val epoch = container.gate.bound()
        setContent {
            val theme by container.settings.themeMode.collectAsStateWithLifecycle()
            val lists = container.todos.data.collectAsStateWithLifecycle().value?.lists?.filter { it.archivedAt == null }.orEmpty()
            CompositionLocalProvider(LocalContainer provides container) {
                AssetBoardTheme(theme, upIsRed = true) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                            .clickable(remember { MutableInteractionSource() }, indication = null) { finish() },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().imePadding().clickable(remember { MutableInteractionSource() }, indication = null) {},
                        ) {
                            Column {
                                QuickAddForm(lists, QuickAddDefaults(), onSave = { draft, onResult ->
                                    lifecycleScope.launch(epoch) {
                                        try {
                                            container.todos.create(draft)
                                            onResult(true)
                                            Toast.makeText(this@QuickAddActivity, "已添加「${draft.title}」", Toast.LENGTH_SHORT).show()
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            onResult(false)
                                            Toast.makeText(this@QuickAddActivity, (e as? LedgerException)?.message ?: "添加失败，内容已保留", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}
