package io.github.odinreader.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.odinreader.data.BookContent
import io.github.odinreader.data.ReadingProgress
import io.github.odinreader.input.ControllerAction
import io.github.odinreader.input.ControllerBindings
import io.github.odinreader.reader.PdfPageRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.min

private data class PdfRenderKey(val page: Int, val width: Int, val height: Int, val retry: Int)
private data class RenderedPdfPage(val key: PdfRenderKey, val bitmap: Bitmap)

/** Original PDF pages, including scans and illustrations, rendered entirely offline. */
@Composable
fun PdfReaderScreen(
    content: BookContent,
    initialProgress: ReadingProgress,
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
) {
    val pageCount = content.book.chapterCount.coerceAtLeast(1)
    val file = requireNotNull(content.pdfFile)
    var pageIndex by rememberSaveable(content.book.id) {
        mutableIntStateOf(initialProgress.pageIndex.coerceIn(0, pageCount - 1))
    }
    var chrome by rememberSaveable(content.book.id) { mutableStateOf(false) }
    var showPages by rememberSaveable(content.book.id) { mutableStateOf(false) }
    var zoom by rememberSaveable(content.book.id) { mutableFloatStateOf(1f) }
    var pan by remember(content.book.id, pageIndex) { mutableStateOf(Offset.Zero) }
    var retry by remember { mutableIntStateOf(0) }
    var rendered by remember(content.book.id) { mutableStateOf<RenderedPdfPage?>(null) }
    var renderError by remember(content.book.id) { mutableStateOf<String?>(null) }
    val renderLock = remember(content.book.id) { Mutex() }
    val toolbarFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val background = MaterialTheme.colorScheme.background
    val view = LocalView.current

    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    SideEffect { onBackgroundChanged(background) }

    fun selectPage(index: Int) {
        val next = index.coerceIn(0, pageCount - 1)
        if (next != pageIndex) {
            pageIndex = next
            zoom = 1f
            pan = Offset.Zero
        }
    }
    fun changePage(amount: Int): Boolean {
        selectPage(pageIndex + amount)
        return true
    }
    fun closeLayer() {
        when {
            showPages -> showPages = false
            chrome -> chrome = false
            else -> onClose()
        }
    }
    BackHandler(enabled = !obscured) { closeLayer() }
    val latestAction by rememberUpdatedState<(ControllerAction) -> Boolean> { action ->
        when {
            obscured -> true
            showPages -> when (action) {
                ControllerAction.Back, ControllerAction.ToggleControls, ControllerAction.OpenContents -> {
                    showPages = false
                    true
                }
                ControllerAction.OpenSettings -> { showPages = false; onOpenSettings(); true }
                else -> { navigateFocus(action, { focusManager.moveFocus(it) }, activateFocused); true }
            }
            else -> when (action) {
                ControllerAction.PreviousPage -> changePage(-1)
                ControllerAction.NextPage -> changePage(1)
                ControllerAction.PreviousChapter -> changePage(-10)
                ControllerAction.NextChapter -> changePage(10)
                ControllerAction.MoveLeft, ControllerAction.MoveUp ->
                    if (chrome) navigateFocus(action, { focusManager.moveFocus(it) }, activateFocused) else changePage(-1)
                ControllerAction.MoveRight, ControllerAction.MoveDown ->
                    if (chrome) navigateFocus(action, { focusManager.moveFocus(it) }, activateFocused) else changePage(1)
                ControllerAction.Confirm -> if (chrome) activateFocused() else { chrome = true; true }
                ControllerAction.Back -> { closeLayer(); true }
                ControllerAction.ToggleControls -> { chrome = !chrome; true }
                ControllerAction.OpenContents -> { showPages = true; true }
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
    LaunchedEffect(chrome, showPages, obscured) {
        onNavigationModeChanged(chrome || showPages)
        if (chrome && !showPages && !obscured) toolbarFocus.requestFocus()
    }

    Box(Modifier.fillMaxSize().background(background).testTag("pdf-reader")) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 10.dp)
                .then(if (showPages) Modifier.clearAndSetSemantics {} else Modifier),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(content.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground)
            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f).clipToBounds().testTag("pdf-viewport"),
                contentAlignment = Alignment.Center,
            ) {
                val density = LocalDensity.current
                val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
                val heightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
                val key = PdfRenderKey(pageIndex, widthPx, heightPx, retry)
                val active = rendered?.takeIf { it.key == key }
                SideEffect { onReadingVisible(active != null && !showPages && !obscured) }
                LaunchedEffect(file, key) {
                    rendered = null
                    renderError = null
                    var unpublished: Bitmap? = null
                    try {
                        val bitmap = withContext(Dispatchers.IO) {
                            renderLock.withLock {
                                coroutineContext.ensureActive()
                                PdfPageRenderer(file).use { renderer ->
                                    renderer.render(key.page, key.width * 2, key.height * 2).also { unpublished = it }
                                }
                            }
                        }
                        coroutineContext.ensureActive()
                        rendered = RenderedPdfPage(key, bitmap)
                        unpublished = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        renderError = "这一页暂时无法显示，请重试或重新导入 PDF。"
                    } finally {
                        // A cancelled render was never given to Compose and can be released now.
                        // Published images are left to GC: a rendering frame may still reference them.
                        unpublished?.recycle()
                    }
                }
                LaunchedEffect(active?.key) {
                    if (active != null) onProgress(ReadingProgress(pageIndex = active.key.page,
                        completed = active.key.page == pageCount - 1))
                }
                val fitScale = active?.bitmap?.let { min(widthPx.toFloat() / it.width, heightPx.toFloat() / it.height) } ?: 1f
                val imageWidth = (active?.bitmap?.width ?: widthPx) * fitScale
                val imageHeight = (active?.bitmap?.height ?: heightPx) * fitScale
                fun constrainPan(offset: Offset, scale: Float): Offset {
                    val horizontal = ((imageWidth * scale - widthPx) / 2f).coerceAtLeast(0f)
                    val vertical = ((imageHeight * scale - heightPx) / 2f).coerceAtLeast(0f)
                    return Offset(offset.x.coerceIn(-horizontal, horizontal), offset.y.coerceIn(-vertical, vertical))
                }
                fun setZoom(value: Float) {
                    zoom = value.coerceIn(1f, 4f)
                    pan = constrainPan(pan, zoom)
                }
                val latestGesture by rememberUpdatedState<(Offset, Offset, Boolean) -> Unit> { down, drag, moved ->
                    when {
                        obscured || showPages || active == null -> Unit
                        zoom > 1.01f -> if (!moved) chrome = !chrome
                        abs(drag.x) > with(density) { 48.dp.toPx() } && abs(drag.x) > abs(drag.y) * 1.2f ->
                            changePage(if (drag.x < 0) 1 else -1)
                        !moved -> when {
                            down.x < widthPx * 0.25f -> changePage(-1)
                            down.x > widthPx * 0.75f -> changePage(1)
                            else -> chrome = !chrome
                        }
                    }
                }
                if (active != null) {
                    Image(
                        bitmap = active.bitmap.asImageBitmap(),
                        contentDescription = "PDF 第 ${pageIndex + 1} 页",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().testTag("pdf-page")
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                            },
                    )
                    // One gesture owner distinguishes unzoomed page swipes from zoomed panning.
                    Box(Modifier.fillMaxSize().pointerInput(obscured, showPages, key) {
                        if (obscured || showPages) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = down.position
                            var drag = Offset.Zero
                            var moved = false
                            var transformed = false
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                val delta = event.calculatePan()
                                drag += delta
                                if (drag.getDistance() > viewConfiguration.touchSlop) moved = true
                                if (pressed > 1 || transformed || zoom > 1.01f) {
                                    if (pressed > 1) transformed = true
                                    val nextZoom = (zoom * event.calculateZoom()).coerceIn(1f, 4f)
                                    pan = constrainPan(pan + delta, nextZoom)
                                    zoom = nextZoom
                                    if (moved || transformed) event.changes.forEach { it.consume() }
                                } else if (moved) {
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (!transformed) latestGesture(start, drag, moved)
                        }
                    })
                    if (chrome && !showPages) {
                        Surface(Modifier.align(Alignment.CenterEnd).padding(8.dp), shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                TextButton(onClick = { setZoom(zoom + 0.5f) }, enabled = !obscured && zoom < 4f,
                                    modifier = Modifier.testTag("pdf-zoom-in").semantics { contentDescription = "放大" }) { Text("放大") }
                                Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.testTag("pdf-zoom"))
                                TextButton(onClick = { setZoom(zoom - 0.5f) }, enabled = !obscured && zoom > 1f,
                                    modifier = Modifier.semantics { contentDescription = "缩小" }) { Text("缩小") }
                                TextButton(onClick = { setZoom(1f); pan = Offset.Zero }, enabled = !obscured,
                                    modifier = Modifier.semantics { contentDescription = "适合页面" }) { Text("适合页面") }
                                TextButton(onClick = { setZoom(widthPx / imageWidth); pan = Offset.Zero }, enabled = !obscured,
                                    modifier = Modifier.semantics { contentDescription = "适合宽度" }) { Text("适合宽度") }
                            }
                        }
                    }
                } else if (renderError != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(renderError!!, color = MaterialTheme.colorScheme.onBackground)
                        Button(onClick = { retry++ }, enabled = !obscured, modifier = Modifier.testTag("pdf-retry")) { Text("重试") }
                    }
                } else {
                    CircularProgressIndicator(Modifier.testTag("pdf-loading"))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${bindings.labelFor(ControllerAction.PreviousPage)} 上一页 · ${bindings.labelFor(ControllerAction.NextPage)} 下一页",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (rendered?.key?.page == pageIndex) "第 ${pageIndex + 1} / $pageCount 页" else "正在载入第 ${pageIndex + 1} 页…",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("pdf-counter"))
                Text("双指缩放 · 放大后拖动", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (chrome && !showPages) {
            Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), tonalElevation = 6.dp) {
                Row(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                    .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, enabled = !obscured, modifier = Modifier.focusRequester(toolbarFocus)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回书库")
                    }
                    Text(content.book.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { showPages = true }, enabled = !obscured) { Icon(Icons.Default.Menu, "PDF 页码") }
                    IconButton(onClick = onOpenSettings, enabled = !obscured) { Icon(Icons.Default.Settings, "阅读设置") }
                    IconButton(onClick = { chrome = false }, enabled = !obscured) { Icon(Icons.Default.Close, "隐藏控制栏") }
                }
            }
            Surface(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(8.dp),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = { changePage(-1) }, enabled = !obscured && pageIndex > 0) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text("上一页")
                    }
                    TextButton(onClick = { changePage(1) }, enabled = !obscured && pageIndex < pageCount - 1) {
                        Text("下一页"); Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    }
                }
            }
        }
        if (showPages) {
            PdfPagesPanel(pageCount, pageIndex, enabled = !obscured, onClose = { showPages = false }) {
                selectPage(it)
                showPages = false
            }
        }
    }
}

@Composable
private fun PdfPagesPanel(count: Int, selected: Int, enabled: Boolean, onClose: () -> Unit, onSelect: (Int) -> Unit) {
    val requester = remember { FocusRequester() }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    LaunchedEffect(enabled) { if (enabled) requester.requestFocus() }
    Surface(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures {} }, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("PDF 页码", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onClose, enabled = enabled, modifier = Modifier.focusRequester(requester)) { Text("返回阅读  B") }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("pdf-pages"), state = scroll,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(count, key = { it }) { index ->
                    OutlinedButton(onClick = { onSelect(index) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("pdf-page-option-$index")) {
                        Text("${if (index == selected) "●  " else ""}第 ${index + 1} 页", Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
