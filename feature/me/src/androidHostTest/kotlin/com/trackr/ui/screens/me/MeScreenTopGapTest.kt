package com.trackr.ui.screens.me

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.UiMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「我的」顶部空白回归（bug：顶部大片空白）。
 * 顶栏换成普通 [androidx.compose.material3.TopAppBar] 后，第一个分组标题「显示」的顶边 =
 * 折叠态顶栏（64dp）+ contentPadding 8dp（+ 状态栏）；旧的 `LargeTopAppBar` 展开态会把顶边推到 ≈160dp。
 * 两种界面模式都验（长辈版导航复用同一个 MeScreen，只验标准版会漏掉长辈版）。
 */
@RunWith(RobolectricTestRunner::class)
class MeScreenTopGapTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun assertTopBlankIsSmall(uiMode: UiMode) {
        composeTestRule.setContent {
            TrackrTheme(uiMode = uiMode) {
                MeScreen(state = MeUiState(), onEvent = {})
            }
        }
        val title = composeTestRule.onNodeWithText("我的")
        title.assertIsDisplayed()

        val density = composeTestRule.density.density
        val titleTopDp = title.fetchSemanticsNode().boundsInRoot.top / density
        val groupTopDp = composeTestRule.onNodeWithText("显示").fetchSemanticsNode().boundsInRoot.top / density
        val mode = if (uiMode == UiMode.Elder) "长辈版" else "标准版"

        // 标题在折叠态顶栏（64dp）里，不能在展开态预留带（≈90dp 起）里
        assertTrue("$mode：「我的」标题顶边 $titleTopDp dp，超出 64dp 顶栏", titleTopDp <= 64.dp.value)
        // 「显示」紧跟在顶栏下面；112dp 给状态栏（Robolectric 下为 0）和取整留余量，旧版 LargeTopAppBar ≈160dp 会失败
        assertTrue("$mode：第一个分组「显示」顶边 $groupTopDp dp，顶部空白太大", groupTopDp <= 112.dp.value)
    }

    @Test
    fun topBlank_normal() = assertTopBlankIsSmall(UiMode.Standard)

    @Test
    fun topBlank_elder() = assertTopBlankIsSmall(UiMode.Elder)
}
