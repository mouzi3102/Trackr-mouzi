package com.trackr.ui.screens.linedetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.ui.platform.LiveUpdateSettings
import org.koin.compose.koinInject

/**
 * 判断和跳转原样转调 [LiveUpdateSettings]（:androidApp 的厂商适配），和旧页面的写法一致。
 *
 * 「表过态就不再拦」记在设置里（[SettingsRepository.liveUpdatePromptDismissed]，和启动时那次引导共用一个开关）：
 * 旧版每次点追踪都重新弹，且弹在根本没有这个开关的机型上（vivo 等），这是本次修的 bug。
 */
@Composable
internal actual fun rememberLiveUpdateGate(): LiveUpdateGate {
    val context = LocalContext.current
    val settings: LiveUpdateSettings = koinInject()
    val appSettings: SettingsRepository = koinInject()
    return remember(context, settings, appSettings) {
        object : LiveUpdateGate {
            override fun blockingGuide(): LiveUpdateGuide? = blockingGuideFor(
                dismissed = appSettings.liveUpdatePromptDismissed.value,
                // 小米：焦点通知（超级岛）没授权
                xiaomiFocusBlocked = settings.isXiaomiRom() && !settings.canShowXiaomiFocus(context),
                // 原生实时活动 / 置顶通知：这台机器找得到开关、且用户还没授权，才值得拦一下
                promotionBlocked = settings.canAskForPromotion(context) && !settings.canPromote(context)
            )

            override fun openSettings(guide: LiveUpdateGuide) {
                when (guide) {
                    LiveUpdateGuide.XIAOMI_FOCUS -> settings.openXiaomiNotificationSettings(context)
                    LiveUpdateGuide.PROMOTED_ONGOING -> settings.openPromotionSettings(context)
                }
            }

            override fun deviceHint(guide: LiveUpdateGuide): String = when {
                guide == LiveUpdateGuide.XIAOMI_FOCUS -> "检测到您正在使用小米澎湃OS（Xiaomi HyperOS）。"
                settings.isColorOs() -> "检测到您正在使用 OPPO ColorOS / 一加 / 真我 系统。"
                else -> "检测到您的系统支持 Android 16 实时活动（Live Updates）。"
            }

            override fun markHandled(guide: LiveUpdateGuide) {
                appSettings.dismissLiveUpdatePrompt()
            }
        }
    }
}
