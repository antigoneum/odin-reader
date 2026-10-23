package io.github.odinreader.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.odinreader.data.Book
import io.github.odinreader.data.ReadingRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Full artwork and a separate progress strip; neither obscures the other's content. */
@Composable
internal fun BookCover(
    book: Book,
    readingRecord: ReadingRecord?,
    fallbackColor: Color,
    modifier: Modifier = Modifier,
) {
    val file = book.coverFile
    val artwork by produceState<ImageBitmap?>(null, file?.absolutePath, file?.lastModified()) {
        value = null
        value = withContext(Dispatchers.IO) { file?.let(::decodeCover) }
    }
    Column(modifier.background(fallbackColor)) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val image = artwork
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "《${book.title}》的封面",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().testTag("book-cover-image-${book.id}"),
                )
            } else {
                Column(
                    Modifier.fillMaxSize().testTag("book-cover-placeholder-${book.id}")
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(book.format, color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelSmall, letterSpacing = 3.sp)
                    Text(book.title, fontFamily = FontFamily.Serif, fontSize = 22.sp,
                        color = CoverText, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("book-cover-title-${book.id}"))
                }
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .background(if (artwork != null) Color.Black.copy(alpha = 0.7f) else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${readingPercent(readingRecord)}%", color = CoverText,
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).testTag("book-progress-${book.id}"))
                Text(readingStatusLabel(readingRecord), color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.22f), CircleShape)) {
                Box(Modifier.fillMaxWidth(readingFraction(readingRecord).toFloat()).fillMaxHeight()
                    .background(CoverText, CircleShape))
            }
        }
    }
}

/** A thumbnail should already be bounded, but still distrust missing/corrupt or replaced files. */
private fun decodeCover(file: File): ImageBitmap? = runCatching {
    if (!file.isFile || file.length() !in 1L..MAX_COVER_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PIXELS) return null
    var sample = 1
    while (maxOf(width, height) / sample > MAX_COVER_EDGE) sample *= 2
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
    // The ImageBitmap owns the decoded pixels. After list disposal/cancellation,
    // normal bitmap collection releases them; recycling during drawing is unsafe.
}.getOrNull()

private val CoverText = Color(0xFFF5F0DF)
private const val MAX_COVER_EDGE = 720
private const val MAX_COVER_BYTES = 8L * 1024 * 1024
private const val MAX_SOURCE_PIXELS = 100_000_000L
