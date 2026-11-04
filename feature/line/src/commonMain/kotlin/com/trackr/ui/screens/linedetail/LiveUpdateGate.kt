package com.trackr.ui.screens.linedetail

import androidx.compose.runtime.Composable

/** 开始追踪前要引导去开的系统实时活动权限 */
enum class LiveUpdateGuide {
    /** 小米焦点通知（超级岛） */
    XIAOMI_FOCUS,

    /** Android 16 实时活动 / 置顶通知（含 OPPO 流体云） */
    PROMOTED_ONGOING
}

/**
 * 开始追踪前检查系统实时活动权限（原来线路页直接调 `LiveUpdateSettings` + `Context`）。
 * Android：小米没开焦点通知、或系统不允许置顶通知时返回要引导的项；iOS 不需要，永远放行。
 */
interface LiveUpdateGate {

    /** 现在开始追踪前要先引导哪一项；null = 直接开始 */
    fun blockingGuide(): LiveUpdateGuide?

    /** 打开 [guide] 对应的系统设置页 */
    fun openSettings(guide: LiveUpdateGuide)

    /** 引导弹窗里的机型说明 */
    fun deviceHint(guide: LiveUpdateGuide): String

    /**
     * 记下「这个引导用户已经表过态了」（点「暂不开启」或「前往开启」都算），
     * 之后 `blockingGuide()` 一律返回 null —— 不能每次都拦着用户开始追踪（bug：点暂不开启不记忆，
     * 每次点追踪都重新弹，甚至弹在根本没有这个开关的机型上）。
     * 想再开：「我的 → 状态栏胶囊」那一行进系统设置。
     */
    fun markHandled(guide: LiveUpdateGuide)
}

/**
 * 纯函数：追踪前该先引导哪一项（[rememberLiveUpdateGate] 的 Android 实现只负责把这四个布尔值取出来）。
 *
 * @param dismissed 用户已经对这个引导表过态（[LiveUpdateGate.markHandled]）→ 永远不再拦
 * @param xiaomiFocusBlocked 小米澎湃OS 且焦点通知没开
 * @param promotionBlocked 系统**确实实现了**原生实时活动 / 置顶通知，且用户没授权
 *   （厂商没实现的机型必须传 false：`LiveUpdatePromotion.canPromote` 在不支持的机型上返回 true，
 *    androidx 的 `canPostPromotedNotifications()` 只是授权信号，不代表这台机器有这个功能）
 */
internal fun blockingGuideFor(
    dismissed: Boolean,
    xiaomiFocusBlocked: Boolean,
    promotionBlocked: Boolean
): LiveUpdateGuide? = when {
    dismissed -> null
    xiaomiFocusBlocked -> LiveUpdateGuide.XIAOMI_FOCUS
    promotionBlocked -> LiveUpdateGuide.PROMOTED_ONGOING
    else -> null
}

@Composable
internal expect fun rememberLiveUpdateGate(): LiveUpdateGate