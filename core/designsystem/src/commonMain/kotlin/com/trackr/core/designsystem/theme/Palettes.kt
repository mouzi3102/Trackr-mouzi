package com.trackr.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.trackr.core.model.AppColorPalette

/*
 * 多套配色（「我的 → 显示 → 应用配色」）。
 *
 * 做法：中立色（background / surface* / outline / error 等）整套复用 v3 那份，只换品牌槽位
 * （primary / secondary / tertiary 三组 + surfaceTint + inversePrimary）。理由：
 * 1) v3 只有一个品牌色（secondary / tertiary 也指 primary，chip 选中、底栏指示器读 secondaryContainer），
 *    所以换这三组就等于换了「所有带颜色的强调」，不用再动其它槽位；
 * 2) 到站绿 / 预警橙 / 步行灰这三个公交语义色（[TrackrColors]）和 error 必须跨配色稳定 —— 用户靠颜色判断
 *    「车快到 / 要注意」，换配色不能改变它们的含义；
 * 3) 中立色不动，深色对比度、WCAG 校验（TrackrThemeTest）和各页面底色表现都保持原样。
 *
 * 蓝（[AppColorPalette.BLUE]）原样返回 Color.kt 里那两份，所以默认主题没有任何变化。
 */

/** 一套配色的品牌槽位（浅 / 深各一份） */
private data class BrandTokens(
    val primary: Color,
    val onPrimary: Color,
    val container: Color,
    val onContainer: Color,
    /** 深色主题里反过来当浅色 primary 用的那个「浅一点的品牌色」 */
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkContainer: Color,
    val darkOnContainer: Color,
)

private val GreenBrand = BrandTokens(
    primary = Color(0xFF1E7D3C), onPrimary = Color(0xFFFFFFFF),
    container = Color(0xFFD8F0DD), onContainer = Color(0xFF0B3D1D),
    darkPrimary = Color(0xFF7BD698), darkOnPrimary = Color(0xFF063014),
    darkContainer = Color(0xFF1B4E2B), darkOnContainer = Color(0xFFD8F0DD),
)

private val OrangeBrand = BrandTokens(
    primary = Color(0xFFB25A00), onPrimary = Color(0xFFFFFFFF),
    container = Color(0xFFFFE7C7), onContainer = Color(0xFF5B2E00),
    darkPrimary = Color(0xFFFFB85C), darkOnPrimary = Color(0xFF4A2400),
    darkContainer = Color(0xFF6B3800), darkOnContainer = Color(0xFFFFE7C7),
)

private val PurpleBrand = BrandTokens(
    primary = Color(0xFF6D3FC4), onPrimary = Color(0xFFFFFFFF),
    container = Color(0xFFE6DDFF), onContainer = Color(0xFF25106B),
    darkPrimary = Color(0xFFCBB6FF), darkOnPrimary = Color(0xFF25106B),
    darkContainer = Color(0xFF463080), darkOnContainer = Color(0xFFE6DDFF),
)

private val PinkBrand = BrandTokens(
    primary = Color(0xFFB31E5C), onPrimary = Color(0xFFFFFFFF),
    container = Color(0xFFFFDAE6), onContainer = Color(0xFF5C0A2C),
    darkPrimary = Color(0xFFFFAFC8), darkOnPrimary = Color(0xFF5C0A2C),
    darkContainer = Color(0xFF7C2347), darkOnContainer = Color(0xFFFFDAE6),
)

/**
 * 按配色 + 深浅色出 ColorScheme。[AppColorPalette.BLUE] 就是 Color.kt 里那两份 v3 主题，未做任何改动。
 */
internal fun paletteColorScheme(palette: AppColorPalette, darkTheme: Boolean): ColorScheme {
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    val brand = when (palette) {
        AppColorPalette.BLUE -> return base
        AppColorPalette.GREEN -> GreenBrand
        AppColorPalette.ORANGE -> OrangeBrand
        AppColorPalette.PURPLE -> PurpleBrand
        AppColorPalette.PINK -> PinkBrand
    }
    val primary = if (darkTheme) brand.darkPrimary else brand.primary
    val onPrimary = if (darkTheme) brand.darkOnPrimary else brand.onPrimary
    val container = if (darkTheme) brand.darkContainer else brand.container
    val onContainer = if (darkTheme) brand.darkOnContainer else brand.onContainer
    // inversePrimary：深色主题用「深一档的品牌色」、浅色主题用「浅一档的品牌色」，正好互逆
    val inverse = if (darkTheme) brand.primary else brand.darkPrimary
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        inversePrimary = inverse,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        tertiary = primary,
        onTertiary = onPrimary,
        tertiaryContainer = container,
        onTertiaryContainer = onContainer,
        surfaceTint = primary,
    )
}

/** 设置页里画给用户看的那个小色圈（和选中后的 primary 一致） */
val AppColorPalette.previewColor: Color
    get() = when (this) {
        AppColorPalette.BLUE -> LightColorScheme.primary
        AppColorPalette.GREEN -> GreenBrand.primary
        AppColorPalette.ORANGE -> OrangeBrand.primary
        AppColorPalette.PURPLE -> PurpleBrand.primary
        AppColorPalette.PINK -> PinkBrand.primary
    }
