@file:OptIn(ExperimentalMaterial3Api::class)

package com.trackr.ui.screens.me

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_cancel
import com.trackr.core.designsystem.resources.data_source_chelaile
import com.trackr.core.designsystem.resources.data_source_mygolbs
import com.trackr.core.designsystem.resources.data_source_auto
import com.trackr.core.designsystem.resources.ic_chevron_right
import com.trackr.core.designsystem.resources.ic_clock
import com.trackr.core.designsystem.resources.transit_code
import com.trackr.core.designsystem.theme.previewColor
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.AppColorPalette
import com.trackr.core.model.EngineMode
import com.trackr.core.model.RailMode
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TransitCodeProvider
import com.trackr.feature.me.resources.Res as MeRes
import com.trackr.feature.me.resources.me_about_app
import com.trackr.feature.me.resources.me_about_title
import com.trackr.feature.me.resources.me_amap_title
import com.trackr.feature.me.resources.me_arrival_alert
import com.trackr.feature.me.resources.me_arrival_alert_desc
import com.trackr.feature.me.resources.me_data_source
import com.trackr.feature.me.resources.me_elder_mode
import com.trackr.feature.me.resources.me_elder_mode_desc
import com.trackr.feature.me.resources.me_group_display
import com.trackr.feature.me.resources.me_color_blue
import com.trackr.feature.me.resources.me_color_green
import com.trackr.feature.me.resources.me_color_orange
import com.trackr.feature.me.resources.me_color_palette
import com.trackr.feature.me.resources.me_color_palette_desc
import com.trackr.feature.me.resources.me_color_pink
import com.trackr.feature.me.resources.me_color_purple
import com.trackr.feature.me.resources.me_rail_mode
import com.trackr.feature.me.resources.me_rail_mode_desc
import com.trackr.feature.me.resources.me_group_map
import com.trackr.feature.me.resources.me_group_backup
import com.trackr.feature.me.resources.me_webdav_entry_desc
import com.trackr.feature.me.resources.me_webdav_title
import com.trackr.feature.me.resources.me_group_notify
import com.trackr.feature.me.resources.me_group_travel
import com.trackr.feature.me.resources.me_live_notify
import com.trackr.feature.me.resources.me_live_notify_desc
import com.trackr.feature.me.resources.me_lockscreen
import com.trackr.feature.me.resources.me_lockscreen_full
import com.trackr.feature.me.resources.me_lockscreen_hidden
import com.trackr.feature.me.resources.me_reminders
import com.trackr.feature.me.resources.me_reminders_count
import com.trackr.feature.me.resources.me_reminders_empty
import com.trackr.feature.me.resources.me_reminders_empty_hint
import com.trackr.feature.me.resources.me_reminders_none
import com.trackr.feature.me.resources.me_status_chip
import com.trackr.feature.me.resources.me_status_chip_off
import com.trackr.feature.me.resources.me_status_chip_on
import com.trackr.feature.me.resources.me_status_chip_settings
import com.trackr.feature.me.resources.me_status_chip_unsupported
import com.trackr.feature.me.resources.me_title
import com.trackr.feature.me.resources.me_version
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 「我的」页（v3）：分组「显示」（长辈版开关）「出行」「通知」「地图」「关于」。
 * 无状态，只有哪个对话框 / Sheet 开着是本地状态。
 */
@Composable
fun MeScreen(
    state: MeUiState,
    onEvent: (MeEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var showTransitCodeDialog by rememberSaveable { mutableStateOf(false) }
    var showAlertThresholdDialog by rememberSaveable { mutableStateOf(false) }
    var showRailModeDialog by rememberSaveable { mutableStateOf(false) }
    var showColorPaletteDialog by rememberSaveable { mutableStateOf(false) }
    var showRemindersSheet by rememberSaveable { mutableStateOf(false) }


    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { MeMessageHost(state.userMessages) { onEvent(MeEvent.MessageShown(it)) } },
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(MeRes.string.me_title), fontWeight = FontWeight.Bold) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 长辈版开关（SettingsRepository.uiMode）
            item(key = "group_display", contentType = "settings_group") {
                SettingsGroup(title = stringResource(MeRes.string.me_group_display)) {
                    SwitchRow(
                        title = stringResource(MeRes.string.me_elder_mode),
                        subtitle = stringResource(MeRes.string.me_elder_mode_desc),
                        checked = state.elderMode,
                        onCheckedChange = { onEvent(MeEvent.SetElderMode(it)) }
                    )
                    GroupDivider()
                    SettingRow(
                        title = stringResource(MeRes.string.me_rail_mode),
                        subtitle = stringResource(MeRes.string.me_rail_mode_desc),
                        value = railModeLabel(state.railMode),
                        onClick = { showRailModeDialog = true }
                    )
                    GroupDivider()
                    SettingRow(
                        title = stringResource(MeRes.string.me_color_palette),
                        subtitle = stringResource(MeRes.string.me_color_palette_desc),
                        value = paletteLabel(state.colorPalette),
                        onClick = { showColorPaletteDialog = true },
                        modifier = Modifier.testTag("row_color_palette")
                    )
                }
            }

            item(key = "group_trip", contentType = "settings_group") {
                SettingsGroup(title = stringResource(MeRes.string.me_group_travel)) {
                    EngineModeRow(
                        selected = state.engineMode,
                        onSelect = { onEvent(MeEvent.SetEngineMode(it)) }
                    )
                    GroupDivider()
                    SettingRow(
                        title = stringResource(Res.string.transit_code),
                        value = transitCodeLabel(state.preferredTransitCode),
                        onClick = { showTransitCodeDialog = true }
                    )
                    GroupDivider()
                    SettingRow(
                        title = stringResource(MeRes.string.me_arrival_alert),
                        subtitle = stringResource(MeRes.string.me_arrival_alert_desc),
                        value = alertThresholdLabel(state.alertThresholdStops),
                        onClick = { showAlertThresholdDialog = true }
                    )
                    GroupDivider()
                    SettingRow(
                        title = stringResource(MeRes.string.me_reminders),
                        subtitle = state.remindersSubtitle,
                        value = if (state.reminders.isEmpty()) stringResource(MeRes.string.me_reminders_none) else stringResource(MeRes.string.me_reminders_count, state.reminders.size),
                        onClick = { showRemindersSheet = true }
                    )
                }
            }

            item(key = "group_notification", contentType = "settings_group") {
                SettingsGroup(title = stringResource(MeRes.string.me_group_notify)) {
                    SwitchRow(
                        title = stringResource(MeRes.string.me_live_notify),
                        subtitle = stringResource(MeRes.string.me_live_notify_desc),
                        checked = state.isLiveNotificationEnabled,
                        onCheckedChange = { onEvent(MeEvent.SetLiveNotificationEnabled(it)) }
                    )
                    GroupDivider()
                    SwitchRow(
                        title = stringResource(MeRes.string.me_lockscreen),
                        subtitle = if (state.showOnLockScreen) {
                            stringResource(MeRes.string.me_lockscreen_full)
                        } else {
                            stringResource(MeRes.string.me_lockscreen_hidden)
                        },
                        checked = state.showOnLockScreen,
                        onCheckedChange = { onEvent(MeEvent.SetShowOnLockScreen(it)) }
                    )
                    state.liveUpdate?.let { liveUpdate ->
                        GroupDivider()
                        SettingRow(
                            title = stringResource(MeRes.string.me_status_chip),
                            subtitle = if (liveUpdate.supported) {
                                liveUpdate.systemName
                            } else {
                                stringResource(MeRes.string.me_status_chip_unsupported)
                            },
                            value = when {
                                !liveUpdate.supported -> stringResource(MeRes.string.me_status_chip_settings)
                                liveUpdate.allowed -> stringResource(MeRes.string.me_status_chip_on)
                                else -> stringResource(MeRes.string.me_status_chip_off)
                            },
                            valueColor = if (liveUpdate.supported && !liveUpdate.allowed) {
                                TrackrTheme.colors.warn
                            } else {
                                null
                            },
                            onClick = { onEvent(MeEvent.OpenLiveUpdateSettings) }
                        )
                    }
                }
            }

            item(key = "group_map", contentType = "settings_group") {
                SettingsGroup(title = stringResource(MeRes.string.me_group_map)) {
                    SettingRow(
                        title = stringResource(MeRes.string.me_amap_title),
                        subtitle = state.amapKeyStatus,
                        onClick = { onEvent(MeEvent.OpenAmapKey) },
                        modifier = Modifier.testTag("row_amap_key"),
                        subtitleModifier = Modifier.testTag("text_amap_key_status")
                    )
                }
            }

            item(key = "group_backup", contentType = "settings_group") {
                SettingsGroup(title = stringResource(MeRes.string.me_group_backup)) {
                    SettingRow(
                        title = stringResource(MeRes.string.me_webdav_title),
                        subtitle = stringResource(MeRes.string.me_webdav_entry_desc),
                        onClick = { onEvent(MeEvent.OpenWebDavBackup) },
                        modifier = Modifier.testTag("row_webdav_backup")
                    )
                }
            }

            item(key = "group_about", contentType = "settings_group") {
                SettingsGroup(title = stringResource(MeRes.string.me_about_title)) {
                    SettingRow(
                        title = stringResource(MeRes.string.me_about_app),
                        value = state.versionName?.let { stringResource(MeRes.string.me_version, it) },
                        onClick = { onEvent(MeEvent.OpenAbout) }
                    )
                }
            }
        }
    }

    if (showTransitCodeDialog) {
        ChoiceDialog(
            title = stringResource(Res.string.transit_code),
            options = TransitCodeProvider.entries,
            selected = state.preferredTransitCode,
            label = ::transitCodeLabel,
            onSelect = {
                onEvent(MeEvent.SetTransitCode(it))
                showTransitCodeDialog = false
            },
            onDismiss = { showTransitCodeDialog = false }
        )
    }

    if (showAlertThresholdDialog) {
        ChoiceDialog(
            title = stringResource(MeRes.string.me_arrival_alert),
            options = ALERT_THRESHOLD_CHOICES,
            selected = state.alertThresholdStops,
            label = ::alertThresholdLabel,
            onSelect = {
                onEvent(MeEvent.SetAlertThresholdStops(it))
                showAlertThresholdDialog = false
            },
            onDismiss = { showAlertThresholdDialog = false }
        )
    }

    if (showRailModeDialog) {
        ChoiceDialog(
            title = stringResource(MeRes.string.me_rail_mode),
            options = RailMode.entries,
            selected = state.railMode,
            label = ::railModeLabel,
            onSelect = {
                onEvent(MeEvent.SetRailMode(it))
                showRailModeDialog = false
            },
            onDismiss = { showRailModeDialog = false }
        )
    }

    if (showColorPaletteDialog) {
        ColorPaletteDialog(
            selected = state.colorPalette,
            onSelect = {
                onEvent(MeEvent.SetColorPalette(it))
                showColorPaletteDialog = false
            },
            onDismiss = { showColorPaletteDialog = false }
        )
    }

    if (showRemindersSheet) {
        RemindersSheet(
            reminders = state.reminders,
            onDismiss = { showRemindersSheet = false },
            onToggle = { id, enabled -> onEvent(MeEvent.ToggleReminder(id, enabled)) },
            onOpen = { id ->
                showRemindersSheet = false
                onEvent(MeEvent.OpenReminder(id))
            }
        )
    }
}

private fun transitCodeLabel(provider: TransitCodeProvider): String = when (provider) {
    TransitCodeProvider.ASK_ALWAYS -> "每次询问"
    TransitCodeProvider.ALIPAY -> "支付宝"
    TransitCodeProvider.UNIONPAY -> "云闪付"
}

private fun alertThresholdLabel(stops: Int): String = "提前 $stops 站"

private fun railModeLabel(mode: RailMode): String = when (mode) {
    RailMode.Horizontal -> "横排"
    RailMode.Vertical -> "竖排"
}

/** 一组设置：小号主色组标题 + 圆角卡片；行之间由调用方放 [GroupDivider] */
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            content = content
        )
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

/** 行的左边：标题 + 可选副标题 */
@Composable
private fun RowTexts(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    subtitleModifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = subtitleModifier.padding(top = 2.dp)
            )
        }
    }
}

/** 可点的行：标题、副标题，右边值 + 箭头 */
@Composable
private fun SettingRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    subtitleModifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowTexts(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
            subtitleModifier = subtitleModifier
        )
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = vectorResource(Res.drawable.ic_chevron_right),
            contentDescription = null,
            tint = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 开关行：整行可点 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowTexts(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** 「数据来源」：车来了 / 掌上公交 / 自动 三段胶囊（各配品牌色） */
@Composable
private fun EngineModeRow(
    selected: EngineMode,
    onSelect: (EngineMode) -> Unit
) {
    val options = listOf(
        EngineMode.CHELAILE to stringResource(Res.string.data_source_chelaile),
        EngineMode.MYGOLBS to stringResource(Res.string.data_source_mygolbs),
        EngineMode.AUTO to stringResource(Res.string.data_source_auto)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowTexts(title = stringResource(MeRes.string.me_data_source), subtitle = null, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (mode, label) ->
                val palette = enginePalette(mode)
                val isSelected = selected == mode
                Surface(
                    onClick = { onSelect(mode) },
                    shape = RoundedCornerShape(percent = 50),
                    color = if (isSelected) palette.container else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = if (isSelected) null else BorderStroke(1.dp, palette.container.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .testTag("chip_engine_${mode.name.lowercase()}")
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        color = if (isSelected) palette.content else palette.container,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** 数据源品牌配色：车来了=活力橙，掌上公交=公交蓝，自动=中性紫（不抢两家的品牌色） */
private data class EnginePalette(val container: Color, val content: Color)

private fun enginePalette(mode: EngineMode): EnginePalette = when (mode) {
    EngineMode.CHELAILE -> EnginePalette(container = Color(0xFFF58220), content = Color.White)
    EngineMode.MYGOLBS -> EnginePalette(container = Color(0xFF1565C0), content = Color.White)
    EngineMode.AUTO -> EnginePalette(container = Color(0xFF7B1FA2), content = Color.White)
}

/** 单选对话框：点选项即选中并关闭；只有「取消」按钮 */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) }
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(text = label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}

/** 应用配色名（和色圈一起显示） */
@Composable
private fun paletteLabel(palette: AppColorPalette): String = stringResource(
    when (palette) {
        AppColorPalette.BLUE -> MeRes.string.me_color_blue
        AppColorPalette.GREEN -> MeRes.string.me_color_green
        AppColorPalette.ORANGE -> MeRes.string.me_color_orange
        AppColorPalette.PURPLE -> MeRes.string.me_color_purple
        AppColorPalette.PINK -> MeRes.string.me_color_pink
    }
)


/** 应用配色选择：每项一个色圈 + 名字，选中的加粗并打勾 */
@Composable
private fun ColorPaletteDialog(
    selected: AppColorPalette,
    onSelect: (AppColorPalette) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MeRes.string.me_color_palette)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                AppColorPalette.entries.forEach { palette ->
                    val isSelected = palette == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelect(palette) }
                            )
                            .testTag("palette_${palette.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(palette.previewColor)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = paletteLabel(palette),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}

/** 定时提醒列表：开关在这里，改和删在编辑页 */
@Composable
private fun RemindersSheet(
    reminders: List<ScheduledReminder>,
    onDismiss: () -> Unit,
    onToggle: (id: String, enabled: Boolean) -> Unit,
    onOpen: (id: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(MeRes.string.me_reminders),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(MeRes.string.me_reminders_count, reminders.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (reminders.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_clock),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = stringResource(MeRes.string.me_reminders_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = stringResource(MeRes.string.me_reminders_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
            ) {
                items(
                    items = reminders,
                    key = { it.id },
                    contentType = { "reminder" }
                ) { reminder ->
                    ReminderItem(
                        reminder = reminder,
                        onToggle = { onToggle(reminder.id, it) },
                        onClick = { onOpen(reminder.id) }
                    )
                }
            }
        }
    }
}

/** 一条提醒：时间、重复标签、线路、上车站；关着的变淡 */
@Composable
private fun ReminderItem(
    reminder: ScheduledReminder,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("reminder_${reminder.id}")
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (reminder.enabled) 1f else 0.5f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = reminder.formatTime(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = shortRepeatText(reminder.daysOfWeek),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = "${reminder.lineName} → ${reminder.endStopName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = buildString {
                    append("${reminder.targetStopName} 上车")
                    if (reminder.autoStartLiveTracking) append(" · 到点自动追踪")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = reminder.enabled, onCheckedChange = onToggle)
    }
}
