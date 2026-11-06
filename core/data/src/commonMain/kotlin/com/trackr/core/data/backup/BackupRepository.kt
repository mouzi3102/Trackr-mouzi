package com.trackr.core.data.backup

import com.trackr.core.data.repository.CitySearchHistoryRepository
import com.trackr.core.data.repository.FavoritesRepository
import com.trackr.core.data.repository.SearchHistoryRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.model.EngineMode
import com.trackr.core.model.AppColorPalette
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteItemRecord
import com.trackr.core.model.FavoriteItemType
import com.trackr.core.model.RailMode
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.UiMode
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.core.network.remote.webdav.joinWebDavUrl
import com.trackr.core.network.remote.webdav.parseWebDavPropfind
import com.trackr.core.network.remote.webdav.webDavBasicAuth
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * WebDAV 备份 / 恢复的门面。
 *
 * 参考 c001apk 的 backup_service + webdav_client：一个 JSON 文件（settings + searchHistory + favorites 三段），
 * Basic 认证，PROPFIND 列目录、MKCOL 缺目录兜底、PUT 上传、GET 下载、DELETE 删除。
 * 状态码语义（401/403=认证、404=不存在、405/409=目录已存在）也按参考实现在这里解释，`WebDavTransport` 只回原始码。
 *
 * 密码永远只在参数里流个本次调用，不落盘、不进日志（异常消息不带凭证；URL 走 NetworkErrors 去敏）。
 */
interface BackupRepository {
    /** PROPFIND 远端目录（只读，不建目录） */
    suspend fun testConnection(password: String): Result<Unit>

    /** Depth:1 列目录，返回备份文件（非目录），新文件在前 */
    suspend fun listRemoteFiles(password: String): Result<List<RemoteBackupFile>>

    /** 收集 → 序列化 → 确保目录 → PUT。成功返回文件名，并把时间写进配置 */
    suspend fun createAndUploadBackup(password: String): Result<String>

    /** 下载 → 宽松解析 → 出恢复计划（此时还不落任何数据，等 UI 确认后 [applyRestore]） */
    suspend fun downloadBackup(fileName: String, password: String): Result<ParsedBackup>

    /** 按已确认的计划通过各仓库现有接口写回 */
    suspend fun applyRestore(parsed: ParsedBackup): Result<RestoreOutcome>

    /** 删远端文件；DELETE 404 算成功（幂等） */
    suspend fun deleteRemoteFile(fileName: String, password: String): Result<Unit>
}

/** 远端备份目录里列出的一个备份文件 */
data class RemoteBackupFile(
    /** 文件名（百分号已解码；重新拼 URL 时再转义回去） */
    val name: String,
    /** 服务器给的修改时间原文（RFC 1123，可能为 null） */
    val lastModified: String?
)

/** 下载并解析成功的一份备份：原始 payload、宽容解码后的收藏段 / 提醒段、恢复计划 */
data class ParsedBackup(
    val fileName: String,
    val payload: TrackrBackupPayload,
    val decodedFavorites: DecodedFavorites,
    val plan: RestorePlan,
    val decodedReminders: DecodedReminders = DecodedReminders()
)

/** 一次恢复实际动了哪些段 */
enum class RestoredSection { SETTINGS, SEARCH_HISTORY, CITY_SEARCH_HISTORY, FAVORITES, REMINDERS }

/**
 * [BackupRepository.applyRestore] 的结果：实际写回的段 + 各段动了几条，给界面写「恢复完成」提示。
 * 收藏和提醒是**合并**（只补本地没有的），所以报「新增/跳过」两个数；设置和搜索历史是整段写。
 */
data class RestoreOutcome(
    val applied: Set<RestoredSection>,
    /** 收藏里本地没有、这次新加进去的条数（车 + 路线） */
    val addedFavorites: Int = 0,
    /** 备份里已有同 id、保留本地那份没动的条数 */
    val keptExistingFavorites: Int = 0,
    val addedReminders: Int = 0
)

/** 纯函数：按 plan 决定要应用哪些段（applyRestore 与确认对话框共用；单测吃这个） */
fun appliedSectionsOf(plan: RestorePlan): Set<RestoredSection> {
    if (plan.empty) return emptySet()
    return buildSet {
        if (plan.replaceSettings) add(RestoredSection.SETTINGS)
        if (plan.replaceSearchHistory) add(RestoredSection.SEARCH_HISTORY)
        if (plan.replaceCitySearchHistory) add(RestoredSection.CITY_SEARCH_HISTORY)
        if (plan.replaceFavorites) add(RestoredSection.FAVORITES)
        if (plan.replaceReminders) add(RestoredSection.REMINDERS)
    }
}

/** WebDAV 备份失败。kind 给界面选文案，httpCode/detail 只在日志里出现，detail 截断且不带凭证 */
class WebDavBackupFailure(
    val kind: Kind,
    val httpCode: Int = 0,
    detail: String = ""
) : Exception("webdav backup failure: ${kind.name} http=$httpCode ${detail.take(120)}") {

    enum class Kind {
        /** 还没填服务器地址 */
        NOT_CONFIGURED,
        /** 用户名或密码错（401/403） */
        AUTH,
        /** 远端文件不存在（404） */
        NOT_FOUND,
        /** 远端目录不存在、创建被拒，PUT 落在不存在的目录上（404/409） */
        DIR_MISSING,
        /** 连不上 / 超时（传输层异常） */
        TRANSPORT,
        /** 其它非 2xx */
        SERVER,
        /** 备份格式比本机新，不敢恢复 */
        NEWER_VERSION,
        /** 不是 Trackr 的备份文件（头部的 app 字段对不上） */
        WRONG_APP,
        /** 不是合法 JSON 或结构不对 */
        BAD_PAYLOAD
    }
}

/**
 * 恢复策略（需求 §6）：
 * - 设置：只覆盖这份备份里出现的字段，全部经 [SettingsRepository] 的现有 setter（越界值由 setter 自己夹取）；
 * - 搜索历史 / 城市搜索历史：整段替换（这两段本来就是「最近的在前」的辅助列表，替换不会让人丢东西）；
 * - 收藏：**合并不替换**（同 c001apk 的 fav_cloud_sync：按 id 取并集）。本地已有同 id 的车 / 路线 / 分组保留本地那份，
 *   备份里有、本地没有的才加进来，加在合一列表末尾并按备份顺序排；分组的旧 id 先映射到本地分组（同 id 或同名），
 *   映射不到才新建。误恢复一份旧备份不会把本地新收藏删掉；
 * - 定时提醒：同样按 id 合并，只补本地没有的（`saveReminder` 会顺带注册闹钟）。
 *
 * 数据源和 UI 用的是同一批仓库接口，不直接摸 DAO / DataStore（保持收藏合一索引、顺序上限这些规则的单一权威）。
 * 城市搜索历史 / 提醒两个仓库是平台可选的（iOS 没绑提醒仓库），传不进来就跳过这两段，备份里也不带。
 */
class DefaultBackupRepository(
    private val configRepository: WebDavConfigRepository,
    private val transport: WebDavTransport,
    private val settings: SettingsRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val favoritesRepository: FavoritesRepository,
    private val nowMillis: () -> Long,
    private val citySearchHistoryRepository: CitySearchHistoryRepository? = null,
    private val reminderRepository: IScheduledReminderRepository? = null
) : BackupRepository {

    override suspend fun testConnection(password: String): Result<Unit> = attempt {
        val target = requireTarget(password)
        val response = transport.propFind(target.directoryUrl, target.auth)
        if (!response.isSuccess && response.code != 207) throw dirFailure(response)
    }

    override suspend fun listRemoteFiles(password: String): Result<List<RemoteBackupFile>> = attempt {
        val target = requireTarget(password)
        val response = transport.propFind(target.directoryUrl, target.auth)
        when {
            // 有些服务器不允许对根目录 PROPFIND（405）：按「空目录」处理而不是报错（参考实现对 405 同理放行）
            response.code == 405 || response.body.isBlank() -> emptyList()
            !response.isSuccess && response.code != 207 -> throw dirFailure(response)
            else -> {
                val entries = withContext(Dispatchers.Default) { parseWebDavPropfind(response.body) }
                entries.asSequence()
                    .filter { !it.isCollection && it.name.isNotBlank() }
                    .map { RemoteBackupFile(name = it.name, lastModified = it.lastModified) }
                    // 固定名的当前备份排最前，其余（老版本留下的带时间戳文件）按名字倒序 = 新的在前
                    .sortedWith(compareByDescending<RemoteBackupFile> { it.name == WebDavConnection.BACKUP_FILE_NAME }
                        .thenByDescending { it.name })
                    .toList()
            }
        }
    }

    override suspend fun createAndUploadBackup(password: String): Result<String> = attempt {
        val connection = configRepository.load()
        if (connection.baseUrl.isBlank()) throw WebDavBackupFailure(WebDavBackupFailure.Kind.NOT_CONFIGURED)
        val auth = webDavBasicAuth(connection.username, password)
        val directoryUrl = joinWebDavUrl(connection.baseUrl, connection.remoteDir)
        val createdAt = nowMillis()
        val json = withContext(Dispatchers.Default) { encodeBackupJson(collectPayload(createdAt)) }
        // 每次备份都写同一个文件名（PUT 覆盖上一份）：网盘里不会堆一串历史文件，用户只要「最近一次」
        val fileName = WebDavConnection.BACKUP_FILE_NAME
        ensureDirectoryChain(auth, connection.baseUrl, connection.remoteDir)
        val response = transport.putText(joinWebDavUrl(directoryUrl, fileName), auth, json)
        if (!response.isSuccess) {
            throw when (response.code) {
                404, 409 -> WebDavBackupFailure(WebDavBackupFailure.Kind.DIR_MISSING, response.code, snippet(response))
                else -> genericFailure(response, "put")
            }
        }
        configRepository.recordBackupSuccess(createdAt)
        fileName
    }

    override suspend fun downloadBackup(fileName: String, password: String): Result<ParsedBackup> = attempt {
        val target = requireTarget(password)
        val response = transport.getText(joinWebDavUrl(target.directoryUrl, fileName), target.auth)
        if (!response.isSuccess) {
            throw when (response.code) {
                404 -> WebDavBackupFailure(WebDavBackupFailure.Kind.NOT_FOUND, 404, fileName)
                else -> genericFailure(response, "get")
            }
        }
        val payload = withContext(Dispatchers.Default) { parseBackupJson(response.body) }
        val decoded = decodeBackupFavorites(payload.favorites)
        val decodedReminders = decodeBackupReminders(payload.reminders)
        ParsedBackup(
            fileName = fileName,
            payload = payload,
            decodedFavorites = decoded,
            plan = planRestore(payload, decoded, decodedReminders),
            decodedReminders = decodedReminders
        )
    }

    override suspend fun applyRestore(parsed: ParsedBackup): Result<RestoreOutcome> = attempt {
        if (parsed.plan.empty || parsed.payload.schemaVersion > BACKUP_SCHEMA_VERSION) {
            throw WebDavBackupFailure(WebDavBackupFailure.Kind.BAD_PAYLOAD)
        }
        // 平台没有这两个仓库时（iOS 没绑提醒仓库）整段跳过，不算失败
        val applied = appliedSectionsOf(parsed.plan).filterTo(LinkedHashSet()) { section ->
            when (section) {
                RestoredSection.CITY_SEARCH_HISTORY -> citySearchHistoryRepository != null
                RestoredSection.REMINDERS -> reminderRepository != null
                else -> true
            }
        }
        var addedFavorites = 0
        var keptExisting = 0
        var addedReminders = 0
        if (RestoredSection.SETTINGS in applied) applySettings(parsed.payload.settings!!)
        if (RestoredSection.SEARCH_HISTORY in applied) applySearchHistory(parsed.payload.searchHistory.orEmpty())
        if (RestoredSection.CITY_SEARCH_HISTORY in applied) {
            applyCitySearchHistory(parsed.payload.citySearchHistory.orEmpty())
        }
        if (RestoredSection.FAVORITES in applied) {
            val merged = applyFavorites(parsed.decodedFavorites)
            addedFavorites = merged.added
            keptExisting = merged.keptExisting
        }
        if (RestoredSection.REMINDERS in applied) {
            addedReminders = applyReminders(parsed.decodedReminders)
        }
        RestoreOutcome(
            applied = applied,
            addedFavorites = addedFavorites,
            keptExistingFavorites = keptExisting,
            addedReminders = addedReminders
        )
    }

    override suspend fun deleteRemoteFile(fileName: String, password: String): Result<Unit> = attempt {
        val target = requireTarget(password)
        val response = transport.delete(joinWebDavUrl(target.directoryUrl, fileName), target.auth)
        if (!response.isSuccess && response.code != 404) throw genericFailure(response, "delete")
    }

    // ---- 收集与写回（只走各仓库的公开接口） ----

    private fun collectPayload(createdAt: Long): TrackrBackupPayload = TrackrBackupPayload(
        schemaVersion = BACKUP_SCHEMA_VERSION,
        app = BACKUP_APP_ID,
        createdAtMillis = createdAt,
        settings = SettingsBackup(
            city = settings.selectedCity.value,
            engineMode = settings.engineMode.value.name,
            transitCodeProvider = settings.preferredTransitCodeProvider.value.id,
            showTransitCodeButton = settings.showTransitCodeButton.value,
            nearbyStopsCount = settings.nearbyStopsCount.value,
            liveNotificationEnabled = settings.isLiveNotificationEnabled.value,
            showOnLockScreen = settings.showOnLockScreen.value,
            alertThresholdStops = settings.alertThresholdStops.value,
            uiMode = settings.uiMode.value.name,
            // 走向条与应用配色：旧备份没这两项 = null，恢复时不动
            railMode = settings.railMode.value.name,
            appColorPalette = settings.appColorPalette.value.name,
            fontScalePromptDismissed = settings.fontScalePromptDismissed.value
        ),
        searchHistory = searchHistoryRepository.searchHistory.value.toList(),
        citySearchHistory = citySearchHistoryRepository?.citySearchHistory?.value?.toList(),
        favorites = FavoritesBackup(
            groups = favoritesRepository.favoriteGroups.value.map { it.asJsonElement() },
            stops = favoritesRepository.favorites.value.map { it.asJsonElement() },
            routes = favoritesRepository.routes.value.map { it.asJsonElement() },
            // 合一索引按 [FavoriteItemRecord] 存：`FavoriteItem` 是没有序列化器的密封类型（只有 UI 用）
            items = favoritesRepository.favoriteItems.value.map { it.record().asJsonElement() }
        ),
        reminders = reminderRepository?.reminders?.value?.map { it.asJsonElement() }
    )

    /** 合一列表里的一条 → 可序列化的存储行 */
    private fun FavoriteItem.record(): FavoriteItemRecord = when (this) {
        is FavoriteItem.Stop -> FavoriteItemRecord(FavoriteItemType.STOP, stop.id, groupId, sortOrder)
        is FavoriteItem.Route -> FavoriteItemRecord(FavoriteItemType.ROUTE, route.id, groupId, sortOrder)
    }

    private suspend fun applySettings(snapshot: SettingsBackup) {
        snapshot.city?.let { settings.selectCity(it) }
        snapshot.engineMode
            ?.let { name -> EngineMode.entries.find { it.name == name } }
            ?.let { settings.setEngineMode(it) }
        snapshot.transitCodeProvider
            ?.let { id -> TransitCodeProvider.entries.find { it.id == id || it.name == id } }
            ?.let { settings.setPreferredTransitCodeProvider(it) }
        snapshot.showTransitCodeButton?.let { settings.setShowTransitCodeButton(it) }
        snapshot.nearbyStopsCount?.let { settings.setNearbyStopsCount(it) }
        snapshot.liveNotificationEnabled?.let { settings.setLiveNotificationEnabled(it) }
        snapshot.showOnLockScreen?.let { settings.setShowOnLockScreen(it) }
        snapshot.alertThresholdStops?.let { settings.setAlertThresholdStops(it) }
        snapshot.uiMode
            ?.let { name -> UiMode.entries.find { it.name == name } }
            ?.let { settings.setUiMode(it) }
        snapshot.railMode
            ?.let { name -> RailMode.entries.find { it.name == name } }
            ?.let { settings.setRailMode(it) }
        snapshot.appColorPalette
            ?.let { name -> AppColorPalette.entries.find { it.name == name } }
            ?.let { settings.setAppColorPalette(it) }
        // 「字体提示点过了」只能单向置 true（仓库没有反向 setter）
        if (snapshot.fontScalePromptDismissed == true) settings.dismissFontScalePrompt()
    }

    /** 备份是「最近的在前」；`addSearchHistory` 把新的顶到最前，所以倒着写、正着排 */
    private suspend fun applySearchHistory(history: List<String>) {
        searchHistoryRepository.clearSearchHistory()
        history.asReversed().forEach { searchHistoryRepository.addSearchHistory(it) }
    }

    /** 城市搜索历史同款规则（仓库自己负责去重和 20 条上限） */
    private suspend fun applyCitySearchHistory(history: List<String>) {
        val repository = citySearchHistoryRepository ?: return
        repository.clearCitySearchHistory()
        history.asReversed().forEach { repository.addCitySearchHistory(it) }
    }

    /** 合并的结果：新加了几条、本地已有跳过了几条 */
    private data class FavoritesMerge(val added: Int, val keptExisting: Int)

    /**
     * 收藏按 id 取并集（参考 c001apk 的 fav_cloud_sync._mergeById）：
     * 本地已有的车 / 路线 / 分组都不动（保留本地那份，包括本地改过的名字和分组），只把备份里多出来的加进来。
     * 分组 id 是各设备自己生成的，所以本地同 id 优先、其次同名（去空格、忽略大小写），都认不出才新建。
     */
    private suspend fun applyFavorites(decoded: DecodedFavorites): FavoritesMerge {
        val localStops = favoritesRepository.favorites.value.mapTo(HashSet()) { it.id }
        val localRoutes = favoritesRepository.routes.value.mapTo(HashSet()) { it.id }
        val localGroups = favoritesRepository.favoriteGroups.value
        val localGroupIds = localGroups.mapTo(HashSet()) { it.id }
        val localGroupByName = localGroups.associateBy { it.name.trim().lowercase() }
        val groupIdsByName = HashSet<String>()

        // 1) 分组：本地已有（同 id 或同名）就复用本地 id；其余按备份顺序新建
        val groupIdMap = HashMap<String, String>()
        decoded.groups.forEach { backupGroup ->
            if (backupGroup.id in localGroupIds) {
                groupIdMap[backupGroup.id] = backupGroup.id
            } else {
                val nameKey = backupGroup.name.trim().lowercase()
                val existing = localGroupByName[nameKey]?.takeIf { it.id !in groupIdsByName }
                groupIdMap[backupGroup.id] = when {
                    existing != null -> existing.id
                    else -> favoritesRepository.createGroup(backupGroup.name).id
                }
            }
            groupIdsByName += groupIdMap.getValue(backupGroup.id)
        }

        // 2) 车：本地没有的才加（toggleFavorite 是「有就删」，所以必须先判存在）
        val addedStopIds = LinkedHashSet<String>()
        var added = 0
        var keptExisting = 0
        decoded.stops.forEach { stop ->
            if (stop.id in localStops) {
                keptExisting++
            } else {
                addedStopIds += stop.id
                if (favoritesRepository.toggleFavorite(stop)) added++ else keptExisting++
            }
        }

        // 3) 路线：本地没有的才加；分组按上面的映射改写，指向备份里没有的分组时按不分组处理
        val addedRouteIds = LinkedHashSet<String>()
        decoded.routes.forEach { route ->
            if (route.id in localRoutes) {
                keptExisting++
            } else {
                addedRouteIds += route.id
                favoritesRepository.saveRoute(
                    route.copy(customGroupId = route.customGroupId?.let { groupIdMap[it] })
                )
                added++
            }
        }

        // 4) 分组归属只重放到这次新加的条目上（已有的条目保留本地分组，不被备份覆盖）；
        //    新加条目的先后按备份索引排，索引没列到的（防御性）垫最后
        val addedKeys = (addedStopIds.map { FavoriteItem.stopKey(it) } +
            addedRouteIds.map { FavoriteItem.routeKey(it) }).toSet()
        val backupOrderKeys = LinkedHashSet<String>()
        decoded.items.sortedBy { it.sortOrder }.forEach { if (it.key in addedKeys) backupOrderKeys += it.key }
        addedKeys.filterTo(backupOrderKeys) { it !in backupOrderKeys }

        decoded.items.forEach { record ->
            if (record.key !in addedKeys) return@forEach
            // 指向备份里没有的分组 → 按不分组处理（RestorePlan 已计数提醒）
            favoritesRepository.setGroup(record.key, record.groupId?.let { groupIdMap[it] })
        }

        // 5) 顺序：本地原有顺序在前，新加的按备份顺序垫在后面
        val orderedKeys = LinkedHashSet<String>()
        favoritesRepository.favoriteItems.value.map { it.key }
            .filterTo(orderedKeys) { it !in addedKeys }
        orderedKeys += backupOrderKeys
        if (orderedKeys.size > 1) favoritesRepository.move(orderedKeys.toList())

        return FavoritesMerge(added = added, keptExisting = keptExisting)
    }

    /** 定时提醒同款 id 并集：本地已有这个 id 就不动，只补没有的（saveReminder 会顺带注册闹钟） */
    private suspend fun applyReminders(decoded: DecodedReminders): Int {
        val repository = reminderRepository ?: return 0
        val localIds = repository.reminders.value.mapTo(HashSet()) { it.id }
        var added = 0
        decoded.items.forEach { reminder ->
            if (reminder.id in localIds) return@forEach
            repository.saveReminder(reminder)
            added++
        }
        return added
    }

    // ---- WebDAV 辅助 ----

    private class WebDavTarget(val auth: String, val directoryUrl: String)

    private suspend fun requireTarget(password: String): WebDavTarget {
        val connection = configRepository.load()
        if (connection.baseUrl.isBlank()) throw WebDavBackupFailure(WebDavBackupFailure.Kind.NOT_CONFIGURED)
        return WebDavTarget(
            auth = webDavBasicAuth(connection.username, password),
            directoryUrl = joinWebDavUrl(connection.baseUrl, connection.remoteDir)
        )
    }

    /**
     * 逐级确保远端目录存在：坚果云这类服务器不会一次创建多级目录（父目录不存在时 MKCOL 直接 409 / 423），
     * 所以按路径段一层层建；已存在（207/200/405/409/412）都当成功跳过。
     */
    private suspend fun ensureDirectoryChain(auth: String, baseUrl: String, remoteDir: String) {
        val segments = remoteDir.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return
        var path = ""
        segments.forEach { segment ->
            path = if (path.isEmpty()) segment else "$path/$segment"
            val url = joinWebDavUrl(baseUrl, path)
            val probe = transport.propFind(url, auth)
            if (probe.isSuccess || probe.code == 207) return@forEach
            if (probe.code != 404 && probe.code != 409 && probe.code != 412 && probe.code != 405) {
                throw dirFailure(probe)
            }
            val created = transport.mkcol(url, auth)
            val accepted = created.isSuccess || created.code == 405 || created.code == 409 || created.code == 412
            if (!accepted) throw WebDavBackupFailure(WebDavBackupFailure.Kind.DIR_MISSING, created.code, snippet(created))
        }
    }

    private fun dirFailure(response: WebDavRaw): WebDavBackupFailure = when (response.code) {
        401, 403 -> WebDavBackupFailure(WebDavBackupFailure.Kind.AUTH, response.code, snippet(response))
        404, 409, 412, 507 -> WebDavBackupFailure(WebDavBackupFailure.Kind.DIR_MISSING, response.code, snippet(response))
        else -> genericFailure(response, "propfind")
    }

    private fun genericFailure(response: WebDavRaw, verb: String): WebDavBackupFailure = when (response.code) {
        401, 403 -> WebDavBackupFailure(WebDavBackupFailure.Kind.AUTH, response.code, snippet(response))
        else -> WebDavBackupFailure(
            WebDavBackupFailure.Kind.SERVER,
            response.code,
            "$verb ${snippet(response)}"
        )
    }

    /** 错误响应体（DavXml / 服务器提示）截 120 字方便排障；换掉所有连续空白，参考实现同款摘要 */
    private fun snippet(response: WebDavRaw): String =
        response.body.trim().replace(WHITESPACE, " ")

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: WebDavBackupFailure) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(WebDavBackupFailure(WebDavBackupFailure.Kind.TRANSPORT, detail = e.message.orEmpty().take(120)))
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/** 备份 JSON 编解码（宽松规则见 [backupJson]）；解析失败抛 [WebDavBackupFailure] */
fun encodeBackupJson(payload: TrackrBackupPayload): String = backupJson.encodeToString(payload)

/**
 * 解码并做头部校验（同参考实现的 decodeAndValidate：先 JSON 合法、再 app 对得上，坏头拒绝恢复）。
 * `schemaVersion` 比本机新时拒绝（数据以后可能换含义），旧版式靠 ignoreUnknownKeys + 缺省字段兼容。
 */
fun parseBackupJson(text: String): TrackrBackupPayload {
    if (text.isBlank()) {
        throw WebDavBackupFailure(WebDavBackupFailure.Kind.BAD_PAYLOAD, detail = "empty")
    }
    val payload = try {
        backupJson.decodeFromString(TrackrBackupPayload.serializer(), text)
    } catch (e: Exception) {
        throw WebDavBackupFailure(WebDavBackupFailure.Kind.BAD_PAYLOAD, detail = e.message.orEmpty())
    }
    if (payload.app != BACKUP_APP_ID) {
        throw WebDavBackupFailure(WebDavBackupFailure.Kind.WRONG_APP, detail = payload.app)
    }
    if (payload.schemaVersion < 1) {
        throw WebDavBackupFailure(WebDavBackupFailure.Kind.BAD_PAYLOAD, detail = "schemaVersion")
    }
    if (payload.schemaVersion > BACKUP_SCHEMA_VERSION) {
        throw WebDavBackupFailure(WebDavBackupFailure.Kind.NEWER_VERSION, detail = "${payload.schemaVersion}")
    }
    return payload
}

/** 每个条目单独转元素（备份文件里存原始 JSON，恢复时逐条宽容解码） */
private inline fun <reified T : Any> T.asJsonElement(): JsonElement =
    backupJson.parseToJsonElement(backupJson.encodeToString(this))
