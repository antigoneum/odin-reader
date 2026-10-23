package io.github.odinreader.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises real import, bounded thumbnail storage and repository reload in isolated private files. */
@RunWith(AndroidJUnit4::class)
class BookCoverRepositoryDeviceTest {
    private lateinit var context: CoverContext

    @Before
    fun isolateFiles() {
        context = CoverContext(InstrumentationRegistry.getInstrumentation().targetContext, UUID.randomUUID().toString())
        check(context.filesDir.mkdirs())
    }

    @After
    fun cleanFiles() {
        context.clearPreferences()
        context.filesDir.deleteRecursively()
    }

    @Test
    fun epubCoverIsResizedAndRetainedAcrossReloadAndDeletion() {
        val bitmap = Bitmap.createBitmap(1000, 1600, Bitmap.Config.ARGB_8888)
        val cover = try {
            bitmap.eraseColor(Color.rgb(20, 90, 170))
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
        val repository = BookRepository(context)
        val source = File(context.filesDir, "covered.epub").apply { writeBytes(epub(cover)) }
        val book = repository.importBook(Uri.fromFile(source))
        val file = requireNotNull(book.coverFile)
        assertTrue(file.isFile)
        assertTrue(file.length() in 1..CoverImages.MAX_SAVED_BYTES)
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path))
        try {
            assertEquals(720, decoded.height)
            assertEquals(450, decoded.width)
        } finally { decoded.recycle() }
        val reloaded = BookRepository(context).listBooks().first { it.id == book.id }
        assertEquals(file.canonicalFile, reloaded.coverFile?.canonicalFile)
        assertEquals("封面导入测试", reloaded.title)
        assertTrue(File(file.parentFile, "document.epub").readBytes().contentEquals(source.readBytes()))
        assertTrue(repository.readBook(book.id).chapters.single().text.contains("中文与 English 正文"))
        repository.saveProgress(book.id, ReadingProgress(0, 1, 10))
        assertEquals(10, BookRepository(context).getProgress(book.id).charOffset)
        repository.deleteBook(book.id)
        assertFalse(file.exists())
        assertNull(repository.readingHistory.getRecord(book.id))
    }

    @Test
    fun corruptOptionalImageKeepsTheBookReadableWithoutCover() {
        val source = File(context.filesDir, "broken-cover.epub").apply { writeBytes(epub("not a PNG".toByteArray())) }
        val repository = BookRepository(context)
        val book = repository.importBook(Uri.fromFile(source))
        assertNull(book.coverFile)
        assertNull(BookRepository(context).listBooks().first { it.id == book.id }.coverFile)
        assertTrue(repository.readBook(book.id).chapters.single().text.contains("中文与 English 正文"))
    }

    @Test
    fun missingEpubThumbnailIsRecoveredFromItsRetainedOriginalWithoutChangingHistory() {
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        val image = try {
            bitmap.eraseColor(Color.BLUE)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
        val source = File(context.filesDir, "recover-cover.epub").apply { writeBytes(epub(image)) }
        val repository = BookRepository(context)
        val book = repository.importBook(Uri.fromFile(source))
        repository.saveProgress(book.id, ReadingProgress(0, 0, 8))
        val before = repository.readingHistory.snapshot(book.id)
        assertTrue(requireNotNull(book.coverFile).delete())
        val reloaded = repository.listBooks().first { it.id == book.id }
        assertTrue(requireNotNull(reloaded.coverFile).isFile)
        assertEquals(before, repository.readingHistory.snapshot(book.id))
    }

    @Test
    fun pdfFirstPageGetsACoverAndExistingPdfRegeneratesItsMissingThumbnail() {
        val source = File(context.filesDir, "cover.pdf")
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            page.canvas.drawColor(Color.rgb(20, 90, 170))
            pdf.finishPage(page)
            source.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        val repository = BookRepository(context)
        val book = repository.importBook(Uri.fromFile(source))
        val cover = requireNotNull(book.coverFile)
        assertTrue(cover.isFile)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(cover.path))
        try { assertTrue(maxOf(bitmap.width, bitmap.height) <= CoverImages.MAX_EDGE) }
        finally { bitmap.recycle() }
        repository.saveProgress(book.id, ReadingProgress(pageIndex = 0))
        val before = repository.readingHistory.snapshot(book.id)
        assertTrue(cover.delete())
        assertNotNull(BookRepository(context).listBooks().first { it.id == book.id }.coverFile)
        assertTrue(cover.isFile)
        assertEquals(before, repository.readingHistory.snapshot(book.id))
    }

    @Test
    fun oversizedAndUnsupportedImageBytesDoNotCreateCacheFiles() {
        val destination = File(context.filesDir, "bad-cover.jpg")
        assertFalse(CoverImages.save(ByteArray(8 * 1024 * 1024 + 1), destination))
        assertFalse(destination.exists())
        val gif = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", 0)
        assertFalse(CoverImages.save(gif, destination))
        assertFalse(destination.exists())
    }

    private fun epub(image: ByteArray): ByteArray {
        val contents = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            "OPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>封面导入测试</dc:title><dc:creator>Odin Reader</dc:creator></metadata><manifest><item id="cover" href="art/cover.png" media-type="image/png" properties="cover-image"/><item id="body" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="body"/></spine></package>""".toByteArray(),
            "OPS/chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>第一章</h1><p>中文与 English 正文。</p></body></html>""".toByteArray(),
            "OPS/art/cover.png" to image,
        )
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                contents.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private class CoverContext(base: Context, prefix: String) : ContextWrapper(base) {
        private val directory = File(File(base.cacheDir, "cover-repository-tests"), prefix)
        private val preferencesName = "cover-tests-$prefix"
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("$preferencesName-$name", mode)
        fun clearPreferences() { baseContext.deleteSharedPreferences("$preferencesName-reading_progress") }
    }
}
