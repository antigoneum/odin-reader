package io.github.odinreader.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Pure text helpers, kept independent of Android for local unit tests. */
internal object BookTextParser {
    private val chapterHeading = Regex(
        "^(?:第[零〇一二三四五六七八九十百千万两0-9]{1,12}[章节卷部回篇].{0,50}|" +
            "(?:chapter|part)\\s+[0-9ivxlcdm]+(?:[\\s.:：—-].{0,50})?|[序前后尾]言|序章|楔子|尾声|后记)$",
        RegexOption.IGNORE_CASE,
    )

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }

    fun normalize(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\u0000", "")
        .replace("\uFEFF", "")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{4,}"), "\n\n\n")
        .trim()

    fun chapters(text: String): List<BookChapter> {
        val normalized = normalize(text)
        if (normalized.isBlank()) return emptyList()
        val chapters = mutableListOf<BookChapter>()
        val lines = StringBuilder()
        var title = "正文"
        var seenHeading = false
        for (line in normalized.lineSequence()) {
            val candidate = line.trim()
            if (candidate.length <= 64 && chapterHeading.matches(candidate)) {
                if (lines.isNotBlank()) chapters += BookChapter(title, lines.toString().trim())
                title = candidate
                lines.clear()
                seenHeading = true
            } else {
                if (lines.isNotEmpty()) lines.append('\n')
                lines.append(line)
            }
        }
        // An empty last chapter can still have a meaningful heading.
        if (lines.isNotBlank()) chapters += BookChapter(title, lines.toString().trim())
        if (chapters.isEmpty() && seenHeading) chapters += BookChapter(title, title)
        return chapters
    }
}

/** Resolve EPUB archive references without ever treating an archive entry as a disk path. */
internal object EpubPaths {
    fun resolve(baseDirectory: String, reference: String): String {
        val path = reference.substringBefore('#').substringBefore('?')
        val decoded = java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        return normalize(baseDirectory, decoded)
    }

    fun archiveEntry(name: String): String = normalize("", name)

    private fun normalize(baseDirectory: String, path: String): String {
        require(!path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000')) {
            "EPUB 路径无效"
        }
        require(!path.substringBefore('/').contains(':')) { "EPUB 不支持外部资源" }
        val segments = mutableListOf<String>()
        for (segment in "$baseDirectory/$path".split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    require(segments.isNotEmpty()) { "EPUB 路径超出归档范围" }
                    segments.removeAt(segments.lastIndex)
                }
                else -> segments += segment
            }
        }
        return segments.joinToString("/")
    }
}
