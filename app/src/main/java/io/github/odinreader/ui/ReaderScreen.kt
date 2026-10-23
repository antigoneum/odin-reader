package io.github.odinreader.ui

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odinreader.data.BookContent
import io.github.odinreader.data.ReadingProgress
import io.github.odinreader.input.ControllerAction
import io.github.odinreader.input.ControllerBindings
import io.github.odinreader.reader.Page
import io.github.odinreader.reader.Paginator
import io.github.odinreader.reader.ReaderFontSelection
import io.github.odinreader.reader.ReaderFonts
import io.github.odinreader.reader.ReaderTypography
import io.github.odinreader.reader.ReadingMode
import io.github.odinreader.reader.PageTurnAnimation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class ReaderPanel { Contents }
private data class LayoutKey(val chapter: Int, val width: Int, val height: Int, val textSize: Float, val fonts: ReaderFontSelection, val mode: ReadingMode, val lineSpacing: Float, val letterSpacing: Float)
private data class ReaderLayout(val key: LayoutKey, val pages: List<Page>, val index: Int, val anchorOffset: Int, val typography: ReaderTypography)

@SuppressLint("InlinedApi") // SIMPLE is an inlined integer, safe on API 28.
@Composable
fun ReaderScreen(
    content: BookContent,
    initialProgress: ReadingProgress,
    fontSize: Float,
    readerFonts: ReaderFontSelection,
    paperStyle: PaperStyle,
    bindings: ControllerBindings,
    obscured: Boolean,
    onOpenSettings: () -> Unit,
    onNavigationModeChanged: (Boolean) -> Unit,
    onBackgroundChanged: (Color?) -> Unit,
    onReadingVisible: (Boolean) -> Unit,
    onProgress: (ReadingProgress) -> Unit,
    onClose: () -> Unit,
    onActionReady: (((ControllerAction) -> Boolean)?) -> Unit,
    activateFocused: () -> Boolean,
    readingMode: ReadingMode = ReadingMode.Single,
    pageAnimation: PageTurnAnimation = PageTurnAnimation.Slide,
    lineSpacing: Float = 1.65f,
    letterSpacing: Float = 0f,
) {
    var chapter by rememberSaveable(content.book.id) {
        mutableIntStateOf(initialProgress.chapterIndex.coerceIn(content.chapters.indices))
    }
    var anchor by rememberSaveable(content.book.id) { mutableIntStateOf(initialProgress.charOffset.coerceAtLeast(0)) }
    var seekEnd by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<ReaderPanel?>(null) }
    var layout by remember { mutableStateOf<ReaderLayout?>(null) }
    var turn by remember { mutableIntStateOf(0) }
    var turnDirection by remember { mutableIntStateOf(1) }
    val focusManager = LocalFocusManager.current
    val toolbarFocus = remember { FocusRequester() }
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    BackHandler {
        when {
            panel != null -> panel = null
            chrome -> chrome = false
            else -> onClose()
        }
    }
    val background = when (paperStyle) {
        PaperStyle.Day -> Color(0xFFFAFAF7)
        PaperStyle.Paper -> Color(0xFFF2EBD9)
        PaperStyle.Night -> Color(0xFF121614)
        PaperStyle.Theme -> MaterialTheme.colorScheme.background
    }
    val foreground = when (paperStyle) {
        PaperStyle.Theme -> MaterialTheme.colorScheme.onBackground
        PaperStyle.Night -> Color(0xFFD0D5CD)
        else -> Color(0xFF303A31)
    }
    val visibleBackground = if (panel != null) MaterialTheme.colorScheme.surface else background
    SideEffect { onBackgroundChanged(visibleBackground) }
    fun changeChapter(direction: Int): Boolean {
        val next = chapter + direction
        if (next in content.chapters.indices) {
            turnDirection = direction
            turn++
            chapter = next
            anchor = 0
            seekEnd = false
            onProgress(ReadingProgress(next, 0, 0))
        }
        return true
    }
    fun changePage(direction: Int): Boolean {
        val current = layout?.takeIf {
            it.key.chapter == chapter && it.key.fonts == readerFonts && it.key.mode == readingMode &&
                it.key.lineSpacing == lineSpacing && it.key.letterSpacing == letterSpacing
        } ?: return true
        val next = if (direction < 0) readingMode.previousStart(current.index)
            else current.index + readingMode.pageStep
        when {
            next in current.pages.indices -> {
                turnDirection = direction
                turn++
                anchor = current.pages[next].startOffset
                layout = current.copy(index = next, anchorOffset = anchor)
                onProgress(ReadingProgress(chapter, next, anchor,
                    completed = chapter == content.chapters.lastIndex &&
                        readingMode.visibleLastIndex(next, current.pages.size) == current.pages.lastIndex))
            }
            direction > 0 && chapter < content.chapters.lastIndex -> {
                turnDirection = direction
                turn++
                chapter++
                anchor = 0
                seekEnd = false
                onProgress(ReadingProgress(chapter, 0, 0))
            }
            direction < 0 && chapter > 0 -> {
                turnDirection = direction
                turn++
                chapter--
                anchor = content.chapters[chapter].text.length
                seekEnd = true
                onProgress(ReadingProgress(chapter, 0, anchor))
            }
        }
        return true
    }
    val latestAction by rememberUpdatedState<(ControllerAction) -> Boolean> { action ->
        if (panel != null) {
            when (action) {
                ControllerAction.Back, ControllerAction.ToggleControls -> { panel = null; true }
                ControllerAction.OpenContents -> { panel = if (panel == ReaderPanel.Contents) null else ReaderPanel.Contents; true }
                ControllerAction.OpenSettings -> { panel = null; onOpenSettings(); true }
                else -> navigateFocus(action, { focusManager.moveFocus(it) }, activateFocused)
            }
        } else {
            when (action) {
                ControllerAction.PreviousPage -> changePage(-1)
                ControllerAction.NextPage -> changePage(1)
                ControllerAction.PreviousChapter -> changeChapter(-1)
                ControllerAction.NextChapter -> changeChapter(1)
                ControllerAction.MoveLeft, ControllerAction.MoveUp ->
                    if (chrome) navigateFocus(action, { focusManager.moveFocus(it) }, activateFocused) else changePage(-1)
                ControllerAction.MoveRight, ControllerAction.MoveDown ->
                    if (chrome) navigateFocus(action, { focusManager.moveFocus(it) }, activateFocused) else changePage(1)
                ControllerAction.Confirm -> if (chrome) activateFocused() else { chrome = true; true }
                ControllerAction.Back -> { if (chrome) chrome = false else onClose(); true }
                ControllerAction.ToggleControls -> { chrome = !chrome; true }
                ControllerAction.OpenContents -> { panel = ReaderPanel.Contents; true }
                ControllerAction.OpenSettings -> { onOpenSettings(); true }
            }
        }
    }
    DisposableEffect(content.book.id) {
        onActionReady { latestAction(it) }
        onDispose {
            onActionReady(null)
            onBackgroundChanged(null)
            onReadingVisible(false)
        }
    }
    LaunchedEffect(chrome, panel, obscured) {
        onNavigationModeChanged(chrome || panel != null)
        if (chrome && panel == null && !obscured) toolbarFocus.requestFocus()
    }

    Box(Modifier.fillMaxSize().background(background)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        ).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
            .padding(horizontal = 24.dp).padding(top = 6.dp, bottom = 2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(content.book.title, color = foreground.copy(alpha = 0.55f), style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f))
                Text(content.chapters[chapter].title, color = foreground.copy(alpha = 0.55f), style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f))
            }
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                val density = LocalDensity.current
                val viewportWidth = minOf(maxWidth, 1200.dp)
                val gutter = if (readingMode == ReadingMode.Single) 0.dp else 20.dp
                val viewportPx = with(density) { viewportWidth.roundToPx() }.coerceAtLeast(1)
                val sheetPx = readingMode.pageWidthPx(viewportPx, with(density) { gutter.roundToPx() })
                val geometry = ReaderPageGeometry(
                    viewportWidth = viewportWidth,
                    height = maxHeight,
                    sheetWidth = with(density) { sheetPx.toDp() },
                    gap = gutter,
                )
                val widthPx = with(density) { geometry.textWidth.roundToPx() }.coerceAtLeast(1)
                val heightPx = with(density) { geometry.textHeight.roundToPx() }.coerceAtLeast(1)
                val textSizePx = with(density) { fontSize.sp.toPx() }
                val key = LayoutKey(chapter, widthPx, heightPx, textSizePx, readerFonts, readingMode, lineSpacing, letterSpacing)
                val text = content.chapters[chapter].text
                LaunchedEffect(key) {
                    layout = null
                    val target = anchor
                    val end = seekEnd
                    val typography = ReaderFonts.load(context, readerFonts)
                    val pages = withContext(Dispatchers.Default) {
                        Paginator.paginate(text, widthPx, heightPx, textSizePx,
                            lineSpacingMultiplier = lineSpacing, typography = typography, letterSpacing = letterSpacing)
                    }
                    val index = if (end) readingMode.finalSpreadStart(pages.size) else Paginator.pageIndexAtOffset(pages, target)
                    anchor = if (end) pages[index].startOffset else target.coerceIn(0, text.length)
                    seekEnd = false
                    layout = ReaderLayout(key, pages, index, anchor, typography)
                }
                val active = layout?.takeIf { it.key == key }
                SideEffect { onReadingVisible(active != null && panel == null && !obscured) }
                LaunchedEffect(active) {
                    active?.let {
                        onProgress(ReadingProgress(it.key.chapter, it.index, it.anchorOffset,
                            completed = it.key.chapter == content.chapters.lastIndex &&
                                readingMode.visibleLastIndex(it.index, it.pages.size) == it.pages.lastIndex))
                    }
                }
                val flipPage by rememberUpdatedState<(Int) -> Unit> { if (!obscured) changePage(it) }
                val toggleChrome by rememberUpdatedState { if (!obscured) chrome = !chrome }
                var dragDistance by remember { mutableFloatStateOf(0f) }
                val dragThreshold = with(density) { 48.dp.toPx() }
                ReaderPagePresentation(
                    frame = active?.let {
                        ReaderPageFrame(chapter, it.index, it.pages.size, text,
                            it.pages[it.index], it.pages.getOrNull(it.index + 1),
                            it.typography, readerFonts, textSizePx, lineSpacing, letterSpacing, readingMode, geometry)
                    },
                    animation = pageAnimation,
                    turn = turn,
                    direction = turnDirection,
                    foreground = foreground,
                    background = background,
                    modifier = Modifier.size(viewportWidth, maxHeight).testTag("reader-viewport")
                        .pointerInput(Unit) {
                            detectTapGestures { position ->
                                when {
                                    position.x < size.width * 0.28f -> flipPage(-1)
                                    position.x > size.width * 0.72f -> flipPage(1)
                                    else -> toggleChrome()
                                }
                            }
                        }
                        .pointerInput(dragThreshold) {
                            detectHorizontalDragGestures(
                                onDragStart = { dragDistance = 0f },
                                onDragCancel = { dragDistance = 0f },
                                onDragEnd = {
                                    if (kotlin.math.abs(dragDistance) > dragThreshold) flipPage(if (dragDistance < 0) 1 else -1)
                                    dragDistance = 0f
                                },
                            ) { change, amount -> change.consume(); dragDistance += amount }
                        },
                )
                if (active == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            val active = layout?.takeIf {
                it.key.chapter == chapter && it.key.fonts == readerFonts && it.key.mode == readingMode &&
                    it.key.lineSpacing == lineSpacing && it.key.letterSpacing == letterSpacing
            }
            Row(Modifier.fillMaxWidth().testTag("reader-footer"), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f).testTag("reader-footer-hints")) {
                    Text(
                        "${bindings.labelFor(ControllerAction.PreviousPage)} 上一页 · ${bindings.labelFor(ControllerAction.NextPage)} 下一页",
                        color = foreground.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${bindings.labelFor(ControllerAction.ToggleControls)} 控制栏 · ${bindings.labelFor(ControllerAction.OpenContents)} 目录",
                        color = foreground.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // The system gesture handle occupies the bottom centre, not either footer block.
                Spacer(Modifier.width(132.dp))
                Text(
                    if (active == null) "正在排版…" else when (readingMode) {
                        ReadingMode.DualColumn -> "第 ${chapter + 1} / ${content.chapters.size} 章 · ${(active.index / 2) + 1} / ${(active.pages.size + 1) / 2} 双栏页"
                        else -> "第 ${chapter + 1} / ${content.chapters.size} 章 · ${active.index + 1} / ${active.pages.size} 页"
                    },
                    color = foreground.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f).testTag("reader-page-counter"),
                    textAlign = TextAlign.End, maxLines = 2,
                )
            }

        }
        if (chrome && panel == null) {
            Surface(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(), tonalElevation = 6.dp, shadowElevation = 3.dp) {
                Row(
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                    ).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose, enabled = !obscured, modifier = Modifier.focusRequester(toolbarFocus)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回书库") }
                    Text(content.book.title, Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = { panel = ReaderPanel.Contents }, enabled = !obscured) { Icon(Icons.Default.Menu, "章节目录") }
                    IconButton(onClick = onOpenSettings, enabled = !obscured) { Icon(Icons.Default.Settings, "阅读设置") }
                    IconButton(onClick = { chrome = false }, enabled = !obscured) { Icon(Icons.Default.Close, "隐藏控制栏") }
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(12.dp),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = { changePage(-1) }, enabled = !obscured) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text("上一页") }
                    TextButton(onClick = { changePage(1) }, enabled = !obscured) { Text("下一页"); Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
                }
            }
        }
        if (panel != null) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                when (panel) {
                    ReaderPanel.Contents -> ContentsPanel(content, chapter, enabled = !obscured, onClose = { panel = null }) {
                        chapter = it; anchor = 0; seekEnd = false
                        onProgress(ReadingProgress(chapter, 0, 0))
                        panel = null
                    }
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String, enabled: Boolean, onClose: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(enabled) { if (enabled) requester.requestFocus() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onClose, enabled = enabled, modifier = Modifier.focusRequester(requester)) { Text("返回阅读  B") }
    }
}

@Composable
private fun ContentsPanel(content: BookContent, selected: Int, enabled: Boolean, onClose: () -> Unit, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp, vertical = 16.dp)) {
        PanelHeader("章节目录", enabled, onClose)
        val scroll = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = selected)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = scroll, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(content.chapters) { index, chapter ->
                OutlinedButton(onClick = { onSelect(index) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("${if (index == selected) "●  " else ""}${index + 1}. ${chapter.title}", Modifier.fillMaxWidth())
                }
            }
        }
    }
}
