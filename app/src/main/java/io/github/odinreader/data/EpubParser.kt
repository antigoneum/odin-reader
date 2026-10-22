package io.github.odinreader.data

import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/** A text-first EPUB 2/3 importer. Images, CSS, scripts, and remote resources are never rendered. */
internal object EpubParser {
    private const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 32 * 1024 * 1024
    private const val MAX_ENTRIES = 4096

    fun parse(bytes: ByteArray, fallbackTitle: String): ParsedBook {
        val entries = readArchive(bytes)
        if (entries.containsKey("META-INF/rights.xml")) throw ImportException("这本 EPUB 受 DRM 保护，无法导入")
        entries["META-INF/encryption.xml"]?.let { encryption ->
            val xml = parser(encryption)
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "CipherReference") {
                    val uri = xml.getAttributeValue(null, "URI").orEmpty().substringBefore('#').lowercase()
                    if (uri.endsWith(".xhtml") || uri.endsWith(".html") || uri.endsWith(".xml") || uri.endsWith(".opf")) {
                        throw ImportException("这本 EPUB 的正文受加密保护，无法导入")
                    }
                }
                xml.next()
            }
        }
        val container = entries["META-INF/container.xml"] ?: throw ImportException("EPUB 缺少 container.xml")
        val containerParser = parser(container)
        var packagePath: String? = null
        while (containerParser.eventType != XmlPullParser.END_DOCUMENT) {
            if (containerParser.eventType == XmlPullParser.START_TAG && containerParser.name == "rootfile") {
                packagePath = containerParser.getAttributeValue(null, "full-path")?.let { EpubPaths.resolve("", it) }
                if (packagePath != null) break
            }
            containerParser.next()
        }
        val opfPath = packagePath ?: throw ImportException("EPUB 未声明正文包")
        val opf = entries[opfPath] ?: throw ImportException("EPUB 正文包不存在")
        val packageDirectory = opfPath.substringBeforeLast('/', "")
        val manifest = mutableMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()
        val opfParser = parser(opf)
        var title = fallbackTitle.ifBlank { "未命名书籍" }
        var author = "作者未知"
        while (opfParser.eventType != XmlPullParser.END_DOCUMENT) {
            if (opfParser.eventType == XmlPullParser.START_TAG) {
                when (opfParser.name) {
                    "title" -> opfParser.nextText().trim().takeIf { it.isNotEmpty() }?.let { title = it }
                    "creator" -> opfParser.nextText().trim().takeIf { it.isNotEmpty() }?.let { author = it }
                    "item" -> {
                        val id = opfParser.getAttributeValue(null, "id")
                        val href = opfParser.getAttributeValue(null, "href")
                        val type = opfParser.getAttributeValue(null, "media-type").orEmpty()
                        if (id != null && href != null) {
                            // EPUB 3 permits remote artwork and styles. They are irrelevant to text import.
                            manifest[id] = ManifestItem(href, type)
                        }
                    }
                    "itemref" -> if (opfParser.getAttributeValue(null, "linear") != "no") {
                        opfParser.getAttributeValue(null, "idref")?.let { spine += it }
                    }
                }
            }
            opfParser.next()
        }
        if (spine.size > BookRepository.MAX_CHAPTERS) throw ImportException("这本书的章节数量超出限制")
        var totalCharacters = 0L
        val chapters = spine.mapIndexedNotNull { index, id ->
            val item = manifest[id] ?: throw ImportException("EPUB 目录引用了不存在的章节")
            if (item.mediaType !in setOf("application/xhtml+xml", "text/html")) return@mapIndexedNotNull null
            val chapterPath = EpubPaths.resolve(packageDirectory, item.href)
            val body = entries[chapterPath] ?: throw ImportException("EPUB 缺少正文：${chapterPath.substringAfterLast('/')}")
            val html = decodeHtml(body)
                .replace(Regex("<(head|script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            val text = plainText(html)
            totalCharacters += text.length
            if (totalCharacters > 4_000_000) throw ImportException("EPUB 正文超出 400 万字符限制")
            if (text.isBlank()) return@mapIndexedNotNull null
            val headingHtml = Regex("<h[12]\\b[^>]*>(.*?)</h[12]\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.get(1)
            val heading = headingHtml?.let(::plainText)?.takeIf { it.isNotBlank() }?.take(256) ?: "第 ${index + 1} 节"
            BookChapter(heading, text)
        }
        return ParsedBook(title, author, "EPUB", chapters)
    }

    private fun readArchive(bytes: ByteArray): Map<String, ByteArray> {
        val retained = mutableMapOf<String, ByteArray>()
        var entryCount = 0
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entryCount > MAX_ENTRIES) throw ImportException("EPUB 包含过多文件")
                // Entries are never extracted onto the filesystem.
                val path = EpubPaths.archiveEntry(entry.name)
                val keep = !entry.isDirectory && (path.endsWith(".xml", true) || path.endsWith(".opf", true) ||
                    path.endsWith(".xhtml", true) || path.endsWith(".html", true) || path.endsWith(".htm", true))
                val output = if (keep) java.io.ByteArrayOutputStream() else null
                var entryBytes = 0
                val buffer = ByteArray(8192)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    entryBytes += read
                    expanded += read
                    if (expanded > MAX_EXPANDED_BYTES) throw ImportException("EPUB 解压后的内容过大")
                    if (keep && entryBytes > MAX_ENTRY_BYTES) throw ImportException("EPUB 单个章节过大")
                    output?.write(buffer, 0, read)
                }
                if (output != null) {
                    if (retained.containsKey(path)) throw ImportException("EPUB 包含重复路径")
                    retained[path] = output.toByteArray()
                }
            }
        }
        return retained
    }

    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        setInput(ByteArrayInputStream(bytes), null)
    }

    private fun decodeHtml(bytes: ByteArray): String {
        val prefix = String(bytes, 0, minOf(bytes.size, 200), Charsets.US_ASCII)
        val encoding = Regex("encoding\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
            .find(prefix)?.groupValues?.get(1)
        return if (encoding != null) runCatching { String(bytes, Charset.forName(encoding)) }.getOrElse { BookTextParser.decode(bytes) }
        else BookTextParser.decode(bytes)
    }

    private fun plainText(html: String): String = BookTextParser.normalize(
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00a0', ' '),
    )

    private data class ManifestItem(val href: String, val mediaType: String)
}
