package io.github.odinreader.data

import android.graphics.BitmapFactory
import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/** A text-first EPUB 2/3 importer with optional local raster cover extraction. */
internal object EpubParser {
    private const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
    private const val MAX_COVER_BYTES = 8 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 32 * 1024 * 1024
    private const val MAX_ENTRIES = 4096

    fun parse(bytes: ByteArray, fallbackTitle: String): ParsedBook {
        val archive = readArchive(bytes)
        val entries = archive.documents
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
        val coverIds = mutableListOf<String>()
        val guideCovers = mutableListOf<String>()
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
                        val properties = opfParser.getAttributeValue(null, "properties").orEmpty().split(Regex("\\s+"))
                        if (id != null && href != null) {
                            manifest[id] = ManifestItem(href, type, "cover-image" in properties)
                        }
                    }
                    "meta" -> if (opfParser.getAttributeValue(null, "name").equals("cover", ignoreCase = true)) {
                        opfParser.getAttributeValue(null, "content")?.let { coverIds += it }
                    }
                    "reference" -> if (opfParser.getAttributeValue(null, "type").orEmpty()
                            .split(Regex("\\s+")).any { it.equals("cover", ignoreCase = true) }) {
                        opfParser.getAttributeValue(null, "href")?.let { guideCovers += it }
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
        val coverPaths = coverPaths(packageDirectory, manifest, spine, coverIds, guideCovers, archive)
        return ParsedBook(title, author, "EPUB", chapters, coverImageBytes = readCoverImage(bytes, coverPaths))
    }

    private fun readArchive(bytes: ByteArray): Archive {
        val retained = mutableMapOf<String, ByteArray>()
        val names = mutableSetOf<String>()
        var entryCount = 0
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entryCount > MAX_ENTRIES) throw ImportException("EPUB 包含过多文件")
                // Entries are never extracted onto the filesystem.
                val path = EpubPaths.archiveEntry(entry.name)
                if (!names.add(path)) throw ImportException("EPUB 包含重复路径")
                val optionalSvg = path.endsWith(".svg", true)
                val keep = !entry.isDirectory && (path.endsWith(".xml", true) || path.endsWith(".opf", true) ||
                    path.endsWith(".xhtml", true) || path.endsWith(".html", true) || path.endsWith(".htm", true) || optionalSvg)
                var output = if (keep) java.io.ByteArrayOutputStream() else null
                var entryBytes = 0
                val buffer = ByteArray(8192)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    entryBytes += read
                    expanded += read
                    if (expanded > MAX_EXPANDED_BYTES) throw ImportException("EPUB 解压后的内容过大")
                    if (keep && entryBytes > MAX_ENTRY_BYTES) {
                        if (!optionalSvg) throw ImportException("EPUB 单个章节过大")
                        // SVG is inspected only as an optional cover wrapper, never rendered.
                        output = null
                    }
                    output?.write(buffer, 0, read)
                }
                if (output != null) {
                    retained[path] = output.toByteArray()
                }
            }
        }
        return Archive(retained, names)
    }

    private fun coverPaths(
        directory: String,
        manifest: Map<String, ManifestItem>,
        spine: List<String>,
        coverIds: List<String>,
        guideCovers: List<String>,
        archive: Archive,
    ): List<CoverCandidate> {
        val result = linkedMapOf<String, CoverCandidate>()
        val types = manifest.values.mapNotNull { item ->
            localPath(directory, item.href)?.let { it to item.mediaType }
        }.toMap()
        val visited = mutableSetOf<String>()
        fun addReference(
            base: String,
            reference: String,
            declaredType: String? = null,
            depth: Int = 0,
            inferred: Boolean = false,
        ) {
            val path = localPath(base, reference) ?: return
            if (path !in archive.names || !visited.add(path)) return
            val type = declaredType ?: types[path]
            if (type in RASTER_TYPES || path.substringAfterLast('.', "").lowercase() in RASTER_EXTENSIONS) {
                result[path] = CoverCandidate(path, inferred)
                return
            }
            if (depth >= 2) return
            val wrapper = archive.documents[path] ?: return
            if (type !in WRAPPER_TYPES && path.substringAfterLast('.', "").lowercase() !in WRAPPER_EXTENSIONS) return
            // Malformed or missing optional artwork must not prevent importing local text.
            runCatching {
                val xml = parser(wrapper)
                while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG) {
                        val source = when (xml.name.lowercase()) {
                            "img" -> xml.getAttributeValue(null, "src")
                            "image" -> xml.getAttributeValue(null, "href")
                                ?: xml.getAttributeValue("http://www.w3.org/1999/xlink", "href")
                            else -> null
                        }
                        source?.let { addReference(path.substringBeforeLast('/', ""), it, depth = depth + 1, inferred = inferred) }
                        if (inferred && source != null) break
                    }
                    xml.next()
                }
            }
        }
        manifest.values.filter { it.coverImage }.forEach { addReference(directory, it.href, it.mediaType) }
        coverIds.mapNotNull(manifest::get).forEach { addReference(directory, it.href, it.mediaType) }
        guideCovers.forEach { addReference(directory, it) }
        manifest.forEach { (id, item) ->
            if (isCoverName(id) || isCoverName(item.href.substringBefore('#').substringBefore('?')
                    .substringAfterLast('/').substringBeforeLast('.'))) {
                addReference(directory, item.href, item.mediaType)
            }
        }
        // Some older books put an unlabelled cover at the beginning of their first
        // chapter. Never infer one from an illustration after prose or a later chapter.
        spine.firstOrNull()?.let(manifest::get)?.takeIf { it.mediaType in setOf("application/xhtml+xml", "text/html") }
            ?.let { item -> localPath(directory, item.href) }?.let { firstPath ->
                archive.documents[firstPath]?.let(::leadingImageReference)?.let { reference ->
                    addReference(firstPath.substringBeforeLast('/', ""), reference, inferred = true)
                }
            }
        return result.values.toList()
    }

    private fun leadingImageReference(document: ByteArray): String? = runCatching {
        val xml = parser(document)
        var bodyDepth = -1
        var ignoredDepth = -1
        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
            when (xml.eventType) {
                XmlPullParser.START_TAG -> {
                    val name = xml.name.lowercase()
                    if (bodyDepth < 0 && name == "body") {
                        bodyDepth = xml.depth
                    } else if (bodyDepth >= 0 && ignoredDepth < 0) {
                        when (name) {
                            "script", "style" -> ignoredDepth = xml.depth
                            "img" -> return@runCatching xml.getAttributeValue(null, "src")
                            "image" -> return@runCatching xml.getAttributeValue(null, "href")
                                ?: xml.getAttributeValue("http://www.w3.org/1999/xlink", "href")
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (xml.depth == ignoredDepth) ignoredDepth = -1
                    if (xml.depth == bodyDepth) return@runCatching null
                }
                XmlPullParser.TEXT -> if (bodyDepth >= 0 && ignoredDepth < 0 && xml.text.any {
                        !it.isWhitespace() && it !in "\u00a0\u200b\u2060\ufeff"
                    }) return@runCatching null
            }
            xml.next()
        }
        null
    }.getOrNull()

    private fun localPath(directory: String, reference: String): String? =
        runCatching { EpubPaths.resolve(directory, reference) }.getOrNull()

    private fun isCoverName(value: String): Boolean =
        value.lowercase().replace(Regex("[\\s_.-]"), "") in setOf("cover", "coverimage", "coverimg", "frontcover", "封面")

    private fun readCoverImage(bytes: ByteArray, candidates: List<CoverCandidate>): ByteArray? {
        if (candidates.isEmpty()) return null
        val priorities = candidates.withIndex().associate { it.value.path to it.index }
        var bestPriority = Int.MAX_VALUE
        var best: ByteArray? = null
        // The first pass validated every entry, actual expanded byte count and all paths.
        // A second pass retains at most one selected raster plus one bounded candidate.
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val priority = priorities[EpubPaths.archiveEntry(entry.name)]
                if (entry.isDirectory || priority == null || priority >= bestPriority) {
                    zip.closeEntry()
                    continue
                }
                var output: java.io.ByteArrayOutputStream? = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var size = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_COVER_BYTES) output = null
                    output?.write(buffer, 0, count)
                }
                output?.toByteArray()?.takeIf { hasRasterHeader(it) &&
                    (!candidates[priority].inferred || hasCoverDimensions(it)) }?.let {
                    best = it
                    bestPriority = priority
                }
            }
        }
        return best
    }

    private fun hasCoverDimensions(bytes: ByteArray): Boolean = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        width >= 200 && height >= 200 && width.toDouble() / height in 0.4..0.95 &&
            width.toLong() * height <= 100_000_000L
    }.getOrDefault(false)

    private fun hasRasterHeader(bytes: ByteArray): Boolean =
        (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) ||
            (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_HEADER)) ||
            (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP")

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

    private val RASTER_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    private val RASTER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val WRAPPER_TYPES = setOf("application/xhtml+xml", "text/html", "image/svg+xml")
    private val WRAPPER_EXTENSIONS = setOf("xhtml", "html", "htm", "svg")
    private val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private data class CoverCandidate(val path: String, val inferred: Boolean)
    private data class ManifestItem(val href: String, val mediaType: String, val coverImage: Boolean)
    private data class Archive(val documents: Map<String, ByteArray>, val names: Set<String>)
}
