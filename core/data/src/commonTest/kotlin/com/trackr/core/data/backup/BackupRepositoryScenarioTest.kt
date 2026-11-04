package com.trackr.core.data.backup

import com.trackr.core.model.EngineMode
import com.trackr.core.network.remote.webdav.joinWebDavUrl
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteItemRecord
import com.trackr.core.model.FavoriteItemType
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.UiMode
import com.trackr.core.model.ScheduledReminder
import kotlinx.serialization.json.JsonPrimitive
import com.trackr.core.testing.FakeCitySearchHistoryRepository
import com.trackr.core.testing.FakeFavoritesRepository
import com.trackr.core.testing.FakeSearchHistoryRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.data.repository.FakeScheduledReminderRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 假 WebDAV 传输：脚本化状态码 + 记录调用；不发真实请求（AGENTS 测试规则） */
private class FakeTransport : WebDavTransport {
    class Call(val verb: String, val url: String, val authorization: String, val body: String)

    val calls = mutableListOf<Call>()
    var propfindReply: Pair<Int, String> = 207 to """<D:multistatus xmlns:D="DAV:"/>"""
    var mkcolReply: Pair<Int, String> = 201 to ""
    var putReply: Int = 201
    var getReply: Pair<Int, String> = 200 to ""
    var deleteReply: Int = 204

    override suspend fun propFind(url: String, authorization: String) =
        calls.let { WebDavRaw(propfindReply.first, propfindReply.second, url) }
            .also { calls += Call("PROPFIND", url, authorization, "") }

    override suspend fun mkcol(url: String, authorization: String) =
        calls.let { WebDavRaw(mkcolReply.first, mkcolReply.second, url) }.also { calls += Call("MKCOL", url, authorization, "") }

    override suspend fun putText(url: String, authorization: String, content: String) =
        calls.let { WebDavRaw(putReply, "", url) }.also { calls += Call("PUT", url, authorization, content) }

    override suspend fun getText(url: String, authorization: String) =
        calls.let { WebDavRaw(getReply.first, getReply.second, url) }.also { calls += Call("GET", url, authorization, "") }

    override suspend fun delete(url: String, authorization: String) =
        calls.let { WebDavRaw(deleteReply, "", url) }.also { calls += Call("DELETE", url, authorization, "") }
}

/** 内存配置仓库（记录调用即可，持久化本身在 store 层另测） */
private class FakeConfigRepository(
    var connection: WebDavConnection = WebDavConnection(
        baseUrl = "https://dav.example.com/dav",
        username = "someone",
        remoteDir = "back ups"
    ),
    var lastRecorded: Long? = null
) : WebDavConfigRepository {
    override suspend fun load() = connection
    override suspend fun saveConnection(baseUrl: String, username: String, remoteDir: String, filePrefix: String) = Unit
    override suspend fun recordBackupSuccess(atMillis: Long) {
        lastRecorded = atMillis
    }
}

class BackupRepositoryScenarioTest {

    private fun repo(
        transport: FakeTransport,
        config: FakeConfigRepository = FakeConfigRepository(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        history: FakeSearchHistoryRepository = FakeSearchHistoryRepository(),
        favorites: FakeFavoritesRepository = FakeFavoritesRepository(),
        cityHistory: FakeCitySearchHistoryRepository = FakeCitySearchHistoryRepository(),
        reminders: FakeScheduledReminderRepository = FakeScheduledReminderRepository(),
        now: Long = 1_793_806_212_000
    ) = Triple(
        DefaultBackupRepository(
            config,
            transport,
            settings,
            history,
            favorites,
            nowMillis = { now },
            citySearchHistoryRepository = cityHistory,
            reminderRepository = reminders
        ),
        Triple(settings, history, favorites),
        config
    )

    @Test
    fun testConnectionOkAndAuthFailure() = runTest {
        val transport = FakeTransport()
        val (sut, _, _) = repo(transport)
        assertTrue(sut.testConnection("pw").isSuccess)
        val call = transport.calls.single()
        assertEquals("PROPFIND", call.verb)
        // 目录里的空格被转义（joinWebDavUrl），Basic 头 = user:pw
        assertEquals("https://dav.example.com/dav/back%20ups", call.url)
        assertEquals("Basic c29tZW9uZTpwdw==", call.authorization)

        transport.propfindReply = 401 to "Unauthorized"
        val failure = sut.testConnection("bad").exceptionOrNull() as WebDavBackupFailure
        assertEquals(WebDavBackupFailure.Kind.AUTH, failure.kind)
        assertEquals(401, failure.httpCode)
    }

    @Test
    fun missingConfigFailsBeforeNetwork() = runTest {
        val transport = FakeTransport()
        val (sut, _, _) = repo(transport, config = FakeConfigRepository(connection = WebDavConnection(baseUrl = "")))
        val failure = sut.testConnection("pw").exceptionOrNull() as WebDavBackupFailure
        assertEquals(WebDavBackupFailure.Kind.NOT_CONFIGURED, failure.kind)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun listFilesPicksNonCollectionsNewestFirst() = runTest {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/back%20ups/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
              <D:response><D:href>/dav/back%20ups/trackr_backup_20261001-000000.json</D:href><D:propstat><D:prop><D:getlastmodified>Wed, 01 Oct 2026</D:getlastmodified></D:prop></D:propstat></D:response>
              <D:response><D:href>/dav/back%20ups/trackr_backup_20261104-153012.json</D:href><D:propstat><D:prop><D:displayname>乱显.json</D:displayname></D:prop></D:propstat></D:response>
            </D:multistatus>
        """.trimIndent()
        val (sut, _, _) = repo(FakeTransport().apply { propfindReply = 207 to xml })
        val result = sut.listRemoteFiles("pw").getOrThrow()
        assertEquals(
            listOf("trackr_backup_20261104-153012.json", "trackr_backup_20261001-000000.json"),
            result.map { it.name }
        )
        assertEquals("Wed, 01 Oct 2026", result[1].lastModified)
    }

    @Test
    fun backupEnsuresDirectoryThenPutsAndRecordsTime() = runTest {
        val transport = FakeTransport()
        val config = FakeConfigRepository()
        val (sut, _, configRepo) = repo(transport, config = config)
        transport.propfindReply = 404 to ""
        val fileName = sut.createAndUploadBackup("pw").getOrThrow()
        assertEquals("trackr_backup_20261104-153012.json", fileName)
        assertEquals(listOf("PROPFIND", "MKCOL", "PUT"), transport.calls.map { it.verb })
        val put = transport.calls.last()
        assertTrue(put.url.endsWith(fileName), put.url)
        val json = put.body
        assertTrue(json.contains("\"app\":\"Trackr\"") && json.contains("\"schemaVersion\":1"), json)
        assertEquals(1_793_806_212_000, configRepo.lastRecorded)
    }

    @Test
    fun backupCreatesNestedDirectoriesLevelByLevel() = runTest {
        // 坚果云这类服务器不会一次创建多级目录（父目录不存在时 MKCOL 直接 409 / 423），必须一层层建
        val transport = FakeTransport()
        val config = FakeConfigRepository(
            connection = WebDavConnection(
                baseUrl = "https://dav.jianguoyun.com/dav",
                username = "me@example.com",
                remoteDir = "手机备份/trackr_backups"
            )
        )
        val (sut, _, _) = repo(transport, config = config)
        transport.propfindReply = 404 to "Not Found"
        val fileName = sut.createAndUploadBackup("pw").getOrThrow()
        val verbs = transport.calls.map { it.verb }
        assertEquals(listOf("PROPFIND", "MKCOL", "PROPFIND", "MKCOL", "PUT"), verbs)
        val urls = transport.calls.map { it.url }
        // 中文目录名要按 RFC 3986 百分号转义（坚果云对未转义的中文路径会 400）
        val firstLevel = joinWebDavUrl("https://dav.jianguoyun.com/dav", "手机备份")
        val secondLevel = joinWebDavUrl("https://dav.jianguoyun.com/dav", "手机备份/trackr_backups")
        assertTrue(firstLevel.contains("%E6%89%8B"), firstLevel)
        assertEquals(firstLevel, urls[0])
        assertEquals(secondLevel, urls[2])
        assertTrue(urls.last().endsWith(fileName), urls.last())
    }

    @Test
    fun existingDirectoriesSkipMkcol() = runTest {
        val transport = FakeTransport()
        val (sut, _, _) = repo(transport)
        transport.propfindReply = 207 to """<D:multistatus xmlns:D="DAV:"/>"""
        sut.createAndUploadBackup("pw").getOrThrow()
        // 目录已经在：PROPFIND 207 就够，不该再发 MKCOL
        assertEquals(listOf("PROPFIND", "PUT"), transport.calls.map { it.verb })
    }

    @Test
    fun putIntoRootRejectedMapsToDirMissingAfterMkcolDenied() = runTest {
        val transport = FakeTransport()
        val (sut, _, _) = repo(transport)
        transport.propfindReply = 404 to "Not Found"
        transport.mkcolReply = 403 to "Forbidden auto create"
        val failure = sut.createAndUploadBackup("pw").exceptionOrNull() as WebDavBackupFailure
        assertEquals(WebDavBackupFailure.Kind.DIR_MISSING, failure.kind)
        assertTrue(failure.message!!.contains("Forbidden"), failure.message ?: "")
    }

    @Test
    fun downloadParsesAndPlans() = runTest {
        val payload = TrackrBackupPayload(
            schemaVersion = 1,
            app = "Trackr",
            createdAtMillis = 5L,
            searchHistory = listOf("a")
        )
        val transport = FakeTransport()
        transport.getReply = 200 to encodeBackupJson(payload)
        val (sut, _, _) = repo(transport)
        val parsed = sut.downloadBackup("f.json", "pw").getOrThrow()
        assertEquals(payload, parsed.payload)
        assertTrue(parsed.plan.replaceSearchHistory)
        assertFalse(parsed.plan.replaceFavorites)
    }

    @Test
    fun deleteTreatsGoneFileAsSuccess() = runTest {
        val transport = FakeTransport()
        val (sut, _, _) = repo(transport)
        transport.deleteReply = 404
        assertTrue(sut.deleteRemoteFile("gone.json", "pw").isSuccess)
    }

    @Test
    fun settingsAndHistoryRestoreGoThroughRepositories() = runTest {
        val settings = FakeSettingsRepository()
        val history = FakeSearchHistoryRepository(initialSearchHistory = listOf("旧的"))
        val payload = TrackrBackupPayload(
            schemaVersion = 1,
            app = "Trackr",
            settings = SettingsBackup(
                engineMode = com.trackr.core.model.EngineMode.MYGOLBS.name,
                transitCodeProvider = TransitCodeProvider.UNIONPAY.id,
                alertThresholdStops = 9, // 越界：setter 自己夹到 3
                uiMode = UiMode.Elder.name
            ),
            searchHistory = listOf("新A", "旧B")
        )
        val decoded = decodeBackupFavorites(payload.favorites)
        val parsed = ParsedBackup("f", payload, decoded, planRestore(payload, decoded))
        val (sut, _, _) = repo(FakeTransport(), settings = settings, history = history)
        val outcome = sut.applyRestore(parsed).getOrThrow()
        assertEquals(setOf(RestoredSection.SETTINGS, RestoredSection.SEARCH_HISTORY), outcome.applied)
        assertEquals(EngineMode.MYGOLBS, settings.engineMode.value)
        assertEquals(TransitCodeProvider.UNIONPAY, settings.preferredTransitCodeProvider.value)
        assertEquals(3, settings.alertThresholdStops.value)
        assertEquals(UiMode.Elder, settings.uiMode.value)
        assertEquals(listOf("新A", "旧B"), history.searchHistory.value)
    }

    @Test
    fun favoritesRestoreMergesByIdAndKeepsLocalEntries() = runTest {
        // 本地：一旧车（和备份里同 id，但名字被用户改过）、一旧路线、一旧分组
        val existing = FakeFavoritesRepository(
            initialFavorites = listOf(line("new_stop", "本地改过名的线")),
            initialRoutes = listOf(TransitGroup(id = "old_route", name = "旧路线")),
            initialGroups = listOf(FavoriteGroup("old_group", "旧分组"))
        )
        val stop = line("new_stop", "备份里的旧名")
        val route = TransitGroup(id = "new_route", name = "新路线", customGroupId = "g_backup")
        val payload = TrackrBackupPayload(
            schemaVersion = 1,
            app = "Trackr",
            favorites = FavoritesBackup(
                groups = listOf(js(FavoriteGroup("g_backup", "公司"))),
                stops = listOf(js(stop), js(line("new_stop_dup", "第二条藏线"))),
                routes = listOf(js(route)),
                items = listOf(
                    js(FavoriteItemRecord(FavoriteItemType.ROUTE, "new_route", "g_backup", 0)),
                    js(FavoriteItemRecord(FavoriteItemType.STOP, "new_stop", "g_backup", 1)),
                    js(FavoriteItemRecord(FavoriteItemType.STOP, "new_stop_dup", null, 2)),
                    // 指向已不存在的车：应用时被忽略
                    js(FavoriteItemRecord(FavoriteItemType.STOP, "ghost", "g_backup", 3))
                )
            )
        )
        val decoded = decodeBackupFavorites(payload.favorites)
        val parsed = ParsedBackup("f", payload, decoded, planRestore(payload, decoded))
        val (sut, _, _) = repo(FakeTransport(), favorites = existing)
        val outcome = sut.applyRestore(parsed).getOrThrow()
        assertEquals(setOf(RestoredSection.FAVORITES), outcome.applied)

        // 合并：本地独有的不动，备份独有的加进来；同 id 的保留本地那份（名字不被备份覆盖）
        assertEquals(listOf("new_stop", "new_stop_dup"), existing.favorites.value.map { it.id })
        assertEquals("本地改过名的线", existing.favorites.value.first { it.id == "new_stop" }.lineName)
        assertEquals(listOf("old_route", "new_route"), existing.routes.value.map { it.id })
        // 备份里本地没有的：一条车 + 一条路线 = 2 条；同 id 的 new_stop 算保留本地
        assertEquals(2, outcome.addedFavorites)
        assertEquals(1, outcome.keptExistingFavorites)

        // 分组：本地那个「旧分组」没被删，备份的「公司」新建一个
        val groups = existing.favoriteGroups.value
        assertEquals(listOf("旧分组", "公司"), groups.map { it.name })
        val newGroupId = groups.first { it.name == "公司" }.id

        // 顺序：本地原有在前（new_stop 算本地已有），新加的按备份索引排（route 0 → dup 2）
        assertEquals(
            listOf(
                FavoriteItem.stopKey("new_stop"),
                FavoriteItem.routeKey("old_route"),
                FavoriteItem.routeKey("new_route"),
                FavoriteItem.stopKey("new_stop_dup")
            ),
            existing.favoriteItems.value.map { it.key }
        )
        val byKey = existing.favoriteItems.value.associateBy { it.key }
        // 只有这次新加的条目才重放分组；本地已有的那条分组不动
        assertEquals(newGroupId, byKey.getValue(FavoriteItem.routeKey("new_route")).groupId)
        assertEquals(null, byKey.getValue(FavoriteItem.stopKey("new_stop_dup")).groupId)
        assertEquals(null, byKey.getValue(FavoriteItem.stopKey("new_stop")).groupId)
    }

    @Test
    fun favoritesRestoreReusesLocalGroupWithSameName() = runTest {
        val existing = FakeFavoritesRepository(initialGroups = listOf(FavoriteGroup("local_g", "公司")))
        val payload = TrackrBackupPayload(
            schemaVersion = 1,
            app = "Trackr",
            favorites = FavoritesBackup(
                groups = listOf(js(FavoriteGroup("backup_g", "公司 "))),
                stops = listOf(js(line("s1", "一线"))),
                items = listOf(js(FavoriteItemRecord(FavoriteItemType.STOP, "s1", "backup_g", 0)))
            )
        )
        val decoded = decodeBackupFavorites(payload.favorites)
        val parsed = ParsedBackup("f", payload, decoded, planRestore(payload, decoded))
        val (sut, _, _) = repo(FakeTransport(), favorites = existing)
        sut.applyRestore(parsed).getOrThrow()
        // 同名（忽略尾部空格 / 大小写）复用本地分组，不再新建第二个「公司」
        assertEquals(listOf("local_g"), existing.favoriteGroups.value.map { it.id })
        assertEquals("local_g", existing.favoriteItems.value.first { it.key == FavoriteItem.stopKey("s1") }.groupId)
    }

    @Test
    fun citySearchHistoryAndRemindersRestoreMerge() = runTest {
        val cityHistory = FakeCitySearchHistoryRepository(initialCitySearchHistory = listOf("本地城市"))
        val reminders = FakeScheduledReminderRepository(
            initialReminders = listOf(reminder("r_local"), reminder("r_dup"))
        )
        val payload = TrackrBackupPayload(
            schemaVersion = 1,
            app = "Trackr",
            citySearchHistory = listOf("北京", "上海"),
            reminders = listOf(js(reminder("r_dup")), js(reminder("r_new")), JsonPrimitive("坏数据"))
        )
        val decoded = decodeBackupFavorites(payload.favorites)
        val decodedReminders = decodeBackupReminders(payload.reminders)
        val parsed = ParsedBackup(
            "f",
            payload,
            decoded,
            planRestore(payload, decoded, decodedReminders),
            decodedReminders
        )
        val (sut, _, _) = repo(FakeTransport(), cityHistory = cityHistory, reminders = reminders)
        val outcome = sut.applyRestore(parsed).getOrThrow()
        assertEquals(
            setOf(RestoredSection.CITY_SEARCH_HISTORY, RestoredSection.REMINDERS),
            outcome.applied
        )
        // 城市历史整段替换（最近的在前）
        assertEquals(listOf("北京", "上海"), cityHistory.citySearchHistory.value)
        // 提醒按 id 合并：已有的 r_local / r_dup 不动，只补 r_new
        assertEquals(listOf("r_local", "r_dup", "r_new"), reminders.reminders.value.map { it.id })
        assertEquals(1, outcome.addedReminders)
        // 计划里报出备份带了几条提醒、跳过了几条坏数据
        assertEquals(2, parsed.plan.reminderCount)
        assertEquals(1, parsed.plan.skippedReminders)
    }

    private fun js(value: Any): kotlinx.serialization.json.JsonElement {
        val json = when (value) {
            is FavoriteGroup -> backupJson.encodeToString(FavoriteGroup.serializer(), value)
            is FavoriteStationLine -> backupJson.encodeToString(FavoriteStationLine.serializer(), value)
            is TransitGroup -> backupJson.encodeToString(TransitGroup.serializer(), value)
            is FavoriteItemRecord -> backupJson.encodeToString(FavoriteItemRecord.serializer(), value)
            is ScheduledReminder -> backupJson.encodeToString(ScheduledReminder.serializer(), value)
            else -> error("unsupported fixture type")
        }
        return backupJson.parseToJsonElement(json)
    }

    private fun reminder(id: String) = ScheduledReminder(
        id = id,
        hour = 7,
        minute = 30,
        cityId = "021",
        cityName = "上海",
        lineId = "line_$id",
        lineName = "$id 路",
        direction = 1,
        endStopName = "终点站",
        targetStopId = "st_$id",
        targetStopName = "上车站",
        targetOrder = 2
    )

    private fun line(id: String, name: String) = FavoriteStationLine(
        id = id,
        cityId = "021",
        cityName = "上海",
        lineId = "line_$id",
        lineName = name,
        startStop = "起点站",
        endStop = "终点站",
        targetStopId = "st_$id",
        targetStopName = "上车站",
        targetOrder = 2
    )
}
