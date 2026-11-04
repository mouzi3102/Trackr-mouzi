package com.trackr.ui.screens.me

import com.trackr.core.data.backup.BackupRepository
import com.trackr.core.data.backup.DecodedFavorites
import com.trackr.core.data.backup.ParsedBackup
import com.trackr.core.data.backup.RemoteBackupFile
import com.trackr.core.data.backup.RestorePlan
import com.trackr.core.data.backup.RestoreOutcome
import com.trackr.core.data.backup.TrackrBackupPayload
import com.trackr.core.data.backup.WebDavBackupFailure
import com.trackr.core.data.backup.WebDavConfigRepository
import com.trackr.core.data.backup.WebDavConnection
import com.trackr.core.testing.MainDispatcherHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WebDAV 备份页的交互规则（不发真实请求：传输和仓库都是假的）。
 *
 * 重点是这几条以坚果云为准的可用性要求：没填密码就不该去打服务器（原来会吃一个莫名其妙的 401）、
 * 「用坚果云」一键填入的地址必须是 https + /dav、点「从备份恢复」在没列表时先读目录、
 * 恢复走确认之后的 `applyRestore`，成功后给出可读的结果提示。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebDavBackupViewModelTest {

    private val main = MainDispatcherHolder()

    @BeforeTest
    fun setup() = main.install()

    @AfterTest
    fun tearDown() = main.reset()

    /** 记录调用的假仓库：状态码 / 失败都可脚本化 */
    private class FakeBackupRepository : BackupRepository {
        val calls = mutableListOf<String>()
        var files = listOf<RemoteBackupFile>()
        var listFails = false

        override suspend fun testConnection(password: String) = calls.let {
            calls += "test:$password"; Result.success(Unit)
        }

        override suspend fun listRemoteFiles(password: String): Result<List<RemoteBackupFile>> {
            calls += "list:$password"
            return if (listFails) {
                Result.failure(WebDavBackupFailure(WebDavBackupFailure.Kind.AUTH, 401))
            } else {
                Result.success(files)
            }
        }

        override suspend fun createAndUploadBackup(password: String): Result<String> {
            calls += "backup:$password"
            return Result.success("trackr_backup_20261104-153012.json")
        }

        override suspend fun downloadBackup(fileName: String, password: String): Result<ParsedBackup> {
            calls += "download:$fileName"
            val payload = TrackrBackupPayload(schemaVersion = 1, app = "Trackr", settings = null)
            val plan = RestorePlan(
                schemaVersion = 1, replaceSettings = false, settingsFieldCount = 0,
                replaceSearchHistory = false, searchHistoryCount = 0,
                replaceCitySearchHistory = false, citySearchHistoryCount = 0,
                replaceFavorites = true, stopCount = 2, routeCount = 1, groupCount = 1,
                itemsInUnknownGroup = 0, skippedFavorites = 0,
                replaceReminders = false, reminderCount = 0, skippedReminders = 0,
                empty = false
            )
            return Result.success(ParsedBackup(fileName, payload, DecodedFavorites(), plan))
        }

        override suspend fun applyRestore(parsed: ParsedBackup): Result<RestoreOutcome> {
            calls += "applyRestore"
            return Result.success(RestoreOutcome(emptySet(), addedFavorites = 3, keptExistingFavorites = 1))
        }

        override suspend fun deleteRemoteFile(fileName: String, password: String): Result<Unit> {
            calls += "delete:$fileName"
            return Result.success(Unit)
        }
    }

    private class FakeConfig(var connection: WebDavConnection = WebDavConnection()) : WebDavConfigRepository {
        var saved = 0
        override suspend fun load() = connection
        override suspend fun saveConnection(baseUrl: String, username: String, remoteDir: String, filePrefix: String) {
            saved++
            connection = connection.copy(baseUrl = baseUrl, username = username, remoteDir = remoteDir, filePrefix = filePrefix)
        }

        override suspend fun recordBackupSuccess(atMillis: Long) = Unit
    }

    /**
     * 构造完先把初始化那次 `configRepository.load()` 推进跑完 —— 否则它在用户（测试）填完表单之后才落地，
     * 会把 baseUrl / username 冲回存储里的空值，测出来的就不是被测逻辑了。
     */
    private suspend fun TestScope.viewModel(repo: FakeBackupRepository, config: FakeConfig): WebDavBackupViewModel =
        WebDavBackupViewModel(backupRepository = repo, configRepository = config).also { advanceUntilIdle() }

    private fun List<WebDavMessage>.whats() = map { it.what }

    @Test
    fun passwordIsNeverSentToTheServerWhenBlank() = runTest(main.dispatcher) {
        val repo = FakeBackupRepository()
        val vm = viewModel(repo, FakeConfig())
        vm.onEvent(WebDavBackupEvent.EditBaseUrl("https://dav.jianguoyun.com/dav/"))
        vm.onEvent(WebDavBackupEvent.EditUsername("me@example.com"))
        advanceUntilIdle()

        // 地址和用户名都填了，但密码没填：三条入口都不该发请求
        vm.onEvent(WebDavBackupEvent.TestConnection)
        vm.onEvent(WebDavBackupEvent.BackupNow)
        vm.onEvent(WebDavBackupEvent.RefreshList)
        advanceUntilIdle()

        assertTrue(repo.calls.isEmpty(), "不该有任何网络调用：${repo.calls}")
        assertEquals(
            List(3) { WebDavMessage.What.NO_PASSWORD },
            vm.uiState.value.messages.whats()
        )
    }

    @Test
    fun blankBaseUrlStillShortCircuitsBeforePasswordCheck() = runTest(main.dispatcher) {
        val repo = FakeBackupRepository()
        val vm = viewModel(repo, FakeConfig())
        vm.onEvent(WebDavBackupEvent.EditPassword("secret"))
        vm.onEvent(WebDavBackupEvent.TestConnection)
        advanceUntilIdle()
        assertEquals(listOf(WebDavMessage.What.NO_URL), vm.uiState.value.messages.whats())
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun jianguoYunPresetFillsHttpsUrlWithDavPath() = runTest(main.dispatcher) {
        val vm = viewModel(FakeBackupRepository(), FakeConfig())
        vm.onEvent(WebDavBackupEvent.ApplyJianguoYun)
        advanceUntilIdle()
        assertEquals(WebDavConnection.PRESET_JIANGUOYUN_URL, vm.uiState.value.baseUrl)
        assertTrue(vm.uiState.value.baseUrl.startsWith("https://"))
        assertTrue(vm.uiState.value.baseUrl.endsWith("/dav/"))
    }

    @Test
    fun backupRecordsTimeAndRefreshesListOnSuccess() = runTest(main.dispatcher) {
        val file = RemoteBackupFile("trackr_backup_20261104-153012.json", null)
        val repo = FakeBackupRepository().apply { files = listOf(file) }
        val config = FakeConfig(WebDavConnection(baseUrl = "https://dav.jianguoyun.com/dav/", username = "me@example.com"))
        val vm = viewModel(repo, config)
        vm.onEvent(WebDavBackupEvent.EditPassword("安全码"))
        vm.onEvent(WebDavBackupEvent.BackupNow)
        advanceUntilIdle()

        assertEquals(
            listOf("backup:安全码", "list:安全码"),
            repo.calls
        )
        assertEquals(listOf(file), vm.uiState.value.files)
        assertTrue(vm.uiState.value.filesLoaded)
        assertEquals(listOf(WebDavMessage.What.BACKUP_OK), vm.uiState.value.messages.whats())
        assertEquals("trackr_backup_20261104-153012.json", vm.uiState.value.messages.single().arg)
        assertTrue(!vm.uiState.value.busy)
    }

    @Test
    fun authFailureIsReportedOnceAndKeepsBusyFalse() = runTest(main.dispatcher) {
        val repo = FakeBackupRepository().apply { listFails = true }
        val config = FakeConfig(WebDavConnection(baseUrl = "https://dav.jianguoyun.com/dav/", username = "me@example.com"))
        val vm = viewModel(repo, config)
        vm.onEvent(WebDavBackupEvent.EditPassword("错的密码"))
        vm.onEvent(WebDavBackupEvent.RefreshList)
        advanceUntilIdle()

        val message = vm.uiState.value.messages.single()
        assertEquals(WebDavMessage.What.LIST_FAIL, message.what)
        assertEquals(WebDavBackupFailure.Kind.AUTH, message.kind)
        assertEquals(401, message.arg?.toIntOrNull())
        assertTrue(!vm.uiState.value.busy)
    }

    @Test
    fun restoreFlowDownloadsThenAppliesOnlyAfterConfirm() = runTest(main.dispatcher) {
        val file = RemoteBackupFile("trackr_backup_20261104-153012.json", null)
        val repo = FakeBackupRepository()
        val config = FakeConfig(WebDavConnection(baseUrl = "https://dav.jianguoyun.com/dav/", username = "me@example.com"))
        val vm = viewModel(repo, config)
        vm.onEvent(WebDavBackupEvent.EditPassword("安全码"))

        vm.onEvent(WebDavBackupEvent.OpenRestore(file))
        advanceUntilIdle()
        assertEquals(listOf("download:trackr_backup_20261104-153012.json"), repo.calls)
        assertEquals(file.name, vm.uiState.value.pendingRestore?.fileName)
        assertEquals(2, vm.uiState.value.pendingRestore?.plan?.stopCount)

        // 没确认之前绝不写本机
        assertTrue(!repo.calls.contains("applyRestore"))
        vm.onEvent(WebDavBackupEvent.ConfirmRestore)
        advanceUntilIdle()

        assertTrue(repo.calls.contains("applyRestore"))
        assertEquals(null, vm.uiState.value.pendingRestore)
        val done = vm.uiState.value.messages.single()
        assertEquals(WebDavMessage.What.RESTORE_OK, done.what)
        // 合并式恢复要能报出「补了几条 / 本机已有几条」
        assertEquals(3, done.addedFavorites)
        assertEquals(1, done.keptExistingFavorites)
    }

    @Test
    fun cancelRestoreClearsPendingWithoutApplying() = runTest(main.dispatcher) {
        val repo = FakeBackupRepository()
        val config = FakeConfig(WebDavConnection(baseUrl = "https://dav.jianguoyun.com/dav/", username = "me@example.com"))
        val vm = viewModel(repo, config)
        vm.onEvent(WebDavBackupEvent.EditPassword("安全码"))
        vm.onEvent(WebDavBackupEvent.OpenRestore(RemoteBackupFile("a.json", null)))
        advanceUntilIdle()
        vm.onEvent(WebDavBackupEvent.CancelRestore)
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.pendingRestore)
        assertTrue(!repo.calls.contains("applyRestore"))
    }
}
