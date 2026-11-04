package com.trackr.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.model.AppColorPalette
import com.trackr.core.model.UiMode
import com.trackr.ui.platform.LiveUpdateAccess
import com.trackr.ui.platform.LiveUpdateFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 启动时「开启流体云 / 实时活动」引导要显示的内容。
 * 文案按 [family] 从 `:feature:line` 的 composeResources 取（和线路详情那个引导同一套话），这里只带判断结果。
 *
 * @param systemName 给用户看的系统功能名，比如「OPPO 流体云」
 */
data class LiveUpdatePromptUi(
    val systemName: String,
    val family: LiveUpdateFamily
)

/**
 * 根级壳状态（重构计划 §8.2）。
 *
 * @param uiMode 当前界面模式，决定主题（第 31 步起也决定导航结构）
 * @param colorPalette 应用配色，只换品牌色；长辈版主题不看它
 * @param fontScalePromptDismissed 「试试长辈版」提示已经点过
 * @param liveUpdatePrompt 「开启流体云 / 实时活动」引导；null = 不弹（平台没有这能力、已开过、点过不再提示、长辈版）
 */
data class AppShellState(
    val uiMode: UiMode = UiMode.Standard,
    val colorPalette: AppColorPalette = AppColorPalette.BLUE,
    val fontScalePromptDismissed: Boolean = false,
    val liveUpdatePrompt: LiveUpdatePromptUi? = null,
)

/**
 * Activity 级（[TrackrRoot] 在 NavDisplay 之上取），读设置里的界面模式。
 *
 * 计划里 `shell` 是可空的（null = 还没读到，配合启动图避免先闪标准版）；这里的设置仓库构造时就同步读完存储，
 * StateFlow 一开始就是真实值，所以不需要 null 状态和启动图。
 *
 * @param liveUpdate 状态栏胶囊（实时活动）的系统状态；平台没有这项（iOS）时不绑定，也就不弹引导
 */
class AppShellViewModel(
    private val settings: SettingsRepository,
    private val liveUpdate: LiveUpdateAccess? = null
) : ViewModel() {

    /** 每次回到前台重查（用户可能刚从系统设置回来）；null = 这一轮不弹 */
    private val liveUpdatePrompt = MutableStateFlow<LiveUpdatePromptUi?>(null)

    val shell: StateFlow<AppShellState> = combine(
        settings.uiMode,
        settings.appColorPalette,
        settings.fontScalePromptDismissed,
        settings.liveUpdatePromptDismissed,
        liveUpdatePrompt
    ) { mode, palette, dismissed, promptDismissed, prompt ->
        AppShellState(
            uiMode = mode,
            colorPalette = palette,
            fontScalePromptDismissed = dismissed,
            liveUpdatePrompt = prompt?.takeUnless { promptDismissed }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppShellState(
            uiMode = settings.uiMode.value,
            colorPalette = settings.appColorPalette.value,
            fontScalePromptDismissed = settings.fontScalePromptDismissed.value
        )
    )

    init {
        refreshLiveUpdatePrompt()
    }

    /**
     * 重查要不要弹「开启流体云 / 实时活动」：系统支持、权限没开、以前没引导过才弹。
     * 长辈版不弹（长辈版没有追踪胶囊这套交互）；点过「暂不开启」后永不再弹。
     */
    fun refreshLiveUpdatePrompt() {
        val access = liveUpdate
        val dismissed = settings.liveUpdatePromptDismissed.value
        val elder = settings.uiMode.value == UiMode.Elder
        if (access == null || dismissed || elder) {
            liveUpdatePrompt.value = null
            return
        }
        val status = runCatching { access.status() }.getOrNull()
        liveUpdatePrompt.value = status
            ?.takeIf { it.supported && !it.allowed }
            ?.let { LiveUpdatePromptUi(systemName = it.systemName, family = it.family) }
    }

    /** 提示里点「打开长辈版」：切过去，提示也算点过 */
    fun enableElderFromPrompt() {
        settings.setUiMode(UiMode.Elder)
        settings.dismissFontScalePrompt()
    }

    /** 提示里点「先不用」或点外面关掉：以后不再提示 */
    fun dismissFontScalePrompt() = settings.dismissFontScalePrompt()

    /** 「暂不开启」/关掉引导：以后启动不再弹（「我的 → 状态栏胶囊」里仍可随时去开） */
    fun dismissLiveUpdatePrompt() {
        liveUpdatePrompt.value = null
        settings.dismissLiveUpdatePrompt()
    }

    /** 「前往开启」：跳系统设置，同时这条引导算点过（从设置回来后不重复打扰） */
    fun openLiveUpdateSettings() {
        dismissLiveUpdatePrompt()
        runCatching { liveUpdate?.openSettings() }
    }
}
