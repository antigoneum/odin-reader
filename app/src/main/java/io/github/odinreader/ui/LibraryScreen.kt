package io.github.odinreader.ui

import androidx.compose.foundation.background
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
private fun BookCard(book: Book, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
            Modifier.fillMaxWidth().weight(1f).background(cover).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(book.format, color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall, letterSpacing = 3.sp)
            Spacer(Modifier.height(6.dp))
            Text(book.title, fontFamily = FontFamily.Serif, fontSize = 22.sp,
                color = Color(0xFFF5F0DF), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text(if (book.isDemo) "ODIN ORIGINAL" else "MY COLLECTION",
                color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, letterSpacing = 1.sp)
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            Text("${book.author} · ${book.chapterCount} ${if (book.format == "PDF") "页" else "章"}",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}