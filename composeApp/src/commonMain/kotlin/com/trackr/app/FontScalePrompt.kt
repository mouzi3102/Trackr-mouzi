package com.trackr.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import com.trackr.ui.platform.LiveUpdateFamily

/** 系统字体放大到这个倍数及以上时提示一次「试试长辈版」（重构计划 §8.2） */
const val FONT_SCALE_PROMPT_THRESHOLD: Float = 1.3f

/**
 * 「字太小看不清？」提示（设计稿 redesign_v3_elder.html「开启入口」）：系统字体 ≥ [FONT_SCALE_PROMPT_THRESHOLD] 倍、
 * 且以前没点过时弹一次。不跟着系统字体自动切换，只提示；之后在「我的」里随时开关。
 *
 * 不论点「打开长辈版」「先不用」还是点外面关掉，都记为点过（[dismissed] 由调用方存进设置），以后不再弹。
 *
 * 启动引导是串起来的：本提示没弹出来（字不够大或点过了）才轮到 [afterPrompt] 里的下一个引导，两个对话框不叠。
 */
@Composable
internal fun FontScalePrompt(
    dismissed: Boolean,
    onEnableElder: () -> Unit,
    onDismiss: () -> Unit,
    afterPrompt: @Composable () -> Unit = {}
) {
    if (dismissed || LocalDensity.current.fontScale < FONT_SCALE_PROMPT_THRESHOLD) {
        afterPrompt()
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("字太小看不清？") },
        text = { Text("试试长辈版：字更大，按钮更大，还能读给你听。之后在「我的」里随时开关。") },
        confirmButton = {
            TextButton(onClick = onEnableElder) { Text("打开长辈版") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("先不用") }
        }
    )
}

/**
 * 启动时引导一次「开启流体云 / 实时活动」：挖孔两侧的小胶囊 + 下拉展开实时到站倒计时与站点进度。
 *
 * 弹不弹由 [AppShellViewModel.refreshLiveUpdatePrompt] 判（系统支持、权限没开、以前没引导过、不是长辈版），
 * 文案按 [LiveUpdatePromptUi.family] 分小米焦点通知 / OPPO 流体云 / Android 原生实时活动三种说法。
 * 「前往开启」跳对应系统设置页；「暂不开启」和关掉都算引导过，之后不再弹 ——
 * 任何时候都还能在「我的 → 状态栏胶囊」手动去开，普通通知栏进度追踪不受影响。
 */
@Composable
internal fun LiveUpdatePrompt(
    prompt: LiveUpdatePromptUi?,
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    if (prompt == null) return
    val isXiaomi = prompt.family == LiveUpdateFamily.XIAOMI
    val permissionName = if (isXiaomi) "允许焦点通知" else "实时活动 / 置顶通知"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isXiaomi) "开启焦点通知（超级岛）" else "开启流体云 / 实时活动") },
        text = {
            Text(
                "检测到您的系统支持 ${prompt.systemName}。\n\n" +
                    "开启「$permissionName」后，即可在屏幕挖孔两侧显示实时公交小胶囊，" +
                    "并在下拉时展开实时到站倒计时与站点进度。\n\n" +
                    "未开启时仍可正常使用普通通知栏进度追踪。"
            )
        },
        confirmButton = {
            TextButton(onClick = onGoToSettings) { Text("前往开启") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("暂不开启") }
        }
    )
}
