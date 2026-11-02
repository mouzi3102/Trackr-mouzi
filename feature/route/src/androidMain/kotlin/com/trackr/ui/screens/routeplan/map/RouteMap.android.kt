package com.trackr.ui.screens.routeplan.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// 不含高德 SDK 的精简构建：Android 端不渲染地图，isRouteMapSupported = false
actual val isRouteMapSupported: Boolean = false

/**
 * Android（不含高德 SDK 的精简构建）：不渲染任何地图，只画底色区 [NoMapBackground]，
 * 搜索框与方案面板照常浮在上面。规划页布局与交互不受影响。
 */
@Composable
actual fun RouteMap(state: RouteMapState, modifier: Modifier) {
    NoMapBackground(modifier = modifier)
}
