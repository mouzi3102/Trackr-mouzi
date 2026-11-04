package com.trackr.core.data.backup

import com.trackr.core.model.AppColorPalette
import com.trackr.core.model.RailMode
import com.trackr.core.model.ScheduledReminder
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
import kotlin.test.assertFalse
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

    private inline fun <reified T : Any> element(value: T) =
        backupJson.parseToJsonElement(backupJson.encodeToString(value))

    @Test
    fun roundTripKeepsEverything() {
        val original = samplePayload()
        val restored = parseBackupJson(encodeBackupJson(original))
        assertEquals(original, restored)
    }

    @Test
    fun firstVersionFileWithoutNewSectionsStillParses() {
        // 0.1.4 及以前生成的备份：没有 railMode / appColorPalette / citySearchHistory / reminders
        val legacy = """
            {
              "schemaVersion": 1,
              "app": "Trackr",
              "createdAtMillis": 1793806212000,
              "settings": { "uiMode": "Standard", "engineMode": "CHELAILE", "nearbyStopsCount": 4 },
              "searchHistory": ["人民广场"],
              "favorites": { "groups": [], "stops": [], "routes": [], "items": [] }
            }
        """.trimIndent()
        val payload = parseBackupJson(legacy)
        assertEquals(4, payload.settings?.nearbyStopsCount)
        assertEquals(null, payload.settings?.railMode)
        assertEquals(null, payload.settings?.appColorPalette)
        assertEquals(null, payload.citySearchHistory)
        assertEquals(null, payload.reminders)
        val plan = planRestore(payload, decodeBackupFavorites(payload.favorites), decodeBackupReminders(payload.reminders))
        assertTrue(plan.replaceSettings && plan.replaceSearchHistory)
        assertFalse(plan.replaceCitySearchHistory)
        assertFalse(plan.replaceReminders)
        // 老文件带着收藏段（空的），所以 FAVORITES 仍在计划里；新增的两段不在
        assertEquals(
            setOf(RestoredSection.SETTINGS, RestoredSection.SEARCH_HISTORY, RestoredSection.FAVORITES),
            appliedSectionsOf(plan)
        )
    }

    @Test
    fun newSectionsRoundTripAndPlan() {
        val payload = samplePayload().copy(
            settings = samplePayload().settings!!.copy(
                railMode = RailMode.Vertical.name,
                appColorPalette = AppColorPalette.PURPLE.name
            ),
            citySearchHistory = listOf("玉林", "上海"),
            reminders = listOf(element(reminder("r1")))
        )
        val restored = parseBackupJson(encodeBackupJson(payload))
        assertEquals(payload, restored)
        val plan = planRestore(restored, decodeBackupFavorites(restored.favorites), decodeBackupReminders(restored.reminders))
        assertTrue(plan.replaceCitySearchHistory && plan.replaceReminders)
        assertEquals(2, plan.citySearchHistoryCount)
        assertEquals(1, plan.reminderCount)
        assertEquals(
            setOf(
                RestoredSection.SETTINGS,
                RestoredSection.SEARCH_HISTORY,
                RestoredSection.CITY_SEARCH_HISTORY,
                RestoredSection.FAVORITES,
                RestoredSection.REMINDERS
            ),
            appliedSectionsOf(plan)
        )
    }

    @Test
    fun malformedReminderEntriesAreSkippedNotFatal() {
        val decoded = decodeBackupReminders(
            listOf(
                element(reminder("ok1").copy(hour = 8)),
                element(reminder("ok1").copy(hour = 9)), // 同 id 只留第一条
                buildJsonObject { put("id", ""); put("hour", 1); put("minute", 1); put("cityId", "021") },
                JsonPrimitive("not-a-reminder")
            )
        )
        assertEquals(listOf("ok1"), decoded.items.map { it.id })
        assertEquals(8, decoded.items.first().hour)
        assertEquals(2, decoded.skipped)
        assertEquals(0, decodeBackupReminders(null).items.size)
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
