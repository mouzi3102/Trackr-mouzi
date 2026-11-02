package com.trackr.ui.screens.stopdetail

import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回归测试：v0.0.6 修复「站详情点线路进不去」。
 * 掌上公交 CMD=105 的灵璧县数据不返回 stationOrder（默认 0），Chelaile 同名站多站台时部分行
 * 缺 targetOrder/lineId。修复前 `selectable = lineId.isNotBlank() && targetOrder > 0` 使这些行
 * 不可点击（表现为「线路点不进去」）；修复后仅要求 lineId 非空，targetOrder=0 时由 LineDetail 兜底默认站。
 */
class StopDetailMapperSelectableTest {

    private fun status(lineId: String, targetOrder: Int, name: String = "K908路"): NearbyStopLineStatus =
        NearbyStopLineStatus(
            line = BusLineDirection(
                lineId = lineId,
                name = name,
                lineNo = name,
                direction = 0,
                startStop = "睢宁客运西站",
                endStop = "公交总站",
                firstTime = "07:00",
                lastTime = "16:30",
                source = DataSourceType.MYGOLBS
            ),
            targetOrder = targetOrder,
            targetStopId = "32",
            targetStopName = "双沟高铁站",
            statusText = "等待发车",
            approachingBuses = emptyList()
        )

    private fun stop(statuses: List<NearbyStopLineStatus>): NearbyStop = NearbyStop(
        stopId = "mg:灵璧县:双沟高铁站",
        name = "双沟高铁站",
        wgsLat = 0.0,
        wgsLng = 0.0,
        physicalStId = "mg:灵璧县:双沟高铁站",
        namesakeStId = "mg:灵璧县:双沟高铁站",
        lines = statuses,
        source = DataSourceType.MYGOLBS
    )

    @Test
    fun targetOrderZero_isStillSelectable_whenLineIdPresent() {
        // 掌上公交灵璧 CMD=105 不返回 stationOrder -> targetOrder=0：修复前不可点，修复后必须可点
        val rows = rowsOf("city", stop(listOf(status("mg:0:灵璧县:K908路:2", targetOrder = 0))), emptySet())
        assertEquals(1, rows.size)
        assertTrue(rows[0].selectable, "targetOrder=0 但 lineId 完整时必须可点（LineDetail 兜底默认站）")
        assertTrue(rows[0].lineId.isNotBlank())
    }

    @Test
    fun blankLineId_staysNonSelectable() {
        // 真实数据拿不到 lineId 的行仍不可点（点了也没法加载线路）
        val rows = rowsOf("city", stop(listOf(status("", targetOrder = 3))), emptySet())
        assertEquals(1, rows.size)
        assertFalse(rows[0].selectable, "lineId 为空的行仍不可点")
    }

    @Test
    fun completeStatus_remainsSelectable() {
        val rows = rowsOf("city", stop(listOf(status("mg:0:灵璧县:K908路:2", targetOrder = 3))), emptySet())
        assertTrue(rows[0].selectable)
        assertEquals(3, rows[0].targetOrder)
    }
}
