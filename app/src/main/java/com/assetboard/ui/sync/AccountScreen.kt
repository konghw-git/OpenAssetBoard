package com.assetboard.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetboard.data.ConflictChoice
import com.assetboard.domain.Dates
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.FieldShape
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.GlassSnackbarHost
import com.assetboard.ui.components.IconTile
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SectionHeader
import com.assetboard.ui.components.glassBarColors
import com.assetboard.ui.components.glassFieldColors
import com.assetboard.ui.components.verticalScrollWithIndicator
import kotlinx.coroutines.launch

/**
 * Sign in to the self-hosted account, sync by hand, sign out, and settle a conflict. Sync is
 * optional: not being signed in is a normal state, and this page says so.
 */
@Composable
fun AccountScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val account = container.account
    val state by account.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }
    var server by remember { mutableStateOf(state.serverUrl.ifBlank { container.settings.syncServerUrl.value }) }
    var username by remember { mutableStateOf(state.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(state.serverUrl, state.username) {
        if (state.serverUrl.isNotBlank()) server = state.serverUrl
        if (!state.username.isNullOrBlank()) username = state.username!!
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            account.consumeMessage()
        }
    }
    ErrorDialog(state.error) { account.clearError() }

    val canSubmit = !state.busy && server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    val submit: () -> Unit = {
        if (canSubmit) {
            container.settings.setSyncServerUrl(server)
            container.appScope.launch { account.login(server, username, password) }
        }
    }

    GlassScaffold(
        snackbarHost = { GlassSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("账号与同步") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = glassBarColors(),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScrollWithIndicator(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.signedIn) {
                SectionCard {
                    SectionHeader("已登录")
                    ListRow(
                        title = state.username.orEmpty().ifBlank { "账号" },
                        subtitle = state.serverUrl,
                        leading = { IconTile(Icons.Outlined.Person, MaterialTheme.colorScheme.primary) },
                        chevron = false,
                    )
                    RowDivider(startIndent = 64.dp)
                    ListRow(
                        title = "立即同步",
                        subtitle = when {
                            state.busy -> "同步中…"
                            state.lastSyncAt != null -> "上次同步 ${Dates.relative(state.lastSyncAt!!)} ${Dates.time(state.lastSyncAt!!)} · 云端版本 ${state.revision}"
                            else -> "还没有同步过"
                        },
                        leading = { IconTile(Icons.Outlined.Sync, cs.primary) },
                        onClick = { container.appScope.launch { account.syncNow() } },
                    )
                    RowDivider(startIndent = 64.dp)
                    ListRow(
                        title = "退出登录",
                        subtitle = "本机数据保留，之后仍可离线使用",
                        leading = { IconTile(Icons.Outlined.Person, cs.onSurfaceVariant) },
                        onClick = { confirmLogout = true },
                    )
                }
                Note("同步是整份上传或下载。两边都改过时不会自动合并，会在这里让你选保留哪一边；覆盖前都会自动另存一份恢复前副本。")
            } else {
                SectionCard {
                    SectionHeader("登录")
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text("服务器地址") },
                            placeholder = { Text("https://…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = FieldShape,
                            colors = glassFieldColors(),
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("用户名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = FieldShape,
                            colors = glassFieldColors(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier.fillMaxWidth(),
                            shape = FieldShape,
                            colors = glassFieldColors(),
                        )
                        Spacer(Modifier.height(2.dp))
                        Button(
                            onClick = submit,
                            enabled = canSubmit,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.busy) "登录中…" else "登录")
                        }
                    }
                }
                Note("账号由服务器管理员创建，没有注册和找回密码。登录后本机数据会与该账号同步；不登录时一切照旧，数据只在本机。")
                if (!state.username.isNullOrBlank()) {
                    Note("上次登录：${state.username}")
                }
            }
        }
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = "退出登录？",
            text = "本机数据会保留，之后仍可离线使用。要换成另一个账号，退出后重新登录即可；另一个账号会使用它自己的数据，不会看到这个账号的内容。",
            confirmLabel = "退出登录",
            destructive = true,
            onConfirm = {
                confirmLogout = false
                container.appScope.launch { account.logout() }
            },
            onDismiss = { confirmLogout = false },
        )
    }

    state.conflict?.let { conflict ->
        val remoteAt = (conflict.remote.updatedAt * 1000).takeIf { it > 0 }?.let(Dates::dateTime) ?: "未知时间"
        AlertDialog(
            onDismissRequest = { account.clearConflict() },
            title = { Text("两边都有改动") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本机在 ${Dates.dateTime(conflict.localExportedAt)} 改过；云端在 $remoteAt 也有新版本。")
                    Text(
                        "同步不会自动合并。保留本机会覆盖云端（云端历史里仍能找回上一版）；使用云端会覆盖本机，覆盖前会自动另存一份恢复前副本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { container.appScope.launch { account.resolveConflict(ConflictChoice.KEEP_LOCAL) } }) {
                    Text("保留本机并上传")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { container.appScope.launch { account.resolveConflict(ConflictChoice.USE_REMOTE) } }) {
                        Text("使用云端")
                    }
                    TextButton(onClick = { account.clearConflict() }) { Text("稍后") }
                }
            },
        )
    }
}

/** The small print under a card. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}