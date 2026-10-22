package io.github.odinreader.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.odinreader.reader.PdfPageRenderer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.math.abs

/** Owns isolated cache storage/preferences; never reads or removes the user's books. */
@RunWith(AndroidJUnit4::class)
class PdfRepositoryDeviceTest {
    private lateinit var context: IsolatedPdfContext

    @Before
    fun createIsolatedStorage() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        context = IsolatedPdfContext(app, "pdf-test-" + UUID.randomUUID())
        check(context.filesDir.mkdirs())
    }

    @After
    fun removeOnlyIsolatedStorage() {
        if (!::context.isInitialized) return
        context.clearIsolatedPreferences()
        val directory = context.filesDir.canonicalFile
        val expectedParent = File(context.baseContext.cacheDir, "reader-pdf-tests").canonicalFile
        check(directory.parentFile == expectedParent)
        directory.deleteRecursively()
    }

    @Test
    fun originalPdfPagesAndProgressSurviveSourceRemovalAndRepositoryRecreation() {
        val source = fixture("illustrated.pdf")
        val originalBytes = source.readBytes()
        val repository = BookRepository(context)
        val book = repository.importBook(Uri.fromFile(source))
        assertEquals("PDF", book.format)
        assertEquals(2, book.chapterCount)
        val content = repository.readBook(book.id)
        assertTrue(content.chapters.isEmpty())
        val storedPdf = requireNotNull(content.pdfFile)
        assertEquals(File(context.filesDir, "books/${book.id}").canonicalFile, storedPdf.canonicalFile.parentFile)
        assertArrayEquals(originalBytes, storedPdf.readBytes())
        assertTrue(source.delete())

        val progress = ReadingProgress(pageIndex = 1)
        repository.saveProgress(book.id, progress)
        val recreated = BookRepository(context)
        assertEquals(progress, recreated.getProgress(book.id))
        assertTrue(recreated.listBooks().any { it.id == book.id && it.format == "PDF" })
        val offline = recreated.readBook(book.id)
        PdfPageRenderer(requireNotNull(offline.pdfFile)).use { renderer ->
            assertEquals(2, renderer.pageCount)
            val page = renderer.render(1, 800, 800)
            try {
                assertEquals(SCAN_COLOR, page.getPixel(page.width / 2, page.height / 2))
            } finally {
                page.recycle()
            }
        }

        recreated.deleteBook(book.id)
        assertFalse(storedPdf.exists())
        assertEquals(ReadingProgress(), recreated.getProgress(book.id))
        assertTrue(recreated.listBooks().single().isDemo)
    }

    @Test
    fun rendererPreservesOriginalGraphicsAspectRatioAndBoundsBitmapMemory() {
        val source = fixture("pages.pdf")
        PdfPageRenderer(source).use { renderer ->
            val page = renderer.render(0, 800, 800)
            try {
                assertTrue(abs(page.width.toDouble() / page.height - 2.0 / 3.0) < 0.01)
                assertEquals(Color.WHITE, page.getPixel(0, 0))
                assertEquals(Color.RED, page.getPixel(page.width / 2, page.height / 2))
            } finally {
                page.recycle()
            }
            val hugeRequest = renderer.render(1, Int.MAX_VALUE, Int.MAX_VALUE)
            try {
                assertTrue(hugeRequest.width <= 4096 && hugeRequest.height <= 4096)
                assertTrue(hugeRequest.width.toLong() * hugeRequest.height <= 4L * 1024 * 1024)
                assertEquals(SCAN_COLOR, hugeRequest.getPixel(hugeRequest.width / 2, hugeRequest.height / 2))
            } finally {
                hugeRequest.recycle()
            }
            expectIllegalArgument { renderer.render(2, 800, 800) }
            expectIllegalArgument { renderer.render(0, 0, 800) }
        }
    }

    @Test
    fun pdfHeaderIsRecognizedWithoutPdfExtension() {
        val source = fixture("document.bin")
        val book = BookRepository(context).importBook(Uri.fromFile(source))
        assertEquals("PDF", book.format)
        assertEquals(2, book.chapterCount)
    }

    @Test
    fun damagedPdfNeverPublishesPartialLibraryEntry() {
        val source = File(context.filesDir, "damaged.pdf")
        source.writeText("%PDF-1.7\nThis is not a valid PDF.")
        val repository = BookRepository(context)
        try {
            repository.importBook(Uri.fromFile(source))
            fail("A damaged PDF must be rejected")
        } catch (error: ImportException) {
            assertTrue(error.message.orEmpty().contains("PDF"))
        }
        assertTrue(repository.listBooks().single().isDemo)
        assertTrue(File(context.filesDir, "books").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun oversizedPdfIsRejectedBeforeEnteringTheLibrary() {
        val source = File(context.filesDir, "oversized.pdf")
        RandomAccessFile(source, "rw").use { it.setLength(20L * 1024 * 1024 + 1) }
        val repository = BookRepository(context)
        try {
            repository.importBook(Uri.fromFile(source))
            fail("The PDF import byte limit must be enforced")
        } catch (error: ImportException) {
            assertTrue(error.message.orEmpty().contains("20 MB"))
        }
        assertTrue(repository.listBooks().single().isDemo)
        assertTrue(File(context.filesDir, "books").listFiles().orEmpty().isEmpty())
    }

    private fun fixture(name: String): File {
        val source = File(context.filesDir, name)
        val document = PdfDocument()
        try {
            val first = document.startPage(PdfDocument.PageInfo.Builder(400, 600, 1).create())
            first.canvas.drawColor(Color.WHITE)
            first.canvas.drawText("Original PDF page", 20f, 80f, Paint().apply { color = Color.BLACK; textSize = 24f })
            first.canvas.drawRect(20f, 200f, 380f, 500f, Paint().apply { color = Color.RED })
            document.finishPage(first)

            val second = document.startPage(PdfDocument.PageInfo.Builder(400, 600, 2).create())
            val scannedImage = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                scannedImage.eraseColor(SCAN_COLOR)
                second.canvas.drawBitmap(scannedImage, null, Rect(0, 0, 400, 600), null)
            } finally {
                scannedImage.recycle()
            }
            document.finishPage(second)
            source.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return source
    }

    private inline fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Invalid page/viewport must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private class IsolatedPdfContext(base: Context, private val prefix: String) : ContextWrapper(base) {
        private val root = File(File(base.cacheDir, "reader-pdf-tests"), prefix)
        private val preferenceNames = mutableSetOf<String>()

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$prefix-$name"
            preferenceNames += isolatedName
            return baseContext.getSharedPreferences(isolatedName, mode)
        }

        fun clearIsolatedPreferences() {
            preferenceNames.forEach { name ->
                baseContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                baseContext.deleteSharedPreferences(name)
            }
        }
    }

    companion object {
        private val SCAN_COLOR = Color.rgb(20, 120, 220)
    }
}
