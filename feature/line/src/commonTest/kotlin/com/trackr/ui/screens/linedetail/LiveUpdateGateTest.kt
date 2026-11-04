package com.trackr.ui.screens.linedetail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 追踪前「开启流体云 / 实时活动」引导的判定（用户反馈的两个 bug 都落在这个纯函数上）：
 * 1. 点「暂不开启」以后必须不再拦 —— 由 [blockingGuideFor] 的 `dismissed` 决定，
 *    Android 侧把 [LiveUpdateGate.markHandled] 写进设置（`live_update_prompt_dismissed`，和启动引导共用一个开关）；
 * 2. 系统根本没有这个功能（比如部分 vivo / iQOO：有 Android 16 的 API，但 ROM 没做胶囊，也解析不到置顶通知设置页）
 *    时 `promotionBlocked` 传 false → 不弹，不能把人领到一个不存在的开关前。
 */
class LiveUpdateGateTest {

    @Test
    fun dismissed_neverBlocksAgain_evenIfBothPermissionsMissing() {
        assertNull(
            blockingGuideFor(dismissed = true, xiaomiFocusBlocked = true, promotionBlocked = true)
        )
    }

    @Test
    fun xiaomiFocusTakesPriorityOverPromotion() {
        assertEquals(
            LiveUpdateGuide.XIAOMI_FOCUS,
            blockingGuideFor(dismissed = false, xiaomiFocusBlocked = true, promotionBlocked = true)
        )
    }

    @Test
    fun promotedOngoing_onlyWhenSystemAllowsIt() {
        assertEquals(
            LiveUpdateGuide.PROMOTED_ONGOING,
            blockingGuideFor(dismissed = false, xiaomiFocusBlocked = false, promotionBlocked = true)
        )
    }

    @Test
    fun deviceWithoutTheFeature_tracksImmediatelyWithoutAnyDialog() {
        // vivo 这类机器：canPromote() 因为「没有可引导的开关」返回 true → promotionBlocked = false
        assertNull(
            blockingGuideFor(dismissed = false, xiaomiFocusBlocked = false, promotionBlocked = false)
        )
    }
}
