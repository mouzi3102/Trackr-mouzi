package com.trackr.ui.screens.me

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.trackr.core.data.backup.RemoteBackupFile
import com.trackr.core.designsystem.theme.TrackrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「从备份恢复」得是页面上的常驻入口 —— 原来只有「先刷新列表、再点某一行（整行热区）」这一条隐藏路径，
 * 用户进页面只看得到「测试连接 / 刷新列表 / 立即备份」，就以为没有恢复功能（本次反馈）。
 *
 * 页面是无状态的：这里只喂 [WebDavBackupUiState] 并记录事件，不接仓库和传输层（合并恢复的规则在 `:core:data` 侧测）。
 */
@RunWith(RobolectricTestRunner::class)
class WebDavBackupRestoreEntryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fileA = RemoteBackupFile("trackr_backup_20261104-153012.json", "Wed, 04 Nov 2026 15:30:12 GMT")
    private val fileB = RemoteBackupFile("trackr_backup_20261103-080000.json", null)

    /** LazyColumn 只组合视口内的条目，滚动区外的按钮要先滚进可视区（和 BusHomeScreenTest 一样用 performScrollToNode） */
    private fun reveal(tag: String) {
        composeRule.onNodeWithTag("webdav_list").performScrollToNode(hasTestTag(tag))
        composeRule.waitForIdle()
    }

    private fun setContent(state: WebDavBackupUiState, events: MutableList<WebDavBackupEvent>) {
        composeRule.setContent {
            TrackrTheme {
                WebDavBackupScreen(state = state, onEvent = { events += it })
            }
        }
        composeRule.waitForIdle()
        reveal(RESTORE_ENTRY)
    }

    @Test
    fun restoreEntry_isVisibleWithEmptyList_andRefreshesFirst() {
        val events = mutableListOf<WebDavBackupEvent>()
        setContent(WebDavBackupUiState(), events)

        // 远端列表还没拉出来时，恢复入口也必须在页面上（和「立即备份」并排，不是藏在某一行里）
        composeRule.onNodeWithTag(RESTORE_ENTRY).assertExists()
        composeRule.onNodeWithTag("btn_webdav_backup_now").assertExists()

        composeRule.onNodeWithTag(RESTORE_ENTRY).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf<WebDavBackupEvent>(WebDavBackupEvent.RefreshList), events)
    }

    @Test
    fun restoreEntry_withLoadedList_opensPicker_andPicksThatFile() {
        val events = mutableListOf<WebDavBackupEvent>()
        setContent(WebDavBackupUiState(files = listOf(fileA, fileB), filesLoaded = true), events)

        composeRule.onNodeWithTag(RESTORE_ENTRY).performClick()
        composeRule.waitForIdle()

        // 弹窗里每一份都能选；点哪一份就是恢复哪一份
        composeRule.onNode(hasText("选择要恢复的备份")).assertExists()
        composeRule.onNodeWithTag(pickTag(fileA)).assertExists()
        composeRule.onNodeWithTag(pickTag(fileB)).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf<WebDavBackupEvent>(WebDavBackupEvent.OpenRestore(fileB)), events)
        composeRule.onNodeWithTag(pickTag(fileA)).assertDoesNotExist()
    }

    @Test
    fun pickerOpensByItself_afterRefreshStartedFromTheEntry() {
        val events = mutableListOf<WebDavBackupEvent>()
        composeRule.setContent {
            TrackrTheme {
                // 模拟 ViewModel：点入口 → RefreshList → 列表回来了（busy 结束）→ 自动弹出选择框，不用点第二次
                var state by remember { mutableStateOf(WebDavBackupUiState()) }
                WebDavBackupScreen(state = state, onEvent = { event ->
                    events += event
                    if (event == WebDavBackupEvent.RefreshList) {
                        state = state.copy(files = listOf(fileA), filesLoaded = true, busy = false)
                    }
                })
            }
        }
        composeRule.waitForIdle()
        reveal(RESTORE_ENTRY)

        composeRule.onNodeWithTag(RESTORE_ENTRY).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(pickTag(fileA)).assertExists()
    }

    @Test
    fun perRowRestoreButton_stillNextToDelete() {
        val events = mutableListOf<WebDavBackupEvent>()
        setContent(WebDavBackupUiState(files = listOf(fileA), filesLoaded = true), events)

        reveal(restoreTag(fileA))
        composeRule.onNodeWithTag(restoreTag(fileA)).assertExists()
        composeRule.onNodeWithTag("btn_delete_${fileA.name}").assertExists()

        composeRule.onNodeWithTag(restoreTag(fileA)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf<WebDavBackupEvent>(WebDavBackupEvent.OpenRestore(fileA)), events)
    }

    private companion object {
        const val RESTORE_ENTRY = "btn_webdav_restore"
        fun restoreTag(file: RemoteBackupFile) = "btn_restore_${file.name}"
        fun pickTag(file: RemoteBackupFile) = "restore_pick_${file.name}"
    }
}
