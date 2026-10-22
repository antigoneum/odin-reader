package io.github.odinreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odinreader.data.Book
import io.github.odinreader.data.ReadingRecord

/** The home shell supplies the safe drawing insets and horizontal page menu. */
@Suppress("UNUSED_PARAMETER") // Navigation callbacks remain source-compatible with the existing home API.
@Composable
internal fun LibraryScreen(
    books: List<Book>,
    busy: Boolean,
    onOpen: (Book) -> Unit,
    onImport: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    tabFocusRequester: FocusRequester? = null,
    importFocusRequester: FocusRequester? = null,
    readingRecords: Map<String, ReadingRecord> = emptyMap(),
) {
    val localImportFocus = remember { FocusRequester() }
    val importFocus = importFocusRequester ?: localImportFocus
    val entryBookFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val firstVisibleIndex by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0 }
    }
    val hasVisibleBook by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }
    }
    val buttonShape = RoundedCornerShape(14.dp)
    LaunchedEffect(busy) { if (!busy) importFocus.requestFocus() }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("把时间，留给阅读", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                Text("${books.size} 本藏书 · 从上次读到的地方继续", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = onImport,
                enabled = !busy,
                shape = buttonShape,
                modifier = Modifier.heightIn(min = 48.dp).testTag("library-import")
                    .focusRequester(importFocus)
                    .focusProperties {
                        tabFocusRequester?.let { up = it }
                        // Only request an attached card; LazyRow can detach offscreen books.
                        if (hasVisibleBook) down = entryBookFocus
                    }
                    .controllerFocusRing(buttonShape),
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("导入书籍")
            }
        }
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("library-book-grid"),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 4.dp),
        ) {
            itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
                BookCard(
                    book = book,
                    readingRecord = readingRecords[book.id],
                    enabled = !busy,
                    modifier = Modifier
                        .then(if (index == firstVisibleIndex) Modifier.focusRequester(entryBookFocus) else Modifier)
                        .focusProperties {
                            up = importFocus
                            if (index == 0) left = FocusRequester.Cancel
                            if (index == books.lastIndex) right = FocusRequester.Cancel
                        },
                ) { onOpen(book) }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            KeyHint("十字键 / 摇杆", "左右选择")
            KeyHint("A", "打开")
            Spacer(Modifier.weight(1f))
            KeyHint("L1 / R1", "切换视图")
        }
    }
}

@Composable
private fun BookCard(
    book: Book,
    readingRecord: ReadingRecord?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val tones = listOf(Color(0xFF31574A), Color(0xFF6E5949), Color(0xFF44546A), Color(0xFF75515B))
    val cover = tones[(book.id.hashCode() and Int.MAX_VALUE) % tones.size]
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        modifier = modifier.width(180.dp).fillMaxHeight().testTag("library-book-${book.id}")
            .controllerFocusRing(shape),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, focusedElevation = 6.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().weight(1f).background(cover).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(book.format, color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall, letterSpacing = 3.sp)
            Spacer(Modifier.height(6.dp))
            Text(book.title, fontFamily = FontFamily.Serif, fontSize = 22.sp,
                color = Color(0xFFF5F0DF), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("book-cover-title-${book.id}"))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${readingPercent(readingRecord)}%", color = Color(0xFFF5F0DF),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).testTag("book-progress-${book.id}"))
                Text(readingStatusLabel(readingRecord), color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.22f), CircleShape)) {
                Box(Modifier.fillMaxWidth(readingFraction(readingRecord).toFloat()).fillMaxHeight()
                    .background(Color(0xFFF5F0DF), CircleShape))
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("book-title-${book.id}"))
            Text("${book.author} · ${book.chapterCount} ${if (book.format == "PDF") "页" else "章"}",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(readingTimeLabel(readingRecord), modifier = Modifier.testTag("book-read-time-${book.id}"),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(remainingTimeLabel(readingRecord), modifier = Modifier.testTag("book-remaining-${book.id}"),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
