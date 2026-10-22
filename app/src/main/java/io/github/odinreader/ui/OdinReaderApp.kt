package io.github.odinreader.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.odinreader.MainActivity
import io.github.odinreader.R
import io.github.odinreader.data.Book
import io.github.odinreader.input.ControllerAction
import io.github.odinreader.input.ControllerBindings

@Composable
fun OdinReaderApp(activity: MainActivity, model: ReaderViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val currentContent = state.content
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var readerNavigationMode by remember { mutableStateOf(true) }
    var readerBackground by remember { mutableStateOf<Color?>(null) }
    var settingsAction by remember { mutableStateOf<((ControllerAction) -> Boolean)?>(null) }
    var readerAction by remember { mutableStateOf<((ControllerAction) -> Boolean)?>(null) }
    val focusManager = LocalFocusManager.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importBook)
    }
    val importBook = { importer.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) }
    val latestAction by rememberUpdatedState<(ControllerAction) -> Boolean> { action ->
        if (state.busy) {
            true
        } else if (showSettings) {
            settingsAction?.invoke(action) ?: true
        } else if (showHelp) {
            when (action) {
                ControllerAction.Back, ControllerAction.OpenSettings, ControllerAction.ToggleControls -> {
                    showHelp = false
                    true
                }
                else -> navigateFocus(action, { focusManager.moveFocus(it) }, activity::activateFocusedControl)
            }
        } else if (state.content != null) {
            readerAction?.invoke(action) ?: false
        } else {
            when (action) {
                ControllerAction.OpenSettings -> { showSettings = true; true }
                ControllerAction.ToggleControls -> { showHelp = true; true }
                ControllerAction.Back -> { activity.onBackPressedDispatcher.onBackPressed(); true }
                else -> navigateFocus(action, { focusManager.moveFocus(it) }, activity::activateFocusedControl)
            }
        }
    }
    DisposableEffect(activity) {
        activity.onControllerAction = { latestAction(it) }
        onDispose { activity.onControllerAction = { false } }
    }
    SideEffect {
        activity.configureController(
            state.controllerBindings,
            useDefaultBindings = state.content == null || showSettings || showHelp || readerNavigationMode,
        )
    }
    BackHandler(enabled = showHelp && !showSettings) { showHelp = false }
    OdinReaderTheme(theme = state.appTheme) {
        val readerVisible = currentContent != null && !showSettings && !showHelp && !state.busy
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
                        showHelp -> HelpScreen(state.controllerBindings) { showHelp = false }
                        state.content != null -> ReaderScreen(
                            content = currentContent!!,
                            initialProgress = state.progress,
                            fontSize = state.fontSize,
                            paperStyle = state.paperStyle,
                            bindings = state.controllerBindings,
                            obscured = showSettings,
                            onOpenSettings = { showSettings = true },
                            onNavigationModeChanged = { readerNavigationMode = it },
                            onBackgroundChanged = { readerBackground = it },
                            onProgress = { model.saveProgress(currentContent!!.book.id, it) },
                            onClose = model::closeBook,
                            onActionReady = { readerAction = it },
                            activateFocused = activity::activateFocusedControl,
                        )
                        else -> LibraryScreen(
                            books = state.books,
                            busy = state.busy || showSettings,
                            onOpen = model::openBook,
                            onImport = importBook,
                            onHelp = { showHelp = true },
                            onSettings = { showSettings = true },
                        )
                    }
                }
                if (showSettings) {
                    Surface(
                        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures {} },
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        SettingsScreen(
                            fontSize = state.fontSize,
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
private fun LibraryScreen(
    books: List<Book>,
    busy: Boolean,
    onOpen: (Book) -> Unit,
    onImport: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
) {
    val importFocus = remember { FocusRequester() }
    LaunchedEffect(busy) { if (!busy) importFocus.requestFocus() }
    val sidebarWidth = 174.dp + WindowInsets.safeDrawing.asPaddingValues()
        .calculateStartPadding(LocalLayoutDirection.current)
    Row(Modifier.fillMaxSize()) {
        Surface(Modifier.width(sidebarWidth).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
            Column(
                Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
                ).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_launcher), null, Modifier.size(36.dp), tint = Color.Unspecified)
                    Column {
                        Text("ODIN", fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Text("READER", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Menu, null, Modifier.size(20.dp))
                        Text("我的书库", style = MaterialTheme.typography.labelLarge)
                    }
                }
                OutlinedButton(onClick = onSettings, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("设置")
                }
                OutlinedButton(onClick = onHelp, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Menu, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("操作指南")
                }
                Spacer(Modifier.weight(1f))
                Text("一本书，一段旅程。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("ODIN 3 · 离线阅读", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
                .padding(start = 24.dp, end = 24.dp, top = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("把时间，留给阅读", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                    Text("${books.size} 本藏书 · 从上次读到的地方继续", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onImport, enabled = !busy, modifier = Modifier.focusRequester(importFocus)) {
                    Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导入书籍")
                }
            }
            Spacer(Modifier.height(18.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(154.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(books, key = { it.id }) { book -> BookCard(book, !busy) { onOpen(book) } }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                KeyHint("十字键 / 摇杆", "选择")
                KeyHint("A", "打开")
                KeyHint("START", "设置")
            }
        }
    }
}

@Composable
private fun BookCard(book: Book, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val tones = listOf(Color(0xFF31574A), Color(0xFF6E5949), Color(0xFF44546A), Color(0xFF75515B))
    val cover = tones[(book.id.hashCode() and Int.MAX_VALUE) % tones.size]
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, focusedElevation = 6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(142.dp).background(cover).padding(16.dp)) {
            Column {
                Text(book.format, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall, letterSpacing = 3.sp)
                Spacer(Modifier.height(12.dp))
                Text(book.title, fontFamily = FontFamily.Serif, fontSize = 23.sp, color = Color(0xFFF5F0DF), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(if (book.isDemo) "ODIN ORIGINAL" else "MY COLLECTION", Modifier.align(Alignment.BottomStart), color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, letterSpacing = 1.sp)
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text("${book.author} · ${book.chapterCount} 章", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
private fun HelpScreen(bindings: ControllerBindings, onClose: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.focusRequester(firstFocus)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回书库") }
            Text("掌机阅读指南", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("双手握住，轻松翻页", style = MaterialTheme.typography.titleLarge)
                KeyHint("摇杆 / 十字键", "书库选择 · 阅读时翻页")
                KeyHint(bindings.labelFor(ControllerAction.PreviousPage), "上一页")
                KeyHint(bindings.labelFor(ControllerAction.NextPage), "下一页")
                KeyHint(bindings.labelFor(ControllerAction.PreviousChapter), "上一章")
                KeyHint(bindings.labelFor(ControllerAction.NextChapter), "下一章")
                KeyHint("A / B", "菜单确认 / 返回")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("保持专注", style = MaterialTheme.typography.titleLarge)
                KeyHint(bindings.labelFor(ControllerAction.ToggleControls), "显示或隐藏阅读控制栏")
                KeyHint(bindings.labelFor(ControllerAction.OpenContents), "章节目录")
                KeyHint(bindings.labelFor(ControllerAction.OpenSettings), "阅读设置")
                Text("支持 TXT 与无 DRM 的 EPUB。导入后离线阅读，自动保存位置。也可以点击页面两侧或左右滑动翻页，点击中间打开控制栏。", style = MaterialTheme.typography.bodyMedium)
                Text("EPUB 当前以纯文本排版；暂不支持插图、原书 CSS 与 PDF。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
