package com.trackr.core.data.backup

import com.trackr.core.data.repository.FavoritesRepository
import com.trackr.core.data.repository.SearchHistoryRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.model.EngineMode
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteItemType
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.UiMode
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

/** 下载并解析成功的一份备份：原始 payload、宽容解码后的收藏段、恢复计划 */
data class ParsedBackup(
    val fileName: String,
    val payload: TrackrBackupPayload,
    val decodedFavorites: DecodedFavorites,
    val plan: RestorePlan
)

/** 一次恢复实际动了哪些段 */
enum class RestoredSection { SETTINGS, SEARCH_HISTORY, FAVORITES }

/** [BackupRepository.applyRestore] 的结果：实际写回的段 */
data class RestoreOutcome(val applied: Set<RestoredSection>)

/** 纯函数：按 plan 决定要应用哪些段（applyRestore 与确认对话框共用；单测吃这个） */
fun appliedSectionsOf(plan: RestorePlan): Set<RestoredSection> {
    if (plan.empty) return emptySet()
    return buildSet {
        if (plan.replaceSettings) add(RestoredSection.SETTINGS)
        if (plan.replaceSearchHistory) add(RestoredSection.SEARCH_HISTORY)
        if (plan.replaceFavorites) add(RestoredSection.FAVORITES)
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
 * 恢复策略（需求 §6；**整体替换**语义已在界面上向用户讲清）：
 * - 搜索历史：清空后按备份倒序逐条 `addSearchHistory`，去重与 20 条上限走仓库自身的规则；
 * - 收藏：先删光（分组、车、路线）、再按备份重建——公开接口只有 `createGroup`（新 id），所以分组的旧 id → 新 id
 *   逐份映射，合一顺序通过 `move` 落位，分组归属通过 `setGroup` 重放；
 * - 设置：只覆盖这份备份里出现的字段，且全部经 [SettingsRepository] 的现有 setter（越界值由 setter 自己夹取）。
 *
 * 数据源和 UI 用的是同一批仓库接口，不直接摸 DAO / DataStore（保持收藏合一索引、顺序上限这些规则的单一权威）。
 */
class DefaultBackupRepository(
    private val configRepository: WebDavConfigRepository,
    private val transport: WebDavTransport,
    private val settings: SettingsRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val favoritesRepository: FavoritesRepository,
    private val nowMillis: () -> Long
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
                    .sortedByDescending { it.name } // 文件名带时间戳，倒序 = 新的在前
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
        val fileName = backupFileNameFor(connection.filePrefix, createdAt)
        ensureDirectory(auth, directoryUrl)
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
        ParsedBackup(fileName, payload, decoded, planRestore(payload, decoded))
    }

    override suspend fun applyRestore(parsed: ParsedBackup): Result<RestoreOutcome> = attempt {
        if (parsed.plan.empty || parsed.payload.schemaVersion > BACKUP_SCHEMA_VERSION) {
            throw WebDavBackupFailure(WebDavBackupFailure.Kind.BAD_PAYLOAD)
        }
        val applied = appliedSectionsOf(parsed.plan)
        if (applied.contains(RestoredSection.SETTINGS)) applySettings(parsed.payload.settings!!)
        if (applied.contains(RestoredSection.SEARCH_HISTORY)) applySearchHistory(parsed.payload.searchHistory.orEmpty())
        if (applied.contains(RestoredSection.FAVORITES)) applyFavorites(parsed.decodedFavorites)
        RestoreOutcome(applied)
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
            nearbyStopsCount = settings.nearbyStopsCount.value,
            liveNotificationEnabled = settings.isLiveNotificationEnabled.value,
            showOnLockScreen = settings.showOnLockScreen.value,
            alertThresholdStops = settings.alertThresholdStops.value,
            uiMode = settings.uiMode.value.name,
            fontScalePromptDismissed = settings.fontScalePromptDismissed.value
        ),
        searchHistory = searchHistoryRepository.searchHistory.value.toList(),
        favorites = FavoritesBackup(
            groups = favoritesRepository.favoriteGroups.value.map { it.asJsonElement() },
            stops = favoritesRepository.favorites.value.map { it.asJsonElement() },
            routes = favoritesRepository.routes.value.map { it.asJsonElement() },
            items = favoritesRepository.favoriteItems.value.map { it.asJsonElement() }
        )
    )

    private suspend fun applySettings(snapshot: SettingsBackup) {
        snapshot.city?.let { settings.selectCity(it) }
        snapshot.engineMode
            ?.let { name -> EngineMode.entries.find { it.name == name } }
            ?.let { settings.setEngineMode(it) }
        snapshot.transitCodeProvider
            ?.let { id -> TransitCodeProvider.entries.find { it.id == id || it.name == id } }
            ?.let { settings.setPreferredTransitCodeProvider(it) }
        snapshot.nearbyStopsCount?.let { settings.setNearbyStopsCount(it) }
        snapshot.liveNotificationEnabled?.let { settings.setLiveNotificationEnabled(it) }
        snapshot.showOnLockScreen?.let { settings.setShowOnLockScreen(it) }
        snapshot.alertThresholdStops?.let { settings.setAlertThresholdStops(it) }
        snapshot.uiMode
            ?.let { name -> UiMode.entries.find { it.name == name } }
            ?.let { settings.setUiMode(it) }
        // 「字体提示点过了」只能单向置 true（仓库没有反向 setter）
        if (snapshot.fontScalePromptDismissed == true) settings.dismissFontScalePrompt()
    }

    /** 备份是「最近的在前」；`addSearchHistory` 把新的顶到最前，所以倒着写、正着排 */
    private suspend fun applySearchHistory(history: List<String>) {
        searchHistoryRepository.clearSearchHistory()
        history.asReversed().forEach { searchHistoryRepository.addSearchHistory(it) }
    }

    private suspend fun applyFavorites(decoded: DecodedFavorites) {
        val currentStops = favoritesRepository.favorites.value.map { it.id }
        val currentRoutes = favoritesRepository.routes.value.map { it.id }
        val currentGroups = favoritesRepository.favoriteGroups.value.map { it.id }
        currentGroups.forEach { favoritesRepository.deleteGroup(it) }
        currentStops.forEach { favoritesRepository.removeFavorite(it) }
        currentRoutes.forEach { favoritesRepository.removeRoute(it) }

        val groupIdMap = HashMap<String, String>()
        decoded.groups.forEach { old ->
            groupIdMap[old.id] = favoritesRepository.createGroup(old.name).id
        }
        decoded.stops.forEach { favoritesRepository.toggleFavorite(it) }
        decoded.routes.forEach { route ->
            favoritesRepository.saveRoute(route.copy(customGroupId = route.customGroupId?.let { groupIdMap[it] }))
        }

        val stopIds = decoded.stops.mapTo(HashSet()) { it.id }
        val routeIds = decoded.routes.mapTo(HashSet()) { it.id }
        decoded.items.forEach { record ->
            val exists = when (record.type) {
                FavoriteItemType.STOP -> record.refId in stopIds
                FavoriteItemType.ROUTE -> record.refId in routeIds
            }
            if (!exists) return@forEach
            // 指向备份里没有的分组 → 按不分组处理（RestorePlan 已计数提醒）
            favoritesRepository.setGroup(record.key, record.groupId?.let { groupIdMap[it] })
        }

        // 合一顺序：备份索引在前（认得出对象的），其它备份条目垫后（理论走不到，防御性补齐）
        val restoredKeys = (decoded.stops.map { FavoriteItem.stopKey(it.id) } +
            decoded.routes.map { FavoriteItem.routeKey(it.id) }).toSet()
        val orderedKeys = LinkedHashSet<String>()
        decoded.items.sortedBy { it.sortOrder }.forEach { if (it.key in restoredKeys) orderedKeys += it.key }
        decoded.stops.forEach { orderedKeys += FavoriteItem.stopKey(it.id) }
        decoded.routes.forEach { orderedKeys += FavoriteItem.routeKey(it.id) }
        favoritesRepository.move(orderedKeys.toList())
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

    /** 目录不存在就 MKCOL；405/409/412 都当「目录已有」放过（同参考实现 _mkcolBackupDir 的判法） */
    private suspend fun ensureDirectory(auth: String, directoryUrl: String) {
        val probe = transport.propFind(directoryUrl, auth)
        if (probe.isSuccess || probe.code == 207 || probe.code == 405) return
        if (probe.code != 404 && probe.code != 409 && probe.code != 412) throw dirFailure(probe)
        val created = transport.mkcol(directoryUrl, auth)
        val accepted = created.isSuccess || created.code == 405 || created.code == 409 || created.code == 412
        if (!accepted) throw WebDavBackupFailure(WebDavBackupFailure.Kind.DIR_MISSING, created.code, snippet(created))
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
