package io.github.odinreader.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.odinreader.MainActivity
import io.github.odinreader.input.ControllerAction
import io.github.odinreader.input.ControllerBindings

@Composable
fun OdinReaderApp(activity: MainActivity, model: ReaderViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val currentContent = state.content
    var homeTab by rememberSaveable { mutableStateOf(HomeTab.Library) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var readerNavigationMode by remember { mutableStateOf(true) }
    var readerBackground by remember { mutableStateOf<Color?>(null) }
    var visibleReaderBook by remember { mutableStateOf<String?>(null) }
    var settingsAction by remember { mutableStateOf<((ControllerAction) -> Boolean)?>(null) }
    var readerAction by remember { mutableStateOf<((ControllerAction) -> Boolean)?>(null) }
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    val tabFocus = remember { HomeTab.entries.map { FocusRequester() } }
    val contentFocus = remember { HomeTab.entries.map { FocusRequester() } }
    ReadingActivityEffect(
        bookId = currentContent?.book?.id,
        bodyVisible = currentContent != null && visibleReaderBook == currentContent.book.id && !showSettings && !state.busy,
        onActiveChanged = model::setReadingActive,
        onTick = model::tickReading,
    )
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importBook)
    }
    val importBook = { importer.launch(arrayOf("text/plain", "application/epub+zip", "application/pdf", "application/octet-stream")) }
    val latestAction by rememberUpdatedState<(ControllerAction) -> Boolean> { action ->
        if (state.busy) {
            true
        } else if (showSettings) {
            settingsAction?.invoke(action) ?: true
        } else if (state.content != null) {
            readerAction?.invoke(action) ?: false
        } else {
            when (action) {
                ControllerAction.PreviousPage -> { homeTab = homeTab.shifted(-1); true }
                ControllerAction.NextPage -> { homeTab = homeTab.shifted(1); true }
                ControllerAction.OpenSettings -> { homeTab = HomeTab.Settings; true }
                ControllerAction.ToggleControls -> {
                    homeTab = if (homeTab == HomeTab.Help) HomeTab.Library else HomeTab.Help
                    true
                }
                ControllerAction.Back -> {
                    when (homeTab) {
                        HomeTab.Library -> activity.onBackPressedDispatcher.onBackPressed()
                        HomeTab.Settings -> settingsAction?.invoke(action) ?: run { homeTab = HomeTab.Library }
                        HomeTab.Help -> homeTab = HomeTab.Library
                    }
                    true
                }
                else -> if (homeTab == HomeTab.Settings) settingsAction?.invoke(action) ?: true
                    else navigateFocus(action, { focusManager.moveFocus(it) }, activity::activateFocusedControl)
            }
        }
    }
    DisposableEffect(activity, inputModeManager) {
        activity.onControllerAction = {
            // We consume gamepad events before Compose sees them. Explicitly leave
            // touch mode so Material buttons become keyboard-focusable for axes too.
            inputModeManager.requestInputMode(InputMode.Keyboard)
            latestAction(it)
        }
        onDispose { activity.onControllerAction = { false } }
    }
    val controllerBindings = state.controllerBindings
    val useDefaultBindings = state.content == null || showSettings || readerNavigationMode
    SideEffect {
        activity.configureController(
            controllerBindings,
            useDefaultBindings = useDefaultBindings,
        )
    }
    BackHandler(enabled = state.content == null && homeTab != HomeTab.Library) { homeTab = HomeTab.Library }
    OdinReaderTheme(theme = state.appTheme) {
        val readerVisible = currentContent != null && !showSettings && !state.busy
        val navigationBarBackground = if (readerVisible) {
            readerBackground ?: MaterialTheme.colorScheme.background
        } else MaterialTheme.colorScheme.background
        val statusBarBackground = if (readerVisible && readerNavigationMode) {
            MaterialTheme.colorScheme.surface
        } else navigationBarBackground
        val lightStatusBar = statusBarBackground.luminance() > 0.5f
        val lightNavigationBar = navigationBarBackground.luminance() > 0.5f
        SideEffect {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = lightStatusBar
                isAppearanceLightNavigationBars = lightNavigationBar
            }
        }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.message) {
            state.message?.let {
                snackbar.showSnackbar(it)
                model.dismissMessage()
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().then(if (showSettings) Modifier.clearAndSetSemantics {} else Modifier),
                ) {
                    when {
                        currentContent?.pdfFile != null -> PdfReaderScreen(
                            content = currentContent,
                            initialProgress = state.progress,
                            bindings = state.controllerBindings,
                            obscured = showSettings,
                            onOpenSettings = { showSettings = true },
                            onNavigationModeChanged = { readerNavigationMode = it },
                            onBackgroundChanged = { readerBackground = it },
                            onReadingVisible = { visibleReaderBook = if (it) currentContent.book.id else null },
                            onProgress = { model.saveProgress(currentContent.book.id, it) },
                            onClose = model::closeBook,
                            onActionReady = { readerAction = it },
                            activateFocused = activity::activateFocusedControl,
                            readingMode = state.readingMode,
                            pageAnimation = state.pageAnimations[state.readingMode] ?: state.readingMode.defaultAnimation,
                        )
                        state.content != null -> ReaderScreen(
                            content = currentContent!!,
                            initialProgress = state.progress,
                            fontSize = state.fontSize,
                            lineSpacing = state.lineSpacing,
                            letterSpacing = state.letterSpacing,
                            readerFonts = state.readerFonts,
                            paperStyle = state.paperStyle,
                            bindings = state.controllerBindings,
                            obscured = showSettings,
                            onOpenSettings = { showSettings = true },
                            onNavigationModeChanged = { readerNavigationMode = it },
                            onBackgroundChanged = { readerBackground = it },
                            onReadingVisible = { visibleReaderBook = if (it) currentContent!!.book.id else null },
                            onProgress = { model.saveProgress(currentContent!!.book.id, it) },
                            onClose = model::closeBook,
                            onActionReady = { readerAction = it },
                            activateFocused = activity::activateFocusedControl,
                            readingMode = state.readingMode,
                            pageAnimation = state.pageAnimations[state.readingMode] ?: state.readingMode.defaultAnimation,
                        )
                        else -> Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            HomeMenu(
                                selected = homeTab,
                                enabled = !state.busy,
                                requesters = tabFocus,
                                contentFocus = contentFocus[homeTab.ordinal],
                                onSelect = { homeTab = it },
                            )
                            Box(Modifier.fillMaxWidth().weight(1f).testTag("home-page-${homeTab.name}")) {
                                when (homeTab) {
                                    HomeTab.Library -> LibraryScreen(
                                        books = state.books,
                                        readingRecords = state.readingRecords,
                                        busy = state.busy,
                                        onOpen = model::openBook,
                                        onImport = importBook,
                                        onHelp = { homeTab = HomeTab.Help },
                                        onSettings = { homeTab = HomeTab.Settings },
                                        tabFocusRequester = tabFocus[HomeTab.Library.ordinal],
                                        importFocusRequester = contentFocus[HomeTab.Library.ordinal],
                                    )
                                    HomeTab.Settings -> SettingsScreen(
                                        fontSize = state.fontSize,
                                        lineSpacing = state.lineSpacing,
                                        onLineSpacing = model::setLineSpacing,
                                        letterSpacing = state.letterSpacing,
                                        onLetterSpacing = model::setLetterSpacing,
                                        readerFonts = state.readerFonts,
                                        onChineseFont = model::setChineseFont,
                                        onEnglishFont = model::setEnglishFont,
                                        paperStyle = state.paperStyle,
                                        theme = state.appTheme,
                                        bindings = state.controllerBindings,
                                        onFontSize = model::setFontSize,
                                        onPaperStyle = model::setPaperStyle,
                                        onTheme = model::setAppTheme,
                                        onBinding = model::setControllerBinding,
                                        onResetBindings = model::resetControllerBindings,
                                        onClose = { homeTab = HomeTab.Library },
                                        onActionReady = { settingsAction = it },
                                        activateFocused = activity::activateFocusedControl,
                                        embedded = true,
                                        tabFocusRequester = tabFocus[HomeTab.Settings.ordinal],
                                        backFocusRequester = contentFocus[HomeTab.Settings.ordinal],
                                        readingMode = state.readingMode,
                                        pageAnimations = state.pageAnimations,
                                        onReadingMode = model::setReadingMode,
                                        onPageAnimation = model::setPageAnimation,
                                    )
                                    HomeTab.Help -> HelpScreen(
                                        bindings = state.controllerBindings,
                                        tabFocusRequester = tabFocus[HomeTab.Help.ordinal],
                                        backFocusRequester = contentFocus[HomeTab.Help.ordinal],
                                        onClose = { homeTab = HomeTab.Library },
                                    )
                                }
                            }
                        }
                    }
                }
                if (showSettings) {
                    Surface(
                        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures {} },
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        SettingsScreen(
                            fontSize = state.fontSize,
                            lineSpacing = state.lineSpacing,
                            onLineSpacing = model::setLineSpacing,
                            letterSpacing = state.letterSpacing,
                            onLetterSpacing = model::setLetterSpacing,
                            readerFonts = state.readerFonts,
                            onChineseFont = model::setChineseFont,
                            onEnglishFont = model::setEnglishFont,
                            paperStyle = state.paperStyle,
                            theme = state.appTheme,
                            bindings = state.controllerBindings,
                            onFontSize = model::setFontSize,
                            onPaperStyle = model::setPaperStyle,
                            onTheme = model::setAppTheme,
                            onBinding = model::setControllerBinding,
                            onResetBindings = model::resetControllerBindings,
                            onClose = { showSettings = false },
                            onActionReady = { settingsAction = it },
                            activateFocused = activity::activateFocusedControl,
                            title = if (state.content != null) "阅读设置" else "设置",
                            isPdf = state.content?.pdfFile != null,
                            readingMode = state.readingMode,
                            pageAnimations = state.pageAnimations,
                            onReadingMode = model::setReadingMode,
                            onPageAnimation = model::setPageAnimation,
                        )
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp))
                if (state.busy) {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), modifier = Modifier.fillMaxSize()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("正在准备书籍…")
                        }
                    }
                }
            }
        }
    }
}

internal fun navigateFocus(
    action: ControllerAction,
    move: (FocusDirection) -> Boolean,
    confirm: () -> Boolean,
): Boolean = when (action) {
    ControllerAction.MoveUp -> move(FocusDirection.Up)
    ControllerAction.MoveDown -> move(FocusDirection.Down)
    ControllerAction.MoveLeft -> move(FocusDirection.Left)
    ControllerAction.MoveRight -> move(FocusDirection.Right)
    ControllerAction.Confirm -> confirm()
    else -> false
}

@Composable
internal fun KeyHint(key: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(key, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
        }
        Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HelpScreen(
    bindings: ControllerBindings,
    tabFocusRequester: FocusRequester,
    backFocusRequester: FocusRequester,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    LaunchedEffect(backFocusRequester) { backFocusRequester.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("掌机阅读指南", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(
                onClick = onClose,
                shape = shape,
                modifier = Modifier.heightIn(min = 48.dp).focusRequester(backFocusRequester)
                    .focusProperties { up = tabFocusRequester }.controllerFocusRing(shape),
            ) { Text("返回书库  B") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("双手握住，轻松翻页", style = MaterialTheme.typography.titleLarge)
                KeyHint("L1 / R1", "主页切换书库、设置和指南")
                KeyHint("摇杆 / 十字键", "书库选择 · 阅读时翻页")
                KeyHint(bindings.labelFor(ControllerAction.PreviousPage), "上一页")
                KeyHint(bindings.labelFor(ControllerAction.NextPage), "下一页")
                KeyHint(bindings.labelFor(ControllerAction.PreviousChapter), "上一章")
                KeyHint(bindings.labelFor(ControllerAction.NextChapter), "下一章")
                KeyHint("A / B", "菜单确认 / 返回")
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("保持专注", style = MaterialTheme.typography.titleLarge)
                KeyHint(bindings.labelFor(ControllerAction.ToggleControls), "显示或隐藏阅读控制栏")
                KeyHint(bindings.labelFor(ControllerAction.OpenContents), "章节目录")
                KeyHint(bindings.labelFor(ControllerAction.OpenSettings), "阅读设置")
                Text("支持 TXT、无 DRM 的 EPUB 与未加密 PDF。导入后离线阅读，自动保存位置。点击页面两侧或左右滑动翻页，点击中间打开控制栏。", style = MaterialTheme.typography.bodyMedium)
                Text("EPUB 使用纯文本排版。PDF 保留图文和扫描页，可双指缩放、拖动查看；目录键打开原页列表，章节键前后跳 10 页。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
