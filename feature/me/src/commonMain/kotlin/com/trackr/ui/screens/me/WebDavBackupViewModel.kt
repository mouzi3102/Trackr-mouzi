package com.trackr.ui.screens.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.backup.BackupRepository
import com.trackr.core.data.backup.RemoteBackupFile
import com.trackr.core.data.backup.RestorePlan
import com.trackr.core.data.backup.ParsedBackup
import com.trackr.core.data.backup.WebDavBackupFailure
import com.trackr.core.data.backup.WebDavConfigRepository
import com.trackr.core.data.backup.WebDavConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * 「WebDAV 备份」子页（第 39 步，「我的」页签）。
 *
 * 密码只活在本 ViewModel 的内存里（本次进程会话），从不落盘、不进日志（constraint §7）；
 * 其它连接字段（地址 / 用户名 / 目录 / 前缀）在每次操作前随表单静默持久化到设置存储。
 * 备份与恢复的实际读写都在 `:core:data` 的 [BackupRepository]（走各仓库现有接口）。
 */

/** 一条一次性提示：界面按 [what] + [kind] 去 composeResources 找中文，ViewModel 不拼句子 */
data class WebDavMessage(
    val id: Long,
    val what: What,
    val kind: WebDavBackupFailure.Kind? = null,
    /** 文件名等参数（资源里的 %1$s） */
    val arg: String? = null,
    /** RESTORE_OK：本次从备份补进本地的新收藏条数（资源里的 %1$d） */
    val addedFavorites: Int = 0,
    /** RESTORE_OK：备份与本机同 id、保留本机那一份的条数（资源里的 %2$d） */
    val keptExistingFavorites: Int = 0,
    /** RESTORE_OK：本次补进来的定时提醒条数（资源里的 %3$d） */
    val addedReminders: Int = 0
) {
    enum class What {
        CONNECT_OK, CONNECT_FAIL, BACKUP_OK, BACKUP_FAIL, LIST_FAIL,
        DOWNLOAD_FAIL, RESTORE_OK, RESTORE_FAIL, DELETE_OK, DELETE_FAIL, NO_URL, NO_PASSWORD
    }
}

/** 点远端文件后、确认前暂存的恢复预览 */
data class PendingRestore(val fileName: String, val createdAtText: String?, val plan: RestorePlan)

data class WebDavBackupUiState(
    val baseUrl: String = "",
    val username: String = "",
    /** 远端目录：不再给用户填（对话框里删掉了），保留存储值让老配置继续能读到自己的备份 */
    val remoteDir: String = "",
    val password: String = "",
    /** 记住登录信息（默认开）；关掉会把已存的密码清掉 */
    val rememberPassword: Boolean = true,
    val busy: Boolean = false,
    val lastBackupText: String? = null,
    val files: List<RemoteBackupFile> = emptyList(),
    val filesLoaded: Boolean = false,
    val pendingRestore: PendingRestore? = null,
    val messages: List<WebDavMessage> = emptyList()
) {
    val canOperate: Boolean get() = baseUrl.isNotBlank() && !busy
    val canRestoreNow: Boolean get() = pendingRestore != null && !busy
}

sealed interface WebDavBackupEvent {
    data class EditBaseUrl(val text: String) : WebDavBackupEvent
    data class EditUsername(val text: String) : WebDavBackupEvent
    data class EditPassword(val text: String) : WebDavBackupEvent

    /** 「记住密码」开关：关掉会立刻清除存的密码（登录信息留在本机，不上传任何东西） */
    data class SetRememberPassword(val enabled: Boolean) : WebDavBackupEvent

    /** 「用坚果云」：一键填入服务器地址（https + /dav），用户名 / 安全码还得用户自己填 */
    data object ApplyJianguoYun : WebDavBackupEvent
    data object TestConnection : WebDavBackupEvent
    data object BackupNow : WebDavBackupEvent
    data object RefreshList : WebDavBackupEvent
    data class OpenRestore(val file: RemoteBackupFile) : WebDavBackupEvent
    data object ConfirmRestore : WebDavBackupEvent
    data object CancelRestore : WebDavBackupEvent
    data class DeleteFile(val file: RemoteBackupFile) : WebDavBackupEvent
    data class MessageShown(val id: Long) : WebDavBackupEvent
    data object Back : WebDavBackupEvent
}

class WebDavBackupViewModel(
    private val backupRepository: BackupRepository,
    private val configRepository: WebDavConfigRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebDavBackupUiState())
    val uiState: StateFlow<WebDavBackupUiState> = _uiState.asStateFlow()

    private var nextMessageId = 1L

    init {
        viewModelScope.launch {
            val connection = configRepository.load()
            _uiState.update {
                it.copy(
                    baseUrl = connection.baseUrl,
                    username = connection.username,
                    // 没存过时用仓库默认值显示（存了空串按「没填」处理）
                    remoteDir = connection.remoteDir,
                    password = connection.password,
                    rememberPassword = connection.rememberPassword,
                    lastBackupText = connection.lastBackupAtMillis?.let(::formatBackupLocalTime)
                )
            }
        }
    }

    fun onEvent(event: WebDavBackupEvent) {
        when (event) {
            is WebDavBackupEvent.EditBaseUrl -> _uiState.update { it.copy(baseUrl = event.text) }
            is WebDavBackupEvent.EditUsername -> _uiState.update { it.copy(username = event.text) }
            is WebDavBackupEvent.EditPassword -> _uiState.update { it.copy(password = event.text) }
            is WebDavBackupEvent.SetRememberPassword -> {
                val enabled = event.enabled
                _uiState.update { it.copy(rememberPassword = enabled) }
                // 立刻落盘：关掉「记住密码」时仓库会不再写密码列并清掉存过的那份
                withAction { _uiState.value.persistToConfig() }
            }
            WebDavBackupEvent.ApplyJianguoYun -> _uiState.update { it.copy(baseUrl = WebDavConnection.PRESET_JIANGUOYUN_URL) }
            WebDavBackupEvent.Back -> Unit
            is WebDavBackupEvent.MessageShown ->
                _uiState.update { s -> s.copy(messages = s.messages.filterNot { it.id == event.id }) }
            WebDavBackupEvent.TestConnection -> withAction {
                val password = _uiState.value.password
                if (!persistFormAndCheckUrl()) return@withAction
                val result = backupRepository.testConnection(password)
                if (result.isSuccess) post(WebDavMessage.What.CONNECT_OK)
                else failWith(WebDavMessage.What.CONNECT_FAIL, result.exceptionOrNull())
            }
            WebDavBackupEvent.RefreshList -> withAction {
                val password = _uiState.value.password
                if (!persistFormAndCheckUrl()) return@withAction
                val result = backupRepository.listRemoteFiles(password)
                val files = result.getOrNull()
                if (files != null) {
                    _uiState.update { it.copy(files = files, filesLoaded = true) }
                } else {
                    _uiState.update { it.copy(filesLoaded = true) }
                    failWith(WebDavMessage.What.LIST_FAIL, result.exceptionOrNull())
                }
            }
            WebDavBackupEvent.BackupNow -> withAction {
                val password = _uiState.value.password
                if (!persistFormAndCheckUrl()) return@withAction
                val result = backupRepository.createAndUploadBackup(password)
                val fileName = result.getOrNull()
                if (fileName != null) {
                    // 成功后刷新「上次备份时间」并顺带把列表拉新（参考实现的进度 + 提示条体验）
                    val connection = configRepository.load()
                    _uiState.update {
                        it.copy(lastBackupText = connection.lastBackupAtMillis?.let(::formatBackupLocalTime))
                    }
                    post(WebDavMessage.What.BACKUP_OK, arg = fileName)
                    backupRepository.listRemoteFiles(password).onSuccess { files ->
                        _uiState.update { it.copy(files = files, filesLoaded = true) }
                    }
                } else {
                    failWith(WebDavMessage.What.BACKUP_FAIL, result.exceptionOrNull())
                }
            }
            is WebDavBackupEvent.OpenRestore -> withAction {
                val password = _uiState.value.password
                if (password.isBlank()) {
                    post(WebDavMessage.What.NO_PASSWORD)
                    return@withAction
                }
                val result = backupRepository.downloadBackup(event.file.name, password)
                val parsed = result.getOrNull()
                if (parsed == null) {
                    failWith(WebDavMessage.What.DOWNLOAD_FAIL, result.exceptionOrNull())
                } else {
                    pendingParsed = parsed
                    _uiState.update {
                        it.copy(
                            pendingRestore = PendingRestore(
                                fileName = parsed.fileName,
                                createdAtText = parsed.payload.createdAtMillis
                                    .takeIf { millis -> millis > 0L }
                                    ?.let(::formatBackupLocalTime),
                                plan = parsed.plan
                            )
                        )
                    }
                }
            }
            WebDavBackupEvent.ConfirmRestore -> withAction {
                val parsed = pendingParsed
                if (parsed == null) {
                    _uiState.update { s -> s.copy(pendingRestore = null) }
                } else {
                    val result = backupRepository.applyRestore(parsed)
                    _uiState.update { it.copy(pendingRestore = null) }
                    pendingParsed = null
                    val outcome = result.getOrNull()
                    if (outcome != null) {
                        // 收藏和提醒是合并，报「补进几条 / 保留本地几条」，让人知道没被覆盖
                        post(
                            what = WebDavMessage.What.RESTORE_OK,
                            addedFavorites = outcome.addedFavorites,
                            keptExistingFavorites = outcome.keptExistingFavorites,
                            addedReminders = outcome.addedReminders
                        )
                    } else if (result.isSuccess) {
                        post(WebDavMessage.What.RESTORE_OK)
                    } else {
                        failWith(WebDavMessage.What.RESTORE_FAIL, result.exceptionOrNull())
                    }
                }
            }
            WebDavBackupEvent.CancelRestore -> {
                pendingParsed = null
                _uiState.update { it.copy(pendingRestore = null) }
            }
            is WebDavBackupEvent.DeleteFile -> withAction {
                val password = _uiState.value.password
                if (password.isBlank()) {
                    post(WebDavMessage.What.NO_PASSWORD)
                    return@withAction
                }
                val result = backupRepository.deleteRemoteFile(event.file.name, password)
                if (result.isSuccess) {
                    _uiState.update { s -> s.copy(files = s.files.filterNot { it.name == event.file.name }) }
                    post(WebDavMessage.What.DELETE_OK, arg = event.file.name)
                } else {
                    failWith(WebDavMessage.What.DELETE_FAIL, result.exceptionOrNull())
                }
            }
        }
    }

    /** ConfirmRestore 要拿到解析全文（UiState 里只放给人看的摘要） */
    private var pendingParsed: ParsedBackup? = null

    private inline fun withAction(crossinline block: suspend () -> Unit) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            try {
                block()
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    /** 界面表单 → 存储里的连接配置；密码落不落盘由「记住密码」决定（关掉时仓库会清掉存过的那份） */
    private suspend fun WebDavBackupUiState.persistToConfig() = configRepository.saveConnection(
        baseUrl = baseUrl,
        username = username,
        remoteDir = remoteDir,
        password = password,
        rememberPassword = rememberPassword
    )

    /** 操作前把表单里的连接设置（除密码）静默存一份；地址或密码为空时提示并返回 false（别拿空密码去打服务器） */
    private suspend fun persistFormAndCheckUrl(): Boolean {
        val state = _uiState.value
        if (state.baseUrl.isBlank()) {
            post(WebDavMessage.What.NO_URL)
            return false
        }
        if (state.password.isBlank()) {
            post(WebDavMessage.What.NO_PASSWORD)
            return false
        }
        state.persistToConfig()
        return true
    }

    private fun failWith(what: WebDavMessage.What, error: Throwable?) {
        val kind = (error as? WebDavBackupFailure)?.kind
        val arg = (error as? WebDavBackupFailure)?.httpCode?.takeIf { it > 0 }?.toString()
        post(what, kind = kind, arg = arg)
    }

    private fun post(
        what: WebDavMessage.What,
        kind: WebDavBackupFailure.Kind? = null,
        arg: String? = null,
        addedFavorites: Int = 0,
        keptExistingFavorites: Int = 0,
        addedReminders: Int = 0
    ) {
        _uiState.update { s ->
            s.copy(
                messages = s.messages +
                    WebDavMessage(nextMessageId++, what, kind, arg, addedFavorites, keptExistingFavorites, addedReminders)
            )
        }
    }
}

/** 「上次备份时间」按本地时区显示（和线路页到站时间同源：固定 Asia/Shanghai，用户都在国内） */
internal fun formatBackupLocalTime(epochMillis: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(kotlinx.datetime.TimeZone.of("Asia/Shanghai"))
    fun pad(v: Int) = v.toString().padStart(2, '0')
    return "${local.year}-${pad(local.monthNumber)}-${pad(local.dayOfMonth)} ${pad(local.hour)}:${pad(local.minute)}"


}