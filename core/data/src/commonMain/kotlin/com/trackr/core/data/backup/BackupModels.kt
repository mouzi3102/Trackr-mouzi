package com.trackr.core.data.backup

import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteItemRecord
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitGroup
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * 「我的」WebDAV 备份的数据结构（参考 c001apk 的 BackupService：一个 JSON 文件，app/version/createdAt 头部 + 分区）。
 * 备份范围按需求：设置配置、搜索记录（线路 + 城市）、收藏（车 + 路线 + 分组 + 合一顺序）、定时提醒。
 * 高德 Key（`trackr_amap_keys`）和 WebDAV 连接配置明确不备份（连接配置含服务器地址，恢复别人的备份会把人往自己网盘上写）。
 *
 * 列表类分区存成 List<JsonElement>：逐条宽松解析，坏一条跳一条，不拖垮整个恢复（模仿参考实现 reviveFavEntries 的跳过计数）。
 */

/** 当前备份格式版本；以后改结构 +1，读旧版时缺字段用默认值 */
const val BACKUP_SCHEMA_VERSION = 1

/** 备份文件头部 `app` 字段，防止拿别家应用的备份来恢复 */
const val BACKUP_APP_ID = "Trackr"

/** 宽松解析备份 JSON：未知字段忽略、缺字段用默认值（需求：unknown/missing 都要容忍） */
val backupJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 一个备份文件的全部可恢复内容 */
@Serializable
data class TrackrBackupPayload(
    val schemaVersion: Int = 0,
    val app: String = "",
    /** 备份生成时刻（epoch millis）；认不出时间不影响恢复 */
    val createdAtMillis: Long = 0,
    val settings: SettingsBackup? = null,
    /** 搜索历史，按「最近的在前」存（和 SearchHistoryRepository 的顺序一致） */
    val searchHistory: List<String>? = null,
    /** 城市选择器的搜索历史，同样「最近的在前」（旧备份没有这段 null，恢复时不动） */
    @SerialName("citySearchHistory") val citySearchHistory: List<String>? = null,
    val favorites: FavoritesBackup? = null,
    /** 定时提醒 [ScheduledReminder] 原始元素列表；iOS 没有这个仓库时备份不带这段（null） */
    val reminders: List<JsonElement>? = null
)

/**
 * 设置快照：`trackr_settings` 里用户能改的东西（对照 SettingsKeys）。
 * 迁移标记（`legacy_*`、`favorites_group_migration_version`）是设备内部状态，不备份、也不恢复；
 * 界面字号提示「点过没有」只能单向置 true（仓库没有反向 setter）。
 * 字段为 null = 这份备份没有这项，恢复时不动这一项。
 */
@Serializable
data class SettingsBackup(
    /** 当前城市（SELECTED_CITY_JSON） */
    val city: TransitCity? = null,
    /** EngineMode 的名字 */
    val engineMode: String? = null,
    /** TransitCodeProvider 的名字 */
    val transitCodeProvider: String? = null,
    /** 首页乘车码悬浮按钮开关（上游 0.1.6 新增的设置，别漏备份） */
    val showTransitCodeButton: Boolean? = null,
    val nearbyStopsCount: Int? = null,
    val liveNotificationEnabled: Boolean? = null,
    val showOnLockScreen: Boolean? = null,
    val alertThresholdStops: Int? = null,
    /** UiMode 的名字 */
    val uiMode: String? = null,
    /** 走向条样式 RailMode 的名字（旧备份没这段 = null，恢复时不动这项） */
    val railMode: String? = null,
    /** 应用配色 AppColorPalette 的名字 */
    val appColorPalette: String? = null,
    val fontScalePromptDismissed: Boolean? = null
)

/** 收藏分区：每样都是原始 JSON 元素列表，逐条宽容解码（列表整体不是数组 → 解析这份备份本身就失败） */
@Serializable
data class FavoritesBackup(
    /** 收藏分组 [FavoriteGroup] */
    val groups: List<JsonElement>? = null,
    /** 收藏的车 [FavoriteStationLine] */
    val stops: List<JsonElement>? = null,
    /** 路线 [TransitGroup] */
    val routes: List<JsonElement>? = null,
    /** 合一顺序 + 分组索引 [FavoriteItemRecord] */
    val items: List<JsonElement>? = null
)

/** 收藏分区逐条解码后的结果（纯函数产出，方便单测；skipped = 认不出/坏数据的条数） */
data class DecodedFavorites(
    val groups: List<FavoriteGroup> = emptyList(),
    val stops: List<FavoriteStationLine> = emptyList(),
    val routes: List<TransitGroup> = emptyList(),
    val items: List<FavoriteItemRecord> = emptyList(),
    val skipped: Int = 0
)

private fun <T> JsonElement.decodeWith(strategy: DeserializationStrategy<T>): T? = try {
    backupJson.decodeFromString(strategy, this.toString())
} catch (_: Exception) {
    null
}

/**
 * 收藏分区 → 可执行的数据（纯函数）。规则（对应需求「坏条目跳过而不是整体失败」）：
 * - 分组要 id/name 非空；车、路线要 id 非空（id 是合一索引和去重的 key）；
 * - 索引条目要 refId 非空；`type` 认不出（其它版本写的枚举值）按坏数据跳过；
 * - 同一个对象重复出现只留第一条（索引按 key 去重）。
 */
fun decodeBackupFavorites(raw: FavoritesBackup?): DecodedFavorites {
    if (raw == null) return DecodedFavorites()
    var skipped = 0

    fun <T : Any> elements(
        source: List<JsonElement>?,
        serializer: KSerializer<T>,
        key: (T) -> String,
        valid: (T) -> Boolean = { true }
    ): List<T> {
        val out = LinkedHashMap<String, T>()
        source.orEmpty().forEach { element ->
            val decoded = element.decodeWith(serializer)
            if (decoded == null || !valid(decoded)) {
                skipped++
            } else {
                out.putIfAbsent(key(decoded), decoded)
            }
        }
        return out.values.toList()
    }

    val groups = elements(raw.groups, FavoriteGroup.serializer(), { it.id }) { it.id.isNotBlank() && it.name.isNotBlank() }
    val stops = elements(raw.stops, FavoriteStationLine.serializer(), { it.id }) { it.id.isNotBlank() }
    val routes = elements(raw.routes, TransitGroup.serializer(), { it.id }) { it.id.isNotBlank() }
    val items = elements(raw.items, FavoriteItemRecord.serializer(), { it.key }) { it.refId.isNotBlank() }
    return DecodedFavorites(groups, stops, routes, items, skipped)
}

/** 定时提醒分区逐条解码后的结果（skipped = 认不出/坏数据的条数） */
data class DecodedReminders(
    val items: List<ScheduledReminder> = emptyList(),
    val skipped: Int = 0
)

/**
 * 提醒分区 → 可执行的数据（纯函数）。规则和收藏一样：id 非空、按 id 去重只留第一条、坏一条跳一条。
 * 提醒 id 是设备内生成的 UUID，跨设备恢复时基本不会撞，撞了按「本地已有」跳过（不做文案相似度匹配，规则要能讲清）。
 */
fun decodeBackupReminders(raw: List<JsonElement>?): DecodedReminders {
    if (raw == null) return DecodedReminders()
    var skipped = 0
    val out = LinkedHashMap<String, ScheduledReminder>()
    raw.forEach { element ->
        val decoded = element.decodeWith(ScheduledReminder.serializer())
        if (decoded == null || decoded.id.isBlank()) {
            skipped++
        } else {
            out.putIfAbsent(decoded.id, decoded)
        }
    }
    return DecodedReminders(out.values.toList(), skipped)
}

/** 恢复会动哪几段、各自动多少条、跳过了多少坏数据（纯函数，确认对话框和单测都吃这个） */
data class RestorePlan(
    val schemaVersion: Int,
    val replaceSettings: Boolean,
    val settingsFieldCount: Int,
    val replaceSearchHistory: Boolean,
    val searchHistoryCount: Int,
    /** 城市搜索历史：整段替换（条数少、纯本地输入辅助，恢复后不会觉得少东西） */
    val replaceCitySearchHistory: Boolean,
    val citySearchHistoryCount: Int,
    val replaceFavorites: Boolean,
    val stopCount: Int,
    val routeCount: Int,
    val groupCount: Int,
    /** 条目指向备份里没有的分组：恢复后按「不分组」处理的条数（只提醒，不阻止） */
    val itemsInUnknownGroup: Int,
    /** 跳过的坏条目数 */
    val skippedFavorites: Int,
    /** 定时提醒：只补本地没有的 id，已有的不动 */
    val replaceReminders: Boolean,
    val reminderCount: Int,
    val skippedReminders: Int,
    /** 没有任何可恢复内容 */
    val empty: Boolean
)

private val settingsFieldGetters: List<(SettingsBackup) -> Any?> = listOf(
    { it.city }, { it.engineMode }, { it.transitCodeProvider }, { it.showTransitCodeButton }, { it.nearbyStopsCount },
    { it.liveNotificationEnabled }, { it.showOnLockScreen }, { it.alertThresholdStops },
    { it.uiMode }, { it.railMode }, { it.appColorPalette }, { it.fontScalePromptDismissed }
)

fun planRestore(
    payload: TrackrBackupPayload,
    favorites: DecodedFavorites,
    reminders: DecodedReminders = DecodedReminders()
): RestorePlan {
    val settingsCount = payload.settings?.let { s -> settingsFieldGetters.count { it(s) != null } } ?: 0
    val replaceSettings = payload.settings != null && settingsCount > 0
    val replaceHistory = payload.searchHistory != null
    val replaceCityHistory = payload.citySearchHistory != null
    val replaceFavorites = payload.favorites != null
    val replaceReminders = payload.reminders != null
    val knownGroups = favorites.groups.mapTo(HashSet()) { it.id }
    val unknownGroupRefs = favorites.items.count { it.groupId != null && it.groupId !in knownGroups }
    return RestorePlan(
        schemaVersion = payload.schemaVersion,
        replaceSettings = replaceSettings,
        settingsFieldCount = settingsCount,
        replaceSearchHistory = replaceHistory,
        searchHistoryCount = payload.searchHistory?.size ?: 0,
        replaceCitySearchHistory = replaceCityHistory,
        citySearchHistoryCount = payload.citySearchHistory?.size ?: 0,
        replaceFavorites = replaceFavorites,
        stopCount = favorites.stops.size,
        routeCount = favorites.routes.size,
        groupCount = favorites.groups.size,
        itemsInUnknownGroup = unknownGroupRefs,
        skippedFavorites = favorites.skipped,
        replaceReminders = replaceReminders,
        reminderCount = reminders.items.size,
        skippedReminders = reminders.skipped,
        empty = !replaceSettings && !replaceHistory && !replaceCityHistory &&
            !replaceFavorites && !replaceReminders
    )
}

/** epoch millis -> UTC 墙上时间分量（公历算法，commonMain 没有 java.time / 时区依赖） */
private fun utcParts(epochMillis: Long): LongArray {
    val totalSeconds = floorDiv(epochMillis, 1000L)
    val days = floorDiv(totalSeconds, 86_400L)
    val secondOfDay = totalSeconds - days * 86_400L
    // Howard Hinnant civil_from_days（1970-01-01 起算）
    val z = days + 719_468L
    val era = floorDiv(z, 146_097L)
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    return longArrayOf(
        year,
        m,
        d,
        secondOfDay / 3600,
        (secondOfDay % 3600) / 60,
        secondOfDay % 60
    )
}

private fun floorDiv(value: Long, divisor: Long): Long {
    val q = value / divisor
    return if (value % divisor != 0L && (value < 0) != (divisor < 0)) q - 1 else q
}

private fun pad(value: Long, width: Int): String {
    val text = value.toString()
    return if (text.length >= width) text else "0".repeat(width - text.length) + text
}

/** 「yyyy-MM-dd HH:mm:ss」（UTC），「上次备份时间」显示用 */
fun formatBackupInstantUtc(epochMillis: Long): String {
    val p = utcParts(epochMillis)
    return "${pad(p[0], 4)}-${pad(p[1], 2)}-${pad(p[2], 2)} ${pad(p[3], 2)}:${pad(p[4], 2)}:${pad(p[5], 2)}"
}
