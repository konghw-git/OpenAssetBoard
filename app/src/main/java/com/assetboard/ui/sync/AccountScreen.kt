package com.assetboard.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetboard.data.ConflictChoice
import com.assetboard.data.ServerCheck
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
    var changePasswordOpen by remember { mutableStateOf(false) }
    val screenScope = rememberCoroutineScope()

    // Leaving the page abandons a sign-in that is still waiting, so coming back always shows
    // the form rather than a stale "登录中…".
    DisposableEffect(account) {
        onDispose {
            account.cancelLogin()
            account.cancelCheck()
        }
    }
    LaunchedEffect(state.serverUrl, state.username) {
        if (state.serverUrl.isNotBlank()) server = state.serverUrl
        if (!state.username.isNullOrBlank()) username = state.username!!
    }
    // After a successful sign-in the password has done its job; after a logout the field must
    // not keep the previous account's password around. A session that expires under an open
    // dialog closes it rather than showing it again after the next sign-in.
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            password = ""
        } else {
            changePasswordOpen = false
            confirmLogout = false
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            account.consumeMessage()
        }
    }
    ErrorDialog(state.error) { account.clearError() }

    val canSubmit = !state.loginBusy && !state.busy &&
        server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
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
                        title = "修改密码",
                        subtitle = "输入旧密码设置新密码；其他设备会退出登录",
                        leading = { IconTile(Icons.Outlined.Lock, cs.primary) },
                        onClick = { changePasswordOpen = true },
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
                        // A result for the address that was typed before is not shown for a new one.
                        val shownCheck = if (state.check.server == server.trim().trimEnd('/')) state.check else ServerCheck()
                        ServerCheckStatus(
                            check = shownCheck,
                            onCheck = { screenScope.launch { account.checkServer(server, force = true) } },
                            enabled = !state.loginBusy,
                        )
                        Spacer(Modifier.height(2.dp))
                        Button(
                            onClick = submit,
                            enabled = canSubmit,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    state.loginBusy && state.check.running -> "正在检测网络…"
                                    state.loginBusy -> "登录中…"
                                    else -> "登录"
                                },
                            )
                        }
                        if (state.loginBusy) {
                            TextButton(onClick = { account.cancelLogin() }, modifier = Modifier.fillMaxWidth()) {
                                Text("取消登录")
                            }
                        }
                    }
                }
                Note("账号由服务器管理员创建，没有注册。登录后可以在本页修改密码；忘记密码时，只能请服务器管理员在服务器上重置。不登录时一切照旧，数据只在本机。")
                if (!state.username.isNullOrBlank()) {
                    Note("上次登录：${state.username}")
                }
            }
        }
    }

    if (changePasswordOpen && state.signedIn) {
        ChangePasswordDialog(
            onDismiss = { changePasswordOpen = false },
            onSubmit = { old, new -> account.changePassword(old, new) },
        )
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

/** The probe's result under the server field, and the button that asks again. */
@Composable
private fun ServerCheckStatus(check: ServerCheck, onCheck: () -> Unit, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                check.running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("正在检测服务器连接…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                check.ok == true -> {
                    val ms = check.latencyMs ?: 0
                    val slow = ms > 1_500
                    Text(
                        if (slow) "服务器连接正常，但比较慢（${latencyText(ms)}）" else "服务器连接正常 · 延迟 ${latencyText(ms)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (slow) cs.tertiary else cs.primary,
                    )
                }
                check.ok == false -> {
                    Text(check.error ?: "无法连接服务器", style = MaterialTheme.typography.bodySmall, color = cs.error)
                    Text(
                        "也可以换一个网络（Wi‑Fi 或移动数据）后再试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                else -> Text(
                    "登录前会先检测服务器连接，不通时会提示更换网络。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        if (!check.running) {
            TextButton(
                onClick = onCheck,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(if (check.ok == null) "检测" else "重新检测", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun latencyText(ms: Long): String = if (ms < 1_000) "$ms ms" else "${ms / 1000.0} 秒"

/**
 * Asks for the old password and the new one twice. The error stays beside the fields so a typo
 * does not close the dialog and throw away what was typed.
 */
@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onSubmit: suspend (old: String, new: String) -> String?,
) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    fun submit() {
        val bytes = new.toByteArray(Charsets.UTF_8).size
        val local = when {
            old.isEmpty() -> "请输入旧密码"
            bytes < 8 -> "新密码至少 8 个字符"
            bytes > 256 -> "新密码最多 256 个字符"
            new != confirm -> "两次输入的新密码不一致"
            new == old -> "新密码不能与旧密码相同"
            else -> null
        }
        if (local != null) {
            error = local
            return
        }
        if (submitting) return
        submitting = true
        error = null
        scope.launch {
            val failure = onSubmit(old, new)
            submitting = false
            if (failure == null) onDismiss() else error = failure
        }
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("修改密码") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = old,
                    onValueChange = { old = it },
                    label = { Text("旧密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    colors = glassFieldColors(),
                )
                OutlinedTextField(
                    value = new,
                    onValueChange = { new = it },
                    label = { Text("新密码（至少 8 个字符）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    colors = glassFieldColors(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("再次输入新密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    colors = glassFieldColors(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
                Text(
                    "修改后，其他设备上的登录会失效；这台手机会保持登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !submitting) {
                Text(if (submitting) "修改中…" else "修改密码")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        },
    )
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
