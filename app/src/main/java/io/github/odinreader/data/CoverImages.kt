package io.github.odinreader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import io.github.odinreader.reader.PdfPageRenderer
import java.io.File
import kotlin.math.roundToInt

/** Optional cover artwork is normalized on IO, never displayed from unbounded source bytes. */
internal object CoverImages {
    const val FILE_NAME = "cover.jpg"
    const val MAX_EDGE = 720
    const val MAX_SAVED_BYTES = 2L * 1024 * 1024
    private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
    private const val MAX_SOURCE_PIXELS = 100_000_000L

    fun save(bytes: ByteArray?, destination: File): Boolean {
        if (bytes == null || bytes.size !in 1..MAX_SOURCE_BYTES) return false
        return optionalCover(destination) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/webp") ||
                bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768 ||
                bounds.outWidth.toLong() * bounds.outHeight > MAX_SOURCE_PIXELS
            ) return@optionalCover false
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return@optionalCover false
            try { writeThumbnail(bitmap, destination) } finally { bitmap.recycle() }
        }
    }

    fun savePdf(document: File, destination: File): Boolean = optionalCover(destination) {
        PdfPageRenderer(document).use { renderer ->
            val bitmap = renderer.render(0, MAX_EDGE, MAX_EDGE)
            try { writeThumbnail(bitmap, destination) } finally { bitmap.recycle() }
        }
    }

    private fun writeThumbnail(source: Bitmap, destination: File): Boolean {
        val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(source.width, source.height))
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val thumbnail = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(thumbnail)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(source, null, android.graphics.Rect(0, 0, width, height),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            val saved = destination.outputStream().buffered().use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            return saved && destination.length() in 1..MAX_SAVED_BYTES
        } finally { thumbnail.recycle() }
    }

    private inline fun optionalCover(destination: File, block: () -> Boolean): Boolean {
        val saved = try { block() } catch (_: Exception) { false }
        if (!saved) destination.delete()
        return saved
    }
}
