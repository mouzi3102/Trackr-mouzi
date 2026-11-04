package com.trackr.core.model

/**
 * 界面模式（重构计划 §8）：标准版 / 长辈版。存在设置里（`SettingsRepository.uiMode`，DataStore 键 `ui_mode`），
 * 没存过按 [Standard]。主题按它换 Token（`TrackrTheme(uiMode)`）；第 31 步起导航结构也按它分。
 */
enum class UiMode { Standard, Elder }

/**
 * 应用配色（用户在「我的 → 显示 → 应用配色」里选）。存在设置里（`SettingsRepository.appColorPalette`，
 * DataStore 键 `app_color_palette`，按 `name` 存），没存过或认不出按 [BLUE]（就是原来的主题）。
 *
 * 只换 Material 3 的 primary/secondary/tertiary 及其容器色，语义色（到站绿 / 预警橙 / 步行灰、error）跨配色不变；
 * 长辈版（[UiMode.Elder]）用自己那套高对比 Token，不看这个值。具体色值在 `:core:designsystem` 的 `Palettes.kt`。
 */
enum class AppColorPalette { BLUE, GREEN, ORANGE, PURPLE, PINK }
