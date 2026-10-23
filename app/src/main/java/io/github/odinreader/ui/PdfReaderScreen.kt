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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.odinreader.data.BookContent
import io.github.odinreader.data.ReadingProgress
import io.github.odinreader.input.ControllerAction
import io.github.odinreader.input.ControllerBindings
import io.github.odinreader.reader.PdfPageRenderer
import io.github.odinreader.reader.ReadingMode
import io.github.odinreader.reader.PageTurnAnimation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.min

private data class PdfRenderKey(val page: Int, val width: Int, val height: Int, val retry: Int, val mode: ReadingMode)
private data class RenderedPdfPage(
    val key: PdfRenderKey,
    val bitmap: Bitmap,
    val nextBitmap: Bitmap?,
    val geometry: ReaderPageGeometry,
)

/** Spread navigation never advances to a duplicate last right-hand page. */
internal fun pdfTurnTarget(current: Int, count: Int, mode: ReadingMode, amount: Int): Int {
    require(count > 0)
    val start = current.coerceIn(0, count - 1)
    if (amount == 1 && mode.visibleLastIndex(start, count) == count - 1) return start
    val delta = if (amount == 1 || amount == -1) amount.toLong() * mode.pageStep else amount.toLong()
    return (start.toLong() + delta).coerceIn(0L, count - 1L).toInt()
}

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
    readingMode: ReadingMode = ReadingMode.Single,
    pageAnimation: PageTurnAnimation = PageTurnAnimation.Slide,
) {
    val pageCount = content.book.chapterCount.coerceAtLeast(1)
    val file = requireNotNull(content.pdfFile)
    var pageIndex by rememberSaveable(content.book.id) {
        mutableIntStateOf(initialProgress.pageIndex.coerceIn(0, pageCount - 1))
    }
    var chrome by rememberSaveable(content.book.id) { mutableStateOf(false) }
    var showPages by rememberSaveable(content.book.id) { mutableStateOf(false) }
    var zoom by rememberSaveable(content.book.id) { mutableFloatStateOf(1f) }
    var pan by remember(content.book.id, pageIndex, readingMode) { mutableStateOf(Offset.Zero) }
    var turn by remember { mutableIntStateOf(0) }
    var turnDirection by remember { mutableIntStateOf(1) }
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

    LaunchedEffect(readingMode) { zoom = 1f; pan = Offset.Zero }

    fun selectPage(index: Int, animate: Boolean = false) {
        val next = index.coerceIn(0, pageCount - 1)
        if (next != pageIndex) {
            if (animate) {
                turnDirection = if (next > pageIndex) 1 else -1
                turn++
            }
            pageIndex = next
            zoom = 1f
            pan = Offset.Zero
        }
    }
    fun changePage(amount: Int): Boolean {
        selectPage(pdfTurnTarget(pageIndex, pageCount, readingMode, amount), animate = true)
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
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp)
                .then(if (showPages) Modifier.clearAndSetSemantics {} else Modifier),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                val gap = if (readingMode == ReadingMode.Single) 0.dp else 14.dp
                val sheetWidthPx = readingMode.pageWidthPx(widthPx, with(density) { gap.roundToPx() })
                val geometry = ReaderPageGeometry(maxWidth, maxHeight,
                    with(density) { sheetWidthPx.toDp() }, gap)
                val key = PdfRenderKey(pageIndex, sheetWidthPx, heightPx, retry, readingMode)
                val active = rendered?.takeIf { it.key == key }
                SideEffect { onReadingVisible(active != null && !showPages && !obscured) }
                LaunchedEffect(file, key) {
                    renderError = null
                    val unpublished = mutableListOf<Bitmap>()
                    try {
                        val pages = withContext(Dispatchers.IO) {
                            renderLock.withLock {
                                coroutineContext.ensureActive()
                                PdfPageRenderer(file).use { renderer ->
                                    val primary = renderer.render(key.page, key.width * 2, key.height * 2)
                                        .also { unpublished += it }
                                    coroutineContext.ensureActive()
                                    val secondary = if (readingMode != ReadingMode.Single && key.page + 1 < pageCount) {
                                        try {
                                            renderer.render(key.page + 1, key.width * 2, key.height * 2).also { unpublished += it }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (error: Exception) {
                                            // Both leaves of a spread are reading content. Do not
                                            // publish a partial spread that step-two navigation skips.
                                            if (readingMode.isSpread) throw error
                                            null
                                        }
                                    } else null
                                    primary to secondary
                                }
                            }
                        }
                        coroutineContext.ensureActive()
                        rendered = RenderedPdfPage(key, pages.first, pages.second, geometry)
                        unpublished.clear()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        renderError = "这一页暂时无法显示，请重试或重新导入 PDF。"
                    } finally {
                        // A cancelled render was never given to Compose and can be released now.
                        // Published images are left to GC: a rendering frame may still reference them.
                        unpublished.forEach(Bitmap::recycle)
                    }
                }
                LaunchedEffect(active?.key) {
                    if (active != null) onProgress(ReadingProgress(pageIndex = active.key.page,
                        completed = (active.key.page + if (active.key.mode.isSpread && active.nextBitmap != null) 1 else 0) == pageCount - 1))
                }
                val fitScale = active?.bitmap?.let { min(sheetWidthPx.toFloat() / it.width, heightPx.toFloat() / it.height) } ?: 1f
                val imageWidth = (active?.bitmap?.width ?: sheetWidthPx) * fitScale
                val imageHeight = (active?.bitmap?.height ?: heightPx) * fitScale
                fun constrainPan(offset: Offset, scale: Float): Offset {
                    val horizontal = (((if (readingMode == ReadingMode.Single) imageWidth else widthPx.toFloat()) * scale - widthPx) / 2f).coerceAtLeast(0f)
                    val vertical = (((if (readingMode == ReadingMode.Single) imageHeight else heightPx.toFloat()) * scale - heightPx) / 2f).coerceAtLeast(0f)
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
                AnimatedPagePresentation(
                    frame = active,
                    frameKey = { it.key },
                    frameGeometry = { it.geometry },
                    frameMode = { it.key.mode },
                    canAnimate = { target, previous -> target.geometry == previous.geometry && target.key.mode == previous.key.mode },
                    animation = pageAnimation,
                    turn = turn,
                    direction = turnDirection,
                    modifier = Modifier.fillMaxSize().testTag("pdf-mode-${readingMode.id}")
                        .graphicsLayer {
                            scaleX = zoom; scaleY = zoom
                            translationX = pan.x; translationY = pan.y
                        },
                    dividerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.13f),
                ) { frame, secondary, role, accessible, sheetModifier ->
                    PdfSheet(frame, secondary, role, accessible, sheetModifier)
                }
                if (active != null) {
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
                                TextButton(onClick = { setZoom(if (readingMode == ReadingMode.Single) widthPx / imageWidth else 1f); pan = Offset.Zero }, enabled = !obscured,
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
            Row(Modifier.fillMaxWidth().testTag("pdf-footer"), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f).testTag("pdf-footer-left")) {
                    Text("${bindings.labelFor(ControllerAction.PreviousPage)} 上一页 · ${bindings.labelFor(ControllerAction.NextPage)} 下一页",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${bindings.labelFor(ControllerAction.ToggleControls)} 控制栏 · ${bindings.labelFor(ControllerAction.OpenContents)} 目录",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(120.dp))
                Column(Modifier.weight(1f).testTag("pdf-footer-right"), horizontalAlignment = Alignment.End) {
                    val current = rendered?.takeIf { it.key.page == pageIndex && it.key.mode == readingMode }
                    val last = readingMode.visibleLastIndex(pageIndex, pageCount)
                    val pagesLabel = if (last > pageIndex) "第 ${pageIndex + 1}–${last + 1} / $pageCount 页" else "第 ${pageIndex + 1} / $pageCount 页"
                    Text(if (current != null) pagesLabel else "正在载入第 ${pageIndex + 1} 页…",
                        textAlign = TextAlign.End, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("pdf-counter"))
                    Text("${readingMode.label} · 双指缩放", textAlign = TextAlign.End, maxLines = 1,
                        style = MaterialTheme.typography.labelSmall, overflow = TextOverflow.Ellipsis)
                }
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
                    TextButton(onClick = { changePage(1) }, enabled = !obscured && readingMode.visibleLastIndex(pageIndex, pageCount) < pageCount - 1) {
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
private fun PdfSheet(
    frame: RenderedPdfPage,
    secondary: Boolean,
    role: String,
    accessible: Boolean,
    modifier: Modifier,
) {
    val bitmap = if (secondary) frame.nextBitmap else frame.bitmap
    val page = frame.key.page + if (secondary) 1 else 0
    Box(
        modifier.requiredSize(frame.geometry.sheetWidth, frame.geometry.height)
            .background(MaterialTheme.colorScheme.background)
            .testTag("pdf-sheet-$role")
            .then(if (accessible) Modifier else Modifier.clearAndSetSemantics {}),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            val tag = when {
                role == "preview" -> "pdf-page-preview"
                !accessible -> "pdf-page-outgoing-$role"
                secondary -> "pdf-page-secondary"
                else -> "pdf-page"
            }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = if (accessible) "PDF 第 ${page + 1} 页" else null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().testTag(tag),
            )
        } else if (frame.key.mode.hasPreview) {
            Text("没有可用的下一页", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
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
