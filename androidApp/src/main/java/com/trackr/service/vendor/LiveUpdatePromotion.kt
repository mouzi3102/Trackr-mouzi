package com.trackr.service.vendor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat

object LiveUpdatePromotion {

    private const val TAG = "LiveUpdatePromotion"

    /** 系统版本上的支持：Android 16（API 36）起才有「置顶通知 / 实时活动」这套 API */
    fun isPromotionSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    /**
     * 这台机器上到底有没有「可以给本应用打开置顶通知 / 实时活动」的地方。
     *
     * 为什么需要这一条：androidx 1.19 只有 [NotificationManagerCompat.canPostPromotedNotifications]，那是**用户授权**信号，
     * 不代表 ROM 真的实现了胶囊 —— 没实现的 ROM（部分 vivo / iQOO 等）上也照样返回 false，
     * 旧逻辑于是每次点追踪都弹「检测到您的系统支持 Android 16 实时活动」，把人领到一个根本不存在的开关前。
     *
     * 判据用系统自己的解析结果：Android 16 的置顶通知有专门的设置页 [Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS]，
     * 实现了这个特性的 ROM 必须有 Activity 能接它；接不住就认为本机没有该功能（不做厂商名单，避免猜错）。
     */
    fun hasPromotionSettingsEntry(context: Context): Boolean {
        if (!isPromotionSupported()) return false
        val intent = promotionSettingsIntent(context)
        return try {
            val resolved = context.packageManager.resolveActivity(intent, 0)
            resolved != null
        } catch (e: Exception) {
            // 判不准时按「有」处理：宁可多问一次（问过一次就会被记住，不再打扰），不要把功能误关掉
            Log.w(TAG, "Cannot resolve promotion settings activity", e)
            true
        }
    }

    /** 值不值得为置顶通知弹引导：系统版本够、且机器上真找得到那个开关 */
    fun canAskForPromotion(context: Context): Boolean = hasPromotionSettingsEntry(context)

    /**
     * 系统允不允许本应用发置顶通知。
     *
     * 系统版本不支持、或这台机器没有实现该特性时返回 **true**：语义是「没什么要引导用户去开的」，
     * 调用方据此跳过引导，也不会因此放弃普通通知栏的进度追踪。
     */
    fun canPromote(context: Context): Boolean {
        if (!isPromotionSupported()) return true
        return try {
            NotificationManagerCompat.from(context).canPostPromotedNotifications()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query promoted notification capability", e)
            true
        }
    }

    fun openPromotionSettings(context: Context): Boolean {
        if (isPromotionSupported()) {
            if (startSafely(context, promotionSettingsIntent(context))) return true
        }
        return openAppNotificationSettings(context)
    }

    fun openAppNotificationSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startSafely(context, intent)) return true
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startSafely(context, details)
    }

    private fun promotionSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun startSafely(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start settings intent ${intent.action}", e)
            false
        }
    }
}
