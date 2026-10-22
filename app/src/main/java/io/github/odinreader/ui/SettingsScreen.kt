package io.github.odinreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.odinreader.input.ControllerAction
import io.github.odinreader.input.ControllerBindings
import io.github.odinreader.input.ControllerButton
import kotlin.math.roundToInt

/** Shared settings overlay for the library and the native reader. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    fontSize: Float,
    paperStyle: PaperStyle,
    theme: AppTheme,
    bindings: ControllerBindings,
    onFontSize: (Float) -> Unit,
    onPaperStyle: (PaperStyle) -> Unit,
    onTheme: (AppTheme) -> Unit,
    onBinding: (ControllerButton, ControllerAction) -> Unit,
    onResetBindings: () -> Unit,
    onClose: () -> Unit,
    onActionReady: (((ControllerAction) -> Boolean)?) -> Unit,
    activateFocused: () -> Boolean,
    title: String = "设置",
) {
    var editingButton by remember { mutableStateOf<ControllerButton?>(null) }
    val focusManager = LocalFocusManager.current
    val backFocus = remember { FocusRequester() }
    val settingsScroll = rememberLazyListState()
    val closeCurrent by rememberUpdatedState {
        if (editingButton != null) editingButton = null else onClose()
    }
    val latestAction by rememberUpdatedState<(ControllerAction) -> Boolean> { action ->
        when (action) {
            ControllerAction.Back, ControllerAction.OpenSettings, ControllerAction.ToggleControls -> {
                closeCurrent()
                true
            }
            else -> {
                // Keep unassigned reading actions inside the settings overlay.
                navigateFocus(action, { focusManager.moveFocus(it) }, activateFocused)
                true
            }
        }
    }
    DisposableEffect(Unit) {
        onActionReady { latestAction(it) }
        onDispose { onActionReady(null) }
    }
    BackHandler { closeCurrent() }
    LaunchedEffect(editingButton) { backFocus.requestFocus() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    editingButton?.let { "配置 ${it.label} 按键" } ?: title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton(
                    onClick = { closeCurrent() },
                    modifier = Modifier.focusRequester(backFocus).testTag("settings-back"),
                ) { Text(if (editingButton != null) "返回设置  B" else "完成  B") }
            }
            val selectedButton = editingButton
            if (selectedButton != null) {
                Text(
                    "为 ${selectedButton.label} 选择阅读功能。菜单中的确认、返回与方向导航保持默认。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ControllerBindings.assignableActions, key = { it.name }) { action ->
                        val chosen = bindings.actionFor(selectedButton) == action
                        OutlinedButton(
                            onClick = {
                                onBinding(selectedButton, action)
                                editingButton = null
                            },
                            modifier = Modifier.fillMaxWidth().testTag("action-${action.name}")
                                .semantics { selected = chosen; role = Role.RadioButton },
                        ) {
                            RadioButton(selected = chosen, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(action.label, Modifier.weight(1f))
                        }
                    }
                }
            } else {
                val systemDark = isSystemInDarkTheme()
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag("settings-list"),
                    state = settingsScroll,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "font") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("正文字号", Modifier.width(96.dp))
                            OutlinedButton(onClick = { onFontSize(fontSize - 2f) }, enabled = fontSize > 14f) {
                                Text("A−")
                            }
                            Text("${fontSize.roundToInt()} sp")
                            OutlinedButton(onClick = { onFontSize(fontSize + 2f) }, enabled = fontSize < 32f) {
                                Text("A+")
                            }
                        }
                    }
                    item(key = "paper") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("阅读背景", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PaperStyle.entries.forEach { style ->
                                    FilterChip(
                                        selected = style == paperStyle,
                                        onClick = { onPaperStyle(style) },
                                        label = { Text(style.label) },
                                        modifier = Modifier.testTag("paper-${style.name}"),
                                    )
                                }
                            }
                            Text(
                                "选择“跟随主题”，正文与界面一起使用主题配色。字号和背景自动保存，调整字号后保持当前段落。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = "theme-heading") {
                        HorizontalDivider()
                        Text("主题", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Catppuccin · 四套官方配色。切换主题时，阅读背景也会设为跟随主题；之后仍可单独选择阅读背景。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(AppTheme.entries, key = { "theme-${it.name}" }) { choice ->
                        val scheme = catppuccinColorScheme(choice, systemDark)
                        FilterChip(
                            selected = choice == theme,
                            onClick = { onTheme(choice) },
                            modifier = Modifier.fillMaxWidth().testTag("theme-${choice.name}"),
                            label = { Text(choice.label) },
                            leadingIcon = {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    PaletteDot(scheme.background, scheme.onBackground)
                                    PaletteDot(scheme.primary, scheme.primary)
                                    PaletteDot(scheme.secondary, scheme.secondary)
                                }
                            },
                        )
                    }
                    item(key = "controller-heading") {
                        HorizontalDivider()
                        Text("按键功能", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
                        Text(
                            "以下映射用于隐藏控制栏时的阅读操作。菜单仍使用 A 确认、B 返回；十字键与摇杆保持方向导航。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(ControllerButton.entries, key = { "binding-${it.name}" }) { button ->
                        OutlinedButton(
                            onClick = { editingButton = button },
                            modifier = Modifier.fillMaxWidth().testTag("binding-${button.name}"),
                        ) {
                            Text(button.label, Modifier.width(80.dp))
                            Text(bindings.actionFor(button).label, Modifier.weight(1f))
                            Text("更改")
                        }
                    }
                    item(key = "reset-bindings") {
                        OutlinedButton(
                            onClick = onResetBindings,
                            modifier = Modifier.testTag("reset-bindings"),
                        ) { Text("恢复默认按键") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteDot(color: Color, outline: Color) {
    Box(Modifier.size(14.dp).background(color, CircleShape).border(1.dp, outline, CircleShape))
}
