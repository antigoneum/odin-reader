package io.github.odinreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.odinreader.reader.PdfPageRenderer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Local, offline library. Call file operations from Dispatchers.IO.
 * Imports are copied into app storage, so document-provider grants need not survive a reboot.
 */
class BookRepository(context: Context) {
    private val appContext = context.applicationContext
    private val libraryDirectory = File(appContext.filesDir, "books")
    private val progressPreferences = appContext.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)

    fun listBooks(): List<Book> {
        val imported = libraryDirectory.listFiles().orEmpty()
            .filter { it.isDirectory && isBookId(it.name) }
            .mapNotNull { directory ->
                runCatching { bookFromJson(JSONObject(File(directory, "metadata.json").readText())) }.getOrNull()
            }
            .sortedByDescending { it.addedAt }
        return imported + DemoBook.content.book
    }

    @Throws(ImportException::class)
    fun readBook(id: String): BookContent {
        if (id == DemoBook.ID) return DemoBook.content
        try {
            val directory = bookDirectory(id)
            val book = bookFromJson(JSONObject(File(directory, "metadata.json").readText()))
            if (book.format == "PDF") {
                val document = File(directory, "document.pdf")
                if (!document.isFile || document.canonicalFile.parentFile != directory.canonicalFile ||
                    document.length() !in 1L..MAX_IMPORT_BYTES.toLong()
                ) throw ImportException("PDF 文件缺失或无效，请重新导入")
                val pages = validatePdf(document)
                return BookContent(book.copy(chapterCount = pages), emptyList(), pdfFile = document)
            }
            val array = JSONArray(File(directory, "chapters.json").readText())
            val chapters = List(array.length()) { index ->
                val chapter = array.getJSONObject(index)
                BookChapter(chapter.getString("title"), chapter.getString("text"))
            }
            if (chapters.isEmpty()) throw ImportException("这本书没有可阅读的正文")
            return BookContent(book, chapters)
        } catch (error: ImportException) {
            throw error
        } catch (error: Exception) {
            throw ImportException("无法打开这本书，请重新导入", error)
        }
    }

    @Throws(ImportException::class)
    fun importBook(uri: Uri): Book {
        var pendingDirectory: File? = null
        try {
            val name = displayName(uri)
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { readBounded(it, MAX_IMPORT_BYTES) }
                ?: throw ImportException("无法读取所选文件")
            val mimeType = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
            val hasPdfHeader = bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals(PDF_HEADER)
            if (name.endsWith(".pdf", ignoreCase = true) || mimeType == "application/pdf" || hasPdfHeader) {
                val id = UUID.randomUUID().toString()
                libraryDirectory.mkdirs()
                val staging = File(libraryDirectory, ".$id.importing")
                pendingDirectory = staging
                check(staging.mkdirs()) { "无法创建书籍目录" }
                val document = File(staging, "document.pdf")
                document.writeBytes(bytes)
                val pages = validatePdf(document)
                val book = Book(
                    id = id,
                    title = name.substringBeforeLast('.').ifBlank { "未命名 PDF" }.take(512),
                    author = "作者未知",
                    format = "PDF",
                    addedAt = System.currentTimeMillis(),
                    chapterCount = pages,
                )
                File(staging, "metadata.json").writeText(bookToJson(book).toString())
                check(staging.renameTo(bookDirectory(id))) { "无法保存导入文件" }
                pendingDirectory = null
                return book
            }
            val isZip = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
            val parsed = when {
                name.endsWith(".epub", ignoreCase = true) || isZip -> EpubParser.parse(bytes, name.substringBeforeLast('.'))
                name.endsWith(".txt", ignoreCase = true) ||
                    mimeType?.startsWith("text/plain") == true -> {
                    val text = BookTextParser.decode(bytes)
                    if (text.count { it == '\u0000' } > text.length / 100) {
                        throw ImportException("这不是可识别的文本文件")
                    }
                    ParsedBook(name.substringBeforeLast('.').ifBlank { "未命名书籍" }, "作者未知", "TXT", BookTextParser.chapters(text))
                }
                else -> throw ImportException("目前支持 TXT、无 DRM 的 EPUB 与未加密 PDF 文件")
            }
            if (parsed.chapters.isEmpty()) throw ImportException("文件中没有可阅读的正文")
            if (parsed.chapters.size > MAX_CHAPTERS) throw ImportException("章节过多，目前最多支持 $MAX_CHAPTERS 章")
            val id = UUID.randomUUID().toString()
            val book = Book(
                id = id,
                title = parsed.title.take(512),
                author = parsed.author.take(512),
                format = parsed.format,
                addedAt = System.currentTimeMillis(),
                chapterCount = parsed.chapters.size,
            )
            libraryDirectory.mkdirs()
            val staging = File(libraryDirectory, ".$id.importing")
            pendingDirectory = staging
            check(staging.mkdirs()) { "无法创建书籍目录" }
            File(staging, "metadata.json").writeText(bookToJson(book).toString())
            val chapters = JSONArray()
            parsed.chapters.forEach { chapter ->
                chapters.put(JSONObject().put("title", chapter.title).put("text", chapter.text))
            }
            File(staging, "chapters.json").writeText(chapters.toString())
            check(staging.renameTo(bookDirectory(id))) { "无法保存导入文件" }
            pendingDirectory = null
            return book
        } catch (error: ImportException) {
            throw error
        } catch (error: Exception) {
            throw ImportException("导入失败：${error.message ?: "文件无法读取"}", error)
        } finally {
            pendingDirectory?.deleteRecursively()
        }
    }

    fun getProgress(id: String): ReadingProgress = ReadingProgress(
        chapterIndex = progressPreferences.getInt("$id.chapter", 0).coerceAtLeast(0),
        pageIndex = progressPreferences.getInt("$id.page", 0).coerceAtLeast(0),
        charOffset = progressPreferences.getInt("$id.offset", 0).coerceAtLeast(0),
    )

    fun saveProgress(id: String, progress: ReadingProgress) {
        progressPreferences.edit()
            .putInt("$id.chapter", progress.chapterIndex.coerceAtLeast(0))
            .putInt("$id.page", progress.pageIndex.coerceAtLeast(0))
            .putInt("$id.offset", progress.charOffset.coerceAtLeast(0))
            .apply()
    }

    /** The bundled sample is permanent and cannot be removed. */
    fun deleteBook(id: String) {
        if (id == DemoBook.ID) return
        check(bookDirectory(id).let { !it.exists() || it.deleteRecursively() }) { "无法删除书籍" }
        progressPreferences.edit().remove("$id.chapter").remove("$id.page").remove("$id.offset").apply()
    }

    private fun bookDirectory(id: String): File {
        require(isBookId(id)) { "无效的书籍 ID" }
        return File(libraryDirectory, id)
    }

    private fun validatePdf(document: File): Int {
        try {
            return PdfPageRenderer(document).use { renderer ->
                val pages = renderer.pageCount
                if (pages <= 0) throw ImportException("PDF 中没有可阅读的页面")
                if (pages > MAX_PDF_PAGES) throw ImportException("PDF 页数过多，目前最多支持 $MAX_PDF_PAGES 页")
                // Validate a real page before publishing metadata into the library.
                renderer.render(0, 8, 8).recycle()
                pages
            }
        } catch (error: ImportException) {
            throw error
        } catch (error: SecurityException) {
            throw ImportException("暂不支持加密或需要密码的 PDF，请先提供未加密文件", error)
        } catch (error: Exception) {
            throw ImportException("无法读取 PDF，文件可能已损坏或受到加密保护", error)
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "未命名书籍.txt"

    private fun bookToJson(book: Book) = JSONObject()
        .put("id", book.id).put("title", book.title).put("author", book.author)
        .put("format", book.format).put("addedAt", book.addedAt).put("chapterCount", book.chapterCount)

    private fun bookFromJson(json: JSONObject) = Book(
        id = json.getString("id"), title = json.getString("title"), author = json.getString("author"),
        format = json.getString("format"), addedAt = json.getLong("addedAt"), chapterCount = json.getInt("chapterCount"),
    )

    private fun isBookId(id: String) = id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))

    companion object {
        private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
        internal const val MAX_CHAPTERS = 1200
        internal const val MAX_PDF_PAGES = 5000
        private val PDF_HEADER = "%PDF-".toByteArray(Charsets.US_ASCII)

        internal fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                if (count > maxBytes) throw ImportException("文件过大，导入文件最多支持 ${maxBytes / 1024 / 1024} MB")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}

internal data class ParsedBook(val title: String, val author: String, val format: String, val chapters: List<BookChapter>)
