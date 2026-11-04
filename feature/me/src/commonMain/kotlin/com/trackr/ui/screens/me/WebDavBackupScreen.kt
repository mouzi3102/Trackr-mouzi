@file:OptIn(ExperimentalMaterial3Api::class)

package com.trackr.ui.screens.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.trackr.core.data.backup.RemoteBackupFile
import com.trackr.core.data.backup.RestorePlan
import com.trackr.core.data.backup.WebDavBackupFailure
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_back
import com.trackr.core.designsystem.resources.action_cancel
import com.trackr.core.designsystem.resources.ic_back
import com.trackr.feature.me.resources.*
import com.trackr.feature.me.resources.Res as MeRes
import com.trackr.ui.components.message.HostMessage
import com.trackr.ui.components.message.UserMessageHost
import org.jetbrains.compose.resources.vectorResource
import org.jetbrains.compose.resources.stringResource

/**
 * WebDAV 备份子页（第 39 步）：连接表单 + 测试连接 / 立即备份 / 刷新列表，
 * 远端文件每条都有「恢复」（下载 → 确认合并 → 恢复）和「删除」按钮；全部文案来自模块 composeResources。
 */
@Composable
fun WebDavBackupScreen(
    state: WebDavBackupUiState,
    onEvent: (WebDavBackupEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    // 「从备份恢复」的文件选择弹窗。列表还没拉到时点它先去读远端目录，读到之后自动弹出来（只用点一次）
    var showRestorePicker by rememberSaveable { mutableStateOf(false) }
    var pickAfterRefresh by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.files, state.busy, state.filesLoaded) {
        if (pickAfterRefresh && !state.busy && state.filesLoaded) {
            pickAfterRefresh = false
            if (state.files.isNotEmpty()) showRestorePicker = true
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = {
            WebDavMessageHost(state.messages) { onEvent(WebDavBackupEvent.MessageShown(it)) }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(MeRes.string.me_webdav_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onEvent(WebDavBackupEvent.Back) }) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.ic_back),
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("webdav_list"),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "intro") {
                Text(
                    text = stringResource(MeRes.string.me_webdav_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item(key = "connection") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = state.baseUrl,
                            onValueChange = { onEvent(WebDavBackupEvent.EditBaseUrl(it)) },
                            label = { Text(stringResource(MeRes.string.me_webdav_server)) },
                            placeholder = { Text(stringResource(MeRes.string.me_webdav_server_placeholder)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("field_webdav_url")
                        )
                        OutlinedTextField(
                            value = state.username,
                            onValueChange = { onEvent(WebDavBackupEvent.EditUsername(it)) },
                            label = { Text(stringResource(MeRes.string.me_webdav_username)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("field_webdav_user")
                        )
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = { onEvent(WebDavBackupEvent.EditPassword(it)) },
                            label = { Text(stringResource(MeRes.string.me_webdav_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            supportingText = {
                                // 需求：密码只存本次会话内存。把这句话钉在输入框下，用户看得见
                                Text(stringResource(MeRes.string.me_webdav_password_hint))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("field_webdav_password")
                        )
                        OutlinedTextField(
                            value = state.remoteDir,
                            onValueChange = { onEvent(WebDavBackupEvent.EditRemoteDir(it)) },
                            label = { Text(stringResource(MeRes.string.me_webdav_dir)) },
                            placeholder = { Text(stringResource(MeRes.string.me_webdav_dir_placeholder)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("field_webdav_dir")
                        )
                        OutlinedTextField(
                            value = state.filePrefix,
                            onValueChange = { onEvent(WebDavBackupEvent.EditFilePrefix(it)) },
                            label = { Text(stringResource(MeRes.string.me_webdav_prefix)) },
                            placeholder = { Text(stringResource(MeRes.string.me_webdav_prefix_placeholder)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("field_webdav_prefix")
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = state.lastBackupText
                                    ?.let { stringResource(MeRes.string.me_webdav_last_backup, it) }
                                    ?: stringResource(MeRes.string.me_webdav_last_backup_none),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).testTag("text_last_backup")
                            )
                            if (state.busy) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }

            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onEvent(WebDavBackupEvent.TestConnection) },
                        enabled = !state.busy,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_webdav_test")
                    ) {
                        Text(stringResource(MeRes.string.me_webdav_test))
                    }
                    Button(
                        onClick = { onEvent(WebDavBackupEvent.RefreshList) },
                        enabled = !state.busy,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_webdav_refresh")
                    ) {
                        Text(stringResource(MeRes.string.me_webdav_refresh))
                    }
                }
            }

            item(key = "backup_and_restore") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onEvent(WebDavBackupEvent.BackupNow) },
                        enabled = !state.busy,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("btn_webdav_backup_now")
                    ) {
                        Text(stringResource(MeRes.string.me_webdav_backup_now))
                    }
                    // 恢复是常驻入口：远端列表没拉的时候点它先去读目录，读到了直接弹文件选择
                    OutlinedButton(
                        onClick = {
                            if (state.files.isEmpty()) {
                                pickAfterRefresh = true
                                onEvent(WebDavBackupEvent.RefreshList)
                            } else {
                                showRestorePicker = true
                            }
                        },
                        enabled = !state.busy,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("btn_webdav_restore")
                    ) {
                        Text(stringResource(MeRes.string.me_webdav_restore_entry))
                    }
                }
            }

            item(key = "remote_title") {
                Text(
                    text = stringResource(MeRes.string.me_webdav_remote_files),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            if (state.files.isEmpty()) {
                item(key = "remote_empty") {
                    Text(
                        text = if (state.filesLoaded) {
                            stringResource(MeRes.string.me_webdav_files_empty)
                        } else {
                            stringResource(MeRes.string.me_webdav_files_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(items = state.files, key = { it.name }) { file ->
                RemoteFileRow(
                    file = file,
                    busy = state.busy,
                    onOpen = { onEvent(WebDavBackupEvent.OpenRestore(file)) },
                    onDelete = { onEvent(WebDavBackupEvent.DeleteFile(file)) }
                )
            }
        }
    }

    if (showRestorePicker && state.files.isNotEmpty()) {
        RestoreFilePickerDialog(
            files = state.files,
            onPick = { file ->
                showRestorePicker = false
                onEvent(WebDavBackupEvent.OpenRestore(file))
            },
            onDismiss = { showRestorePicker = false }
        )
    }

    val pending = state.pendingRestore
    if (pending != null) {
        RestoreConfirmDialog(
            pending = pending,
            onConfirm = { onEvent(WebDavBackupEvent.ConfirmRestore) },
            onDismiss = { onEvent(WebDavBackupEvent.CancelRestore) }
        )
    }
}

/** 选一份远端备份来恢复：列出文件名 + 服务器给的修改时间，点一条走「下载 → 确认 → 写入本机」 */
@Composable
private fun RestoreFilePickerDialog(
    files: List<RemoteBackupFile>,
    onPick: (RemoteBackupFile) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MeRes.string.me_webdav_restore_pick_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(MeRes.string.me_webdav_restore_pick_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                files.forEach { file ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(file) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("restore_pick_${file.name}")
                    ) {
                        Text(text = file.name, style = MaterialTheme.typography.bodyMedium)
                        val modified = file.lastModified
                        if (modified != null) {
                            Text(
                                text = modified,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}

@Composable
private fun RemoteFileRow(
    file: RemoteBackupFile,
    busy: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("row_remote_${file.name}"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = file.name, style = MaterialTheme.typography.bodyMedium)
                val modified = file.lastModified
                if (modified != null) {
                    Text(
                        text = modified,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 「恢复」「删除」都是显式按钮：整行点着恢复不好发现，误触也不好撤销
            TextButton(
                onClick = onOpen,
                enabled = !busy,
                modifier = Modifier.testTag("btn_restore_${file.name}")
            ) {
                Text(stringResource(MeRes.string.me_webdav_restore))
            }
            TextButton(
                onClick = onDelete,
                enabled = !busy,
                modifier = Modifier.testTag("btn_delete_${file.name}")
            ) {
                Text(stringResource(MeRes.string.me_webdav_delete))
            }
        }
    }
}

@Composable
private fun RestoreConfirmDialog(
    pending: PendingRestore,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val plan = pending.plan
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MeRes.string.me_webdav_restore_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(MeRes.string.me_webdav_restore_about, pending.fileName),
                    style = MaterialTheme.typography.bodySmall
                )
                if (pending.createdAtText != null) {
                    Text(
                        text = stringResource(MeRes.string.me_webdav_restore_created, pending.createdAtText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val lines = buildList {
                    if (plan.replaceSettings) {
                        add(stringResource(MeRes.string.me_webdav_replace_settings, plan.settingsFieldCount))
                    }
                    if (plan.replaceSearchHistory) {
                        add(stringResource(MeRes.string.me_webdav_replace_history, plan.searchHistoryCount))
                    }
                    if (plan.replaceCitySearchHistory) {
                        add(stringResource(MeRes.string.me_webdav_replace_city_history, plan.citySearchHistoryCount))
                    }
                    if (plan.replaceFavorites) {
                        add(stringResource(MeRes.string.me_webdav_replace_favorites, plan.stopCount, plan.routeCount))
                        add(stringResource(MeRes.string.me_webdav_contains_groups, plan.groupCount))
                    }
                    if (plan.replaceReminders) {
                        add(stringResource(MeRes.string.me_webdav_restore_reminders, plan.reminderCount))
                    }
                    if (plan.skippedFavorites > 0) {
                        add(
                            stringResource(
                                MeRes.string.me_webdav_skipped_items,
                                plan.skippedFavorites.toString()
                            )
                        )
                    }
                    if (plan.itemsInUnknownGroup > 0) {
                        add(stringResource(MeRes.string.me_webdav_unknown_group_note))
                    }
                }
                lines.forEach { line ->
                    Text(
                        text = "•  $line",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = stringResource(MeRes.string.me_webdav_restore_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("btn_restore_confirm")) {
                Text(stringResource(MeRes.string.me_webdav_restore_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    )
}

/** 失败 / 结果提示：文字全部来自模块资源，ViewModel 只给「哪件事 + 哪种失败 + 参数」 */
@Composable
private fun WebDavMessageText(message: WebDavMessage): String {
    return when (message.what) {
        WebDavMessage.What.CONNECT_OK -> stringResource(MeRes.string.me_webdav_connect_ok)
        WebDavMessage.What.CONNECT_FAIL -> WebDavFailText(message.kind, message.arg)
        WebDavMessage.What.BACKUP_OK -> stringResource(MeRes.string.me_webdav_backup_ok, message.arg.orEmpty())
        WebDavMessage.What.BACKUP_FAIL -> WebDavFailText(message.kind, message.arg)
        WebDavMessage.What.LIST_FAIL -> WebDavFailText(message.kind, message.arg)
        WebDavMessage.What.DOWNLOAD_FAIL -> WebDavFailText(message.kind, message.arg)
        WebDavMessage.What.RESTORE_OK -> stringResource(MeRes.string.me_webdav_restore_ok)
        WebDavMessage.What.RESTORE_FAIL -> WebDavFailText(message.kind, message.arg)
        WebDavMessage.What.DELETE_OK -> stringResource(MeRes.string.me_webdav_delete_ok)
        WebDavMessage.What.DELETE_FAIL -> WebDavFailText(message.kind, message.arg)
        WebDavMessage.What.NO_URL -> stringResource(MeRes.string.me_webdav_no_url)
    }
}

/** 复用共享的 [UserMessageHost]：标准版 Snackbar、长辈版点「知道了」才关（和其它页同一套提示机制） */
@Composable
internal fun WebDavMessageHost(
    messages: List<WebDavMessage>,
    onShown: (Long) -> Unit
) {
    val current = messages.firstOrNull()
    UserMessageHost(
        message = current?.let { HostMessage(key = it.id, text = WebDavMessageText(it)) },
        onResult = { key, _ -> onShown(key as Long) }
    )
}

/** 失败文案按 [WebDavBackupFailure.Kind] 选模块资源；局部函数不能带 @Composable，所以单独提出来 */
@Composable
private fun WebDavFailText(kind: WebDavBackupFailure.Kind?, arg: String?): String = when (kind) {
    WebDavBackupFailure.Kind.AUTH -> stringResource(MeRes.string.me_webdav_err_auth)
    WebDavBackupFailure.Kind.NOT_FOUND -> stringResource(MeRes.string.me_webdav_err_not_found)
    WebDavBackupFailure.Kind.DIR_MISSING -> stringResource(MeRes.string.me_webdav_err_dir_missing)
    WebDavBackupFailure.Kind.TRANSPORT -> stringResource(MeRes.string.me_webdav_err_network)
    WebDavBackupFailure.Kind.SERVER -> stringResource(MeRes.string.me_webdav_err_server, arg.orEmpty())
    WebDavBackupFailure.Kind.NEWER_VERSION -> stringResource(MeRes.string.me_webdav_err_newer)
    WebDavBackupFailure.Kind.WRONG_APP -> stringResource(MeRes.string.me_webdav_err_wrong_app)
    WebDavBackupFailure.Kind.BAD_PAYLOAD -> stringResource(MeRes.string.me_webdav_err_payload)
    WebDavBackupFailure.Kind.NOT_CONFIGURED, null -> stringResource(MeRes.string.me_webdav_err_generic)
}
