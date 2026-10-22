package io.github.odinreader.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Native, offline PDF rendering. Construct, render and close on a worker thread. */
class PdfPageRenderer(file: File) : Closeable {
    private val renderer: PdfRenderer = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).let { descriptor ->
        try {
            // PdfRenderer owns the descriptor after successful construction.
            PdfRenderer(descriptor)
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
    }
    private var closed = false

    val pageCount: Int
        @Synchronized get() {
            check(!closed) { "PDF 已关闭" }
            return renderer.pageCount
        }

    /** Fits the original page into the requested viewport with a bounded bitmap allocation. */
    @Synchronized
    fun render(pageIndex: Int, width: Int, height: Int): Bitmap {
        check(!closed) { "PDF 已关闭" }
        require(pageIndex in 0 until renderer.pageCount) { "PDF 页码超出范围" }
        require(width > 0 && height > 0) { "PDF 显示区域无效" }
        renderer.openPage(pageIndex).use { page ->
            require(page.width > 0 && page.height > 0) { "PDF 页面尺寸无效" }
            val pageWidth = page.width.toDouble()
            val pageHeight = page.height.toDouble()
            val fit = min(width / pageWidth, height / pageHeight)
            val longestEdge = MAX_EDGE / maxOf(pageWidth, pageHeight)
            val pixels = sqrt(MAX_PIXELS / (pageWidth * pageHeight))
            val scale = min(fit, min(longestEdge, pixels))
            val bitmapWidth = floor(pageWidth * scale).toInt().coerceAtLeast(1)
            val bitmapHeight = floor(pageHeight * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            try {
                // Unpainted PDF areas are paper, including scanned/image-only documents.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            renderer.close()
            closed = true
        }
    }

    companion object {
        private const val MAX_EDGE = 4096.0
        private const val MAX_PIXELS = 4.0 * 1024 * 1024
    }
}
