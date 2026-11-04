package com.trackr.core.data.backup

import com.trackr.core.model.EngineMode
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItemRecord
import com.trackr.core.model.FavoriteItemType
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.UiMode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 备份 JSON 的往返与宽容解析（需求 §5/§6）：未知字段忽略、缺段容忍、坏条目跳过、app/schemaVersion 头部校验、
 * UTC 时间戳 / 文件名拼装。全部单线程、不发真实请求（transport 假实现见 [BackupRepositoryScenarioTest]）。
 */
class BackupJsonTest {

    private fun samplePayload() = TrackrBackupPayload(
        schemaVersion = BACKUP_SCHEMA_VERSION,
        app = BACKUP_APP_ID,
        createdAtMillis = 1_793_806_212_000,
        settings = SettingsBackup(
            city = TransitCity(cityId = "021", cityName = "上海", pinyin = "shanghai"),
            engineMode = EngineMode.MYGOLBS.name,
            transitCodeProvider = TransitCodeProvider.ALIPAY.id,
            nearbyStopsCount = 6,
            liveNotificationEnabled = false,
            showOnLockScreen = true,
            alertThresholdStops = 3,
            uiMode = UiMode.Elder.name,
            fontScalePromptDismissed = true
        ),
        searchHistory = listOf("人民广场", "世纪大道"),
        favorites = FavoritesBackup(
            groups = listOf(element(FavoriteGroup("g1", "家"))),
            stops = listOf(element(stop("s1"))),
            routes = listOf(element(route("r1"))),
            items = listOf(
                element(FavoriteItemRecord(FavoriteItemType.STOP, "s1", "g1", 0)),
                element(FavoriteItemRecord(FavoriteItemType.ROUTE, "r1", null, 1))
            )
        )
    )

    private inline fun <reified T : Any> element(value: T) =
        backupJson.parseToJsonElement(backupJson.encodeToString(value))

    @Test
    fun roundTripKeepsEverything() {
        val original = samplePayload()
        val restored = parseBackupJson(encodeBackupJson(original))
        assertEquals(original, restored)
    }

    @Test
    fun unknownAndMissingFieldsAreTolerated() {
        val json = """
            {
              "schemaVersion": 1,
              "app": "Trackr",
              "futureFlag": { "deep": [1, 2, 3] },
              "settings": { "uiMode": "Elder", "neverHeardOf": 42 },
              "searchHistory": ["a"],
              "favorites": { "groups": [], "stops": [], "items": [], "unknownList": [ { "kind": "whatever" } ] }
            }
        """.trimIndent()
        val payload = parseBackupJson(json)
        assertEquals(BACKUP_SCHEMA_VERSION, payload.schemaVersion)
        assertEquals(UiMode.Elder.name, payload.settings?.uiMode)
        assertEquals(listOf("a"), payload.searchHistory)
        assertTrue(payload.favorites!!.stops!!.isEmpty())
        assertEquals(null, payload.settings?.city)
    }

    @Test
    fun headerValidationMatchesReference() {
        fun of(app: String, version: Int) = """{"app":"$app","schemaVersion":$version,"searchHistory":["x"]}"""
        assertEquals(WebDavBackupFailure.Kind.WRONG_APP, parseFail(of("c001apk", 1)).kind)
        assertEquals(WebDavBackupFailure.Kind.BAD_PAYLOAD, parseFail(of(BACKUP_APP_ID, 0)).kind)
        assertEquals(WebDavBackupFailure.Kind.NEWER_VERSION, parseFail(of(BACKUP_APP_ID, BACKUP_SCHEMA_VERSION + 1)).kind)
        assertEquals(WebDavBackupFailure.Kind.BAD_PAYLOAD, parseFail("not json at all [").kind)
        assertEquals(WebDavBackupFailure.Kind.BAD_PAYLOAD, parseFail("   ").kind)
    }

    private fun parseFail(text: String): WebDavBackupFailure = assertFailsWith { parseBackupJson(text) }

    @Test
    fun malformedFavoriteEntriesAreSkippedNotFatal() {
        val raw = FavoritesBackup(
            groups = listOf(
                element(FavoriteGroup("g1", "家")),
                JsonPrimitive("not-a-group"),
                buildJsonObject { put("id", ""); put("name", "无id") }
            ),
            stops = listOf(
                element(stop("s1")),
                buildJsonObject { put("id", "s2") }, // 必填字段缺 → 坏数据
                JsonPrimitive(7)
            ),
            routes = listOf(buildJsonObject { put("id", "r1"); put("name", "回家") }),
            items = listOf(
                element(FavoriteItemRecord(FavoriteItemType.STOP, "s1", "g1", 0)),
                buildJsonObject { put("type", "WIDGET"); put("refId", "x") }, // 认不出的 kind：整次恢复不失败，条目跳过
                buildJsonObject { put("type", "STOP"); put("refId", "") }
            )
        )
        val decoded = decodeBackupFavorites(raw)
        assertEquals(listOf("g1"), decoded.groups.map { it.id })
        assertEquals(listOf("s1"), decoded.stops.map { it.id })
        assertEquals(listOf("r1"), decoded.routes.map { it.id })
        assertEquals(1, decoded.items.size)
        assertEquals(6, decoded.skipped)
    }

    @Test
    fun planRestoreReportsSections() {
        val payload = samplePayload()
        val plan = planRestore(payload, decodeBackupFavorites(payload.favorites))
        assertTrue(plan.replaceSettings && plan.replaceSearchHistory && plan.replaceFavorites)
        assertEquals(9, plan.settingsFieldCount)
        assertEquals(2, plan.searchHistoryCount)
        assertEquals(
            setOf(RestoredSection.SETTINGS, RestoredSection.SEARCH_HISTORY, RestoredSection.FAVORITES),
            appliedSectionsOf(plan)
        )

        val onlyHistory = planRestore(
            TrackrBackupPayload(schemaVersion = 1, app = BACKUP_APP_ID, searchHistory = listOf("x")),
            decodeBackupFavorites(null)
        )
        assertEquals(setOf(RestoredSection.SEARCH_HISTORY), appliedSectionsOf(onlyHistory))

        val empty = planRestore(TrackrBackupPayload(schemaVersion = 1, app = BACKUP_APP_ID), DecodedFavorites())
        assertTrue(empty.empty)
        assertEquals(emptySet(), appliedSectionsOf(empty))
    }

    @Test
    fun planCountsUnknownGroupRefs() {
        val raw = FavoritesBackup(
            items = listOf(element(FavoriteItemRecord(FavoriteItemType.STOP, "s1", "ghost", 0)))
        )
        val payload = TrackrBackupPayload(schemaVersion = 1, app = BACKUP_APP_ID, favorites = raw)
        val plan = planRestore(payload, decodeBackupFavorites(raw))
        assertEquals(1, plan.itemsInUnknownGroup)
    }

    @Test
    fun utcTimestampAndFileName() {
        assertEquals("2026-11-04 15:30:12", formatBackupInstantUtc(1_793_806_212_000))
        assertEquals("2000-01-01 00:00:00", formatBackupInstantUtc(946_684_800_000))
        // 负数时刻也要正确（floorDiv）
        assertEquals("1969-12-31 23:59:59", formatBackupInstantUtc(-1_000))
        assertEquals(
            "trackr_backup_20261104-153012.json",
            backupFileNameFor("trackr_backup", 1_793_806_212_000)
        )
        assertEquals("bk_20261104-153012.json", backupFileNameFor(" bk ", 1_793_806_212_000))
        // 前缀缺省
        assertEquals("trackr_backup_19700101-000000.json", backupFileNameFor(null, 0))
    }

    private fun stop(id: String) = FavoriteStationLine(
        id = id,
        cityId = "021",
        cityName = "上海",
        lineId = "line_$id",
        lineName = "线路$id",
        startStop = "起点",
        endStop = "终点",
        targetStopId = "st_$id",
        targetStopName = "上车站",
        targetOrder = 3
    )

    private fun route(id: String) = TransitGroup(id = id, name = "路线$id")
}
