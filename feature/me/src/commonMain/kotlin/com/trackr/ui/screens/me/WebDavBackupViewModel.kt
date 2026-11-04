package com.trackr.ui.screens.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.backup.BackupRepository
import com.trackr.core.data.backup.RemoteBackupFile
import com.trackr.core.data.backup.RestorePlan
import com.trackr.core.data.backup.ParsedBackup
import com.trackr.core.data.backup.WebDavBackupFailure
import com.trackr.core.data.backup.WebDavConfigRepository
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
    val arg: String? = null
) {
    enum class What {
        CONNECT_OK, CONNECT_FAIL, BACKUP_OK, BACKUP_FAIL, LIST_FAIL,
        DOWNLOAD_FAIL, RESTORE_OK, RESTORE_FAIL, DELETE_OK, DELETE_FAIL, NO_URL
    }
}

/** 点远端文件后、确认前暂存的恢复预览 */
data class PendingRestore(val fileName: String, val createdAtText: String?, val plan: RestorePlan)

data class WebDavBackupUiState(
    val baseUrl: String = "",
    val username: String = "",
    val remoteDir: String = "",
    val filePrefix: String = "",
    /** 只在内存里，本次会话有效；不写 DataStore、不进任何日志 */
    val password: String = "",
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
    data class EditRemoteDir(val text: String) : WebDavBackupEvent
    data class EditFilePrefix(val text: String) : WebDavBackupEvent
    data class EditPassword(val text: String) : WebDavBackupEvent
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
                    filePrefix = connection.filePrefix,
                    lastBackupText = connection.lastBackupAtMillis?.let(::formatBackupLocalTime)
                )
            }
        }
    }

    fun onEvent(event: WebDavBackupEvent) {
        when (event) {
            is WebDavBackupEvent.EditBaseUrl -> _uiState.update { it.copy(baseUrl = event.text) }
            is WebDavBackupEvent.EditUsername -> _uiState.update { it.copy(username = event.text) }
            is WebDavBackupEvent.EditRemoteDir -> _uiState.update { it.copy(remoteDir = event.text) }
            is WebDavBackupEvent.EditFilePrefix -> _uiState.update { it.copy(filePrefix = event.text) }
            is WebDavBackupEvent.EditPassword -> _uiState.update { it.copy(password = event.text) }
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
                    if (result.isSuccess) {
                        // 收藏分组恢复了，但顺序、数据各仓库自己维护；页面不需要再拉任何东西
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

    /** 操作前把表单里的连接设置（除密码）静默存一份；地址为空时提示并返回 false */
    private suspend fun persistFormAndCheckUrl(): Boolean {
        val state = _uiState.value
        if (state.baseUrl.isBlank()) {
            post(WebDavMessage.What.NO_URL)
            return false
        }
        configRepository.saveConnection(
            baseUrl = state.baseUrl,
            username = state.username,
            remoteDir = state.remoteDir,
            filePrefix = state.filePrefix
        )
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
        arg: String? = null
    ) {
        _uiState.update { s -> s.copy(messages = s.messages + WebDavMessage(nextMessageId++, what, kind, arg)) }
    }
}

/** 「上次备份时间」按本地时区显示（和线路页到站时间同源：固定 Asia/Shanghai，用户都在国内） */
internal fun formatBackupLocalTime(epochMillis: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(kotlinx.datetime.TimeZone.of("Asia/Shanghai"))
    fun pad(v: Int) = v.toString().padStart(2, '0')
    return "${local.year}-${pad(local.monthNumber)}-${pad(local.dayOfMonth)} ${pad(local.hour)}:${pad(local.minute)}"
}
