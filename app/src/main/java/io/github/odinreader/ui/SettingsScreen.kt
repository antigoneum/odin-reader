package io.github.odinreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusProperties
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
import io.github.odinreader.reader.ReaderFont
import io.github.odinreader.reader.ReaderFontSelection
import io.github.odinreader.reader.ReadingMode
import io.github.odinreader.reader.PageTurnAnimation
import io.github.odinreader.reader.ReaderLineSpacing
import io.github.odinreader.reader.ReaderLetterSpacing
import java.util.Locale
import kotlin.math.roundToInt

private val SettingsControlShape = RoundedCornerShape(14.dp)

/** Settings content shared by the handheld menu and the native reader overlay. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    fontSize: Float,
    readerFonts: ReaderFontSelection,
    paperStyle: PaperStyle,
    theme: AppTheme,
    bindings: ControllerBindings,
    onFontSize: (Float) -> Unit,
    onChineseFont: (ReaderFont) -> Unit,
    onEnglishFont: (ReaderFont) -> Unit,
    onPaperStyle: (PaperStyle) -> Unit,
    onTheme: (AppTheme) -> Unit,
    onBinding: (ControllerButton, ControllerAction) -> Unit,
    onResetBindings: () -> Unit,
    onClose: () -> Unit,
    onActionReady: (((ControllerAction) -> Boolean)?) -> Unit,
    activateFocused: () -> Boolean,
    title: String = "设置",
    isPdf: Boolean = false,
    embedded: Boolean = false,
    tabFocusRequester: FocusRequester? = null,
    backFocusRequester: FocusRequester? = null,
    readingMode: ReadingMode = ReadingMode.Single,
    pageAnimations: Map<ReadingMode, PageTurnAnimation> = emptyMap(),
    onReadingMode: (ReadingMode) -> Unit = {},
    onPageAnimation: (ReadingMode, PageTurnAnimation) -> Unit = { _, _ -> },
    lineSpacing: Float = ReaderLineSpacing.Default,
    onLineSpacing: (Float) -> Unit = {},
    letterSpacing: Float = ReaderLetterSpacing.Default,
    onLetterSpacing: (Float) -> Unit = {},
) {
    var editingButton by remember { mutableStateOf<ControllerButton?>(null) }
    var choosingFont by remember { mutableStateOf(false) }
    var restoreFontFocus by remember { mutableStateOf(false) }
    var choosingDisplay by remember { mutableStateOf(false) }
    var restoreDisplayFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val backFocus = backFocusRequester ?: remember { FocusRequester() }
    val fontPickerFocus = remember { FocusRequester() }
    val displayPickerFocus = remember { FocusRequester() }
    val settingsScroll = rememberLazyListState()
    val closeCurrent by rememberUpdatedState {
        when {
            editingButton != null -> editingButton = null
            choosingFont -> {
                choosingFont = false
                restoreFontFocus = true
            }
            choosingDisplay -> {
                choosingDisplay = false
                restoreDisplayFocus = true
            }
            else -> onClose()
        }
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
    LaunchedEffect(editingButton, choosingFont, choosingDisplay, backFocus) {
        if (restoreFontFocus && !choosingFont) {
            // LazyColumn attaches its items during measurement. Scroll first;
            // the entry's own effect restores focus once it is composed.
            settingsScroll.scrollToItem(1)
        } else if (restoreDisplayFocus && !choosingDisplay) {
            settingsScroll.scrollToItem(if (isPdf) 1 else 2)
        } else backFocus.requestFocus()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize()
                .then(if (embedded) Modifier else Modifier.safeDrawingPadding())
                .padding(horizontal = 24.dp, vertical = if (embedded) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    editingButton?.let { "配置 ${it.label} 按键" }
                        ?: if (choosingFont) "选择正文字体" else if (choosingDisplay) "阅读模式与翻页动画" else title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton(
                    onClick = { closeCurrent() },
                    shape = SettingsControlShape,
                    modifier = Modifier.focusRequester(backFocus).testTag("settings-back")
                        .focusProperties { tabFocusRequester?.let { up = it } }
                        .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape),
                ) {
                    Text(when {
                        editingButton != null || choosingFont || choosingDisplay -> "返回设置  B"
                        embedded -> "返回书库  B"
                        else -> "完成  B"
                    })
                }
            }
            val selectedButton = editingButton
            if (choosingDisplay) {
                ReaderDisplayPicker(
                    readingMode = readingMode,
                    pageAnimations = pageAnimations,
                    onReadingMode = onReadingMode,
                    onPageAnimation = onPageAnimation,
                    backFocus = backFocus,
                    isPdf = isPdf,
                )
            } else if (choosingFont) {
                ReaderFontPicker(
                    selection = readerFonts,
                    onChineseFont = onChineseFont,
                    onEnglishFont = onEnglishFont,
                    backFocus = backFocus,
                    fontSize = fontSize,
                    lineSpacing = lineSpacing,
                    letterSpacing = letterSpacing,
                    paperStyle = paperStyle,
                )
            } else if (selectedButton != null) {
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
                            shape = SettingsControlShape,
                            modifier = Modifier.fillMaxWidth().testTag("action-${action.name}")
                                .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape)
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
                val settingsControls: @Composable (Modifier) -> Unit = { controlsModifier ->
                    LazyColumn(
                    controlsModifier.testTag("settings-list"),
                    state = settingsScroll,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (isPdf) {
                        item(key = "pdf-layout") {
                            Text("PDF 保留原始版面；请在阅读控制栏中缩放，或双指缩放并拖动查看。主题用于界面，PDF 页面保持原色。")
                        }
                        item(key = "reading-display") {
                            DisplaySettingsEntry(readingMode, pageAnimations, displayPickerFocus, restoreDisplayFocus,
                                onFocusRestored = { restoreDisplayFocus = false }, onClick = { choosingDisplay = true })
                        }
                    } else {
                        item(key = "font") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text("正文字号", Modifier.width(96.dp))
                                OutlinedButton(
                                    onClick = { onFontSize(fontSize - 2f) },
                                    enabled = fontSize > 14f,
                                    shape = SettingsControlShape,
                                    modifier = Modifier.testTag("reader-font-size-decrease")
                                        .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape),
                                ) { Text("A−") }
                                Text("${fontSize.roundToInt()} sp", modifier = Modifier.testTag("reader-font-size-value"))
                                OutlinedButton(
                                    onClick = { onFontSize(fontSize + 2f) },
                                    enabled = fontSize < 32f,
                                    shape = SettingsControlShape,
                                    modifier = Modifier.testTag("reader-font-size-increase")
                                        .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape),
                                ) { Text("A+") }
                            }
                        }
                        item(key = "reader-font") {
                            LaunchedEffect(restoreFontFocus) {
                                if (restoreFontFocus) {
                                    fontPickerFocus.requestFocus()
                                    restoreFontFocus = false
                                }
                            }
                            OutlinedButton(
                                onClick = { choosingFont = true },
                                shape = SettingsControlShape,
                                modifier = Modifier.fillMaxWidth().testTag("reader-font-picker")
                                    .focusRequester(fontPickerFocus)
                                    .heightIn(min = 76.dp).controllerFocusRing(SettingsControlShape),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("中文字体 · ${readerFonts.chinese.label}", style = MaterialTheme.typography.titleMedium)
                                    Text("英文字体 · ${readerFonts.english.label}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text("更换")
                            }
                        }
                        item(key = "reading-display") {
                            DisplaySettingsEntry(readingMode, pageAnimations, displayPickerFocus, restoreDisplayFocus,
                                onFocusRestored = { restoreDisplayFocus = false }, onClick = { choosingDisplay = true })
                        }
                        item(key = "line-spacing") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("正文行距", style = MaterialTheme.typography.titleSmall)
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onLineSpacing(((lineSpacing * 100).roundToInt() - 10) / 100f) },
                                        enabled = lineSpacing > ReaderLineSpacing.Minimum,
                                        shape = SettingsControlShape,
                                        modifier = Modifier.testTag("reader-line-spacing-decrease")
                                            .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape)) { Text("−") }
                                    Text(String.format(Locale.ROOT, "%.2f 倍", lineSpacing),
                                        modifier = Modifier.testTag("reader-line-spacing-value"))
                                    OutlinedButton(onClick = { onLineSpacing(((lineSpacing * 100).roundToInt() + 10) / 100f) },
                                        enabled = lineSpacing < ReaderLineSpacing.Maximum,
                                        shape = SettingsControlShape,
                                        modifier = Modifier.testTag("reader-line-spacing-increase")
                                            .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape)) { Text("+") }
                                    OutlinedButton(onClick = { onLineSpacing(ReaderLineSpacing.Default) },
                                        enabled = lineSpacing != ReaderLineSpacing.Default,
                                        shape = SettingsControlShape,
                                        modifier = Modifier.testTag("reader-line-spacing-default")
                                            .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape)) { Text("默认") }
                                }
                                Text("1.20–2.40 倍；调整后保持当前段落，PDF 保留原始行距。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item(key = "letter-spacing") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("字间距", style = MaterialTheme.typography.titleSmall)
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onLetterSpacing(((letterSpacing * 100).roundToInt() - 1) / 100f) },
                                        enabled = letterSpacing > ReaderLetterSpacing.Minimum,
                                        shape = SettingsControlShape,
                                        modifier = Modifier.testTag("reader-letter-spacing-decrease")
                                            .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape)) { Text("−") }
                                    Text("${(letterSpacing * 100f).roundToInt()}%",
                                        modifier = Modifier.testTag("reader-letter-spacing-value"))
                                    OutlinedButton(onClick = { onLetterSpacing(((letterSpacing * 100).roundToInt() + 1) / 100f) },
                                        enabled = letterSpacing < ReaderLetterSpacing.Maximum,
                                        shape = SettingsControlShape,
                                        modifier = Modifier.testTag("reader-letter-spacing-increase")
                                            .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape)) { Text("+") }
                                    OutlinedButton(onClick = { onLetterSpacing(ReaderLetterSpacing.Default) },
                                        enabled = letterSpacing != ReaderLetterSpacing.Default,
                                        shape = SettingsControlShape,
                                        modifier = Modifier.testTag("reader-letter-spacing-default")
                                            .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape)) { Text("默认") }
                                }
                                Text("按字号增加 0–20% 的字间留白，中文和英文同时生效；PDF 保留原始字间距。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            shape = SettingsControlShape,
                                            modifier = Modifier.testTag("paper-${style.name}")
                                                .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape),
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
                    }
                    item(key = "theme-heading") {
                        HorizontalDivider()
                        Text("主题", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (isPdf) "Catppuccin · 四套官方配色。PDF 页面保持原色，主题应用于界面。"
                            else "Catppuccin · 四套官方配色。切换主题时，阅读背景也会设为跟随主题；之后仍可单独选择阅读背景。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(AppTheme.entries, key = { "theme-${it.name}" }) { choice ->
                        val scheme = catppuccinColorScheme(choice, systemDark)
                        FilterChip(
                            selected = choice == theme,
                            onClick = { onTheme(choice) },
                            shape = SettingsControlShape,
                            modifier = Modifier.fillMaxWidth().testTag("theme-${choice.name}")
                                .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape),
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
                            shape = SettingsControlShape,
                            modifier = Modifier.fillMaxWidth().testTag("binding-${button.name}")
                                .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape),
                        ) {
                            Text(button.label, Modifier.width(80.dp))
                            Text(bindings.actionFor(button).label, Modifier.weight(1f))
                            Text("更改")
                        }
                    }
                    item(key = "reset-bindings") {
                        OutlinedButton(
                            onClick = onResetBindings,
                            shape = SettingsControlShape,
                            modifier = Modifier.testTag("reset-bindings")
                                .heightIn(min = 48.dp).controllerFocusRing(SettingsControlShape),
                        ) { Text("恢复默认按键") }
                    }
                }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        isPdf -> settingsControls(Modifier.fillMaxSize())
                        maxWidth >= 640.dp -> Row(Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            settingsControls(Modifier.weight(1.15f).fillMaxHeight())
                            ReaderTypographyPreview(readerFonts, fontSize, lineSpacing, letterSpacing, paperStyle,
                                Modifier.weight(1f).fillMaxHeight())
                        }
                        else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ReaderTypographyPreview(readerFonts, fontSize, lineSpacing, letterSpacing, paperStyle,
                                Modifier.fillMaxWidth().height(200.dp))
                            settingsControls(Modifier.weight(1f).fillMaxWidth())
                        }
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
