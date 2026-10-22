package io.github.odinreader.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises Android XmlPullParser and Html rather than JVM Android stubs. */
@RunWith(AndroidJUnit4::class)
class EpubParserDeviceTest {
    @Test
    fun percentAndHashArchiveNamesResolveFromEncodedSpineReferences() {
        val book = EpubParser.parse(archive(baseEntries()), "fallback")
        assertEquals("设备测试书", book.title)
        assertEquals("Odin Reader", book.author)
        assertEquals(2, book.chapters.size)
        assertEquals("百分号章节", book.chapters[0].title)
        assertEquals("井号章节", book.chapters[1].title)
        assertTrue(book.chapters[0].text.contains("正文一：中文与 emoji 📖🌊。"))
        assertTrue(book.chapters[1].text.contains("正文二：路径中的 # 是文件名的一部分。"))
    }

    @Test
    fun remoteArtworkAndCssDoNotBlockLocalTextAndScriptsAreNotImported() {
        val book = EpubParser.parse(archive(baseEntries()), "fallback")
        assertEquals(2, book.chapters.size)
        val text = book.chapters.joinToString("\n") { it.text }
        assertFalse(text.contains("hidden-head"))
        assertFalse(text.contains("hidden-script"))
        assertFalse(text.contains("hidden-style"))
        assertTrue(text.contains("正文一"))
    }

    @Test
    fun rightsDocumentRejectsDrmBook() {
        val entries = baseEntries().apply {
            put("META-INF/rights.xml", "<rights>protected</rights>")
        }
        val error = assertThrows(ImportException::class.java) {
            EpubParser.parse(archive(entries), "fallback")
        }
        assertTrue(error.message.orEmpty().contains("DRM"))
    }

    @Test
    fun encryptedChapterIsRejectedBeforeRendering() {
        val entries = baseEntries().apply {
            put("META-INF/encryption.xml", """<?xml version="1.0"?>
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
                    <CipherData><CipherReference URI="OEBPS/Text/100%25.xhtml"/></CipherData>
                  </EncryptedData>
                </encryption>
            """.trimIndent())
        }
        val error = assertThrows(ImportException::class.java) {
            EpubParser.parse(archive(entries), "fallback")
        }
        assertTrue(error.message.orEmpty().contains("加密"))
    }

    @Test
    fun archiveEntryCannotTraverseAboveItsRoot() {
        val entries = baseEntries().apply { put("../outside.xhtml", "<p>outside</p>") }
        try {
            EpubParser.parse(archive(entries), "fallback")
            throw AssertionError("An archive entry must not escape the EPUB root")
        } catch (_: IllegalArgumentException) {
            // Our archive-path normalization rejects traversal on older Android versions.
        } catch (_: java.util.zip.ZipException) {
            // Android 14+ can reject unsafe ZIP paths before our parser sees the entry.
        }
    }

    @Test
    fun oversizedTextEntryIsRejectedWhileReadingActualExpandedBytes() {
        val entries = baseEntries().apply {
            put("OEBPS/Text/100%.xhtml", "x".repeat(2 * 1024 * 1024 + 1))
        }
        val error = assertThrows(ImportException::class.java) {
            EpubParser.parse(archive(entries), "fallback")
        }
        assertTrue(error.message.orEmpty().contains("章节过大"))
    }

    private fun baseEntries(): LinkedHashMap<String, String> = linkedMapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """<?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent(),
        "OEBPS/book.opf" to """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book">device-test</dc:identifier>
                <dc:title>设备测试书</dc:title><dc:creator>Odin Reader</dc:creator>
              </metadata>
              <manifest>
                <item id="first" href="Text/100%25.xhtml" media-type="application/xhtml+xml"/>
                <item id="second" href="Text/a%23b.xhtml#opening" media-type="application/xhtml+xml"/>
                <item id="cover" href="https://example.invalid/cover.jpg" media-type="image/jpeg"/>
                <item id="css" href="https://example.invalid/book.css" media-type="text/css"/>
              </manifest>
              <spine><itemref idref="first"/><itemref idref="second"/></spine>
            </package>
        """.trimIndent(),
        "OEBPS/Text/100%.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>hidden-head</title></head>
              <body><h1>百分号章节</h1><p>正文一：中文与 emoji 📖🌊。</p>
                <script>hidden-script</script><style>hidden-style</style>
              </body>
            </html>
        """.trimIndent(),
        "OEBPS/Text/a#b.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body><h1>井号章节</h1><p>正文二：路径中的 # 是文件名的一部分。</p></body>
            </html>
        """.trimIndent(),
    )

    private fun archive(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
