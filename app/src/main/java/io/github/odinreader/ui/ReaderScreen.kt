package io.github.odinreader.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.odinreader.data.BookContent
import io.github.odinreader.data.ReadingProgress
import io.github.odinreader.input.ControllerAction
import io.github.odinreader.reader.Page
import io.github.odinreader.reader.Paginator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class ReaderPanel { Contents, Settings }
private data class LayoutKey(val chapter: Int, val width: Int, val height: Int, val textSize: Float)
private data class ReaderLayout(val key: LayoutKey, val pages: List<Page>, val index: Int, val anchorOffset: Int)

@SuppressLint("InlinedApi") // SIMPLE is an inlined integer, safe on API 28.
@Composable
fun ReaderScreen(
    content: BookContent,
    initialProgress: ReadingProgress,
    fontSize: Float,
    paperStyle: PaperStyle,
    onFontSize: (Float) -> Unit,
    onPaperStyle: (PaperStyle) -> Unit,
    onProgress: (ReadingProgress) -> Unit,
    onClose: () -> Unit,
    onActionReady: (((ControllerAction) -> Boolean)?) -> Unit,
    activateFocused: () -> Boolean,
) {
    var chapter by rememberSaveable(content.book.id) {
        mutableIntStateOf(initialProgress.chapterIndex.coerceIn(content.chapters.indices))
    }
    var anchor by rememberSaveable(content.book.id) { mutableIntStateOf(initialProgress.charOffset.coerceAtLeast(0)) }
    var seekEnd by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<ReaderPanel?>(null) }
    var layout by remember { mutableStateOf<ReaderLayout?>(null) }
    val focusManager = LocalFocusManager.current
    val toolbarFocus = remember { FocusRequester() }
    val view = LocalView.current
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
    }
    val foreground = if (paperStyle == PaperStyle.Night) Color(0xFFD0D5CD) else Color(0xFF303A31)
    fun changeChapter(direction: Int): Boolean {
        val next = chapter + direction
        if (next in content.chapters.indices) {
            chapter = next
            anchor = 0
            seekEnd = false
            onProgress(ReadingProgress(next, 0, 0))
        }
        return true
    }
    fun changePage(direction: Int): Boolean {
        val current = layout?.takeIf { it.key.chapter == chapter } ?: return true
        val next = current.index + direction
        when {
            next in current.pages.indices -> {
                anchor = current.pages[next].startOffset
                layout = current.copy(index = next, anchorOffset = anchor)
                onProgress(ReadingProgress(chapter, next, anchor))
            }
            direction > 0 && chapter < content.chapters.lastIndex -> {
                chapter++
                anchor = 0
                seekEnd = false
                onProgress(ReadingProgress(chapter, 0, 0))
            }
            direction < 0 && chapter > 0 -> {
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
                ControllerAction.OpenSettings -> { panel = if (panel == ReaderPanel.Settings) null else ReaderPanel.Settings; true }
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
                ControllerAction.OpenSettings -> { panel = ReaderPanel.Settings; true }
            }
        }
    }
    DisposableEffect(content.book.id) {
        onActionReady { latestAction(it) }
        onDispose { onActionReady(null) }
    }
    LaunchedEffect(chrome, panel) { if (chrome && panel == null) toolbarFocus.requestFocus() }

    Box(Modifier.fillMaxSize().background(background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(content.book.title, color = foreground.copy(alpha = 0.55f), style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f))
                Text(content.chapters[chapter].title, color = foreground.copy(alpha = 0.55f), style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f))
            }
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                val density = LocalDensity.current
                val pageWidth = minOf(maxWidth, 840.dp)
                val widthPx = with(density) { pageWidth.roundToPx() }.coerceAtLeast(1)
                val heightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
                val textSizePx = with(density) { fontSize.sp.toPx() }
                val key = LayoutKey(chapter, widthPx, heightPx, textSizePx)
                val text = content.chapters[chapter].text
                LaunchedEffect(key) {
                    layout = null
                    val target = anchor
                    val end = seekEnd
                    val pages = withContext(Dispatchers.Default) {
                        Paginator.paginate(text, widthPx, heightPx, textSizePx)
                    }
                    val index = if (end) pages.lastIndex else Paginator.pageIndexAtOffset(pages, target)
                    anchor = if (end) pages[index].startOffset else target.coerceIn(0, text.length)
                    seekEnd = false
                    layout = ReaderLayout(key, pages, index, anchor)
                }
                val active = layout?.takeIf { it.key == key }
                LaunchedEffect(active) {
                    active?.let {
                        onProgress(ReadingProgress(it.key.chapter, it.index, it.anchorOffset))
                    }
                }
                val flipPage by rememberUpdatedState<(Int) -> Unit> { changePage(it) }
                val toggleChrome by rememberUpdatedState { chrome = !chrome }
                var dragDistance by remember { mutableFloatStateOf(0f) }
                val dragThreshold = with(density) { 48.dp.toPx() }
                AndroidView(
                    factory = { context ->
                        TextView(context).apply {
                            typeface = Typeface.SERIF
                            includeFontPadding = false
                            setFallbackLineSpacing(true)
                            isElegantTextHeight = true
                            if (android.os.Build.VERSION.SDK_INT >= 35) setUseBoundsForWidth(true)
                            setPadding(0, 0, 0, 0)
                            setLineSpacing(0f, 1.65f)
                            breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
                            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                            gravity = Gravity.TOP or Gravity.START
                            textDirection = android.view.View.TEXT_DIRECTION_FIRST_STRONG_LTR
                            isFocusable = false
                            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        }
                    },
                    modifier = Modifier.width(pageWidth).fillMaxHeight()
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
                    update = { textView ->
                        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                        textView.setTextColor(foreground.toArgb())
                        val pageText = active?.let { it.pages[it.index].text } ?: ""
                        if (textView.text.toString() != pageText) textView.text = pageText
                    },
                )
                if (active == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            val active = layout?.takeIf { it.key.chapter == chapter }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("L1 上一页 · R1 下一页", color = foreground.copy(alpha = 0.45f), style = MaterialTheme.typography.labelSmall)
                Text(
                    if (active == null) "正在排版…" else "第 ${chapter + 1} / ${content.chapters.size} 章 · ${active.index + 1} / ${active.pages.size} 页",
                    color = foreground.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall,
                )
                Text("X 控制栏 · Y 目录", color = foreground.copy(alpha = 0.45f), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (chrome && panel == null) {
            Surface(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(), tonalElevation = 6.dp, shadowElevation = 3.dp) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, modifier = Modifier.focusRequester(toolbarFocus)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回书库") }
                    Text(content.book.title, Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = { panel = ReaderPanel.Contents }) { Icon(Icons.Default.Menu, "章节目录") }
                    IconButton(onClick = { panel = ReaderPanel.Settings }) { Icon(Icons.Default.Settings, "阅读设置") }
                    IconButton(onClick = { chrome = false }) { Icon(Icons.Default.Close, "隐藏控制栏") }
                }
            }
            Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = { changePage(-1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text("上一页") }
                    TextButton(onClick = { changePage(1) }) { Text("下一页"); Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
                }
            }
        }
        if (panel != null) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                when (panel) {
                    ReaderPanel.Contents -> ContentsPanel(content, chapter, onClose = { panel = null }) {
                        chapter = it; anchor = 0; seekEnd = false
                        onProgress(ReadingProgress(chapter, 0, 0))
                        panel = null
                    }
                    ReaderPanel.Settings -> SettingsPanel(fontSize, paperStyle, onFontSize, onPaperStyle) { panel = null }
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String, onClose: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onClose, modifier = Modifier.focusRequester(requester)) { Text("返回阅读  B") }
    }
}

@Composable
private fun ContentsPanel(content: BookContent, selected: Int, onClose: () -> Unit, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp)) {
        PanelHeader("章节目录", onClose)
        val scroll = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = selected)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = scroll, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(content.chapters) { index, chapter ->
                OutlinedButton(onClick = { onSelect(index) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${if (index == selected) "●  " else ""}${index + 1}. ${chapter.title}", Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(fontSize: Float, paperStyle: PaperStyle, onFontSize: (Float) -> Unit, onPaperStyle: (PaperStyle) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelHeader("阅读设置", onClose)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("正文字号", Modifier.width(100.dp))
            OutlinedButton(onClick = { onFontSize(fontSize - 2) }, enabled = fontSize > 14f) { Text("A−") }
            Text("${fontSize.roundToInt()} sp")
            OutlinedButton(onClick = { onFontSize(fontSize + 2) }, enabled = fontSize < 32f) { Text("A+") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("阅读背景", Modifier.width(100.dp))
            PaperStyle.entries.forEach { style ->
                FilterChip(selected = style == paperStyle, onClick = { onPaperStyle(style) }, label = { Text(style.label) })
            }
        }
        HorizontalDivider()
        Text("L1 / R1 翻页  ·  L2 / R2 切换章节  ·  X 显示控制栏  ·  Y 目录", style = MaterialTheme.typography.bodyMedium)
        Text("字号和背景自动保存。调整字号后保持当前段落；阅读时保持屏幕常亮。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
