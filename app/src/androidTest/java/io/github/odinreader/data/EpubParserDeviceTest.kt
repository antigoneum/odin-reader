package io.github.odinreader.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun epubThreeCoverPropertyWinsOverLegacyMetadataAndHandlesEncodedNames() {
        val preferred = raster(Color.BLUE)
        val legacy = raster(Color.RED)
        val entries = binaryEntries().apply {
            editPackage {
                it.replace("</metadata>", "<meta name=\"cover\" content=\"old-art\"/></metadata>")
                    .replace("</manifest>", """
                        <item id="new-art" href="Images/Cover%20Image.png#front" media-type="image/png" properties="cover-image scripted"/>
                        <item id="old-art" href="Images/old.png" media-type="image/png"/>
                        </manifest>
                    """.trimIndent())
            }
            put("OEBPS/Images/old.png", legacy)
            put("OEBPS/Images/Cover Image.png", preferred)
        }
        val book = EpubParser.parse(archiveBytes(entries), "fallback")
        assertArrayEquals(preferred, book.coverImageBytes)
        assertEquals(2, book.chapters.size)
    }

    @Test
    fun epubTwoCoverMetadataResolvesManifestIdWithoutGuessingImageOrder() {
        val cover = raster(Color.GREEN)
        val entries = binaryEntries().apply {
            editPackage {
                it.replace("</metadata>", "<meta name=\"cover\" content=\"artwork-37\"/></metadata>")
                    .replace("</manifest>", """
                        <item id="first-illustration" href="Images/first.png" media-type="image/png"/>
                        <item id="artwork-37" href="Images/portrait.png" media-type="image/png"/>
                        </manifest>
                    """.trimIndent())
            }
            put("OEBPS/Images/first.png", raster(Color.YELLOW))
            put("OEBPS/Images/portrait.png", cover)
        }
        assertArrayEquals(cover, EpubParser.parse(archiveBytes(entries), "fallback").coverImageBytes)
    }

    @Test
    fun guideCoverFindsRasterThroughXhtmlAndSvgWrappersUsingRelativePaths() {
        val cover = raster(Color.CYAN)
        val entries = binaryEntries().apply {
            editPackage { it.replace("</package>", """
                <guide><reference type="cover" href="Text/front.xhtml#art"/></guide></package>
            """.trimIndent()) }
            put("OEBPS/Text/front.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml"><body>
                  <img src="../Images/front.svg"/>
                </body></html>
            """.trimIndent().toByteArray())
            put("OEBPS/Images/front.svg", """
                <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
                  <image xlink:href="front%23art.png?cached=1#image"/>
                </svg>
            """.trimIndent().toByteArray())
            put("OEBPS/Images/front#art.png", cover)
        }
        assertArrayEquals(cover, EpubParser.parse(archiveBytes(entries), "fallback").coverImageBytes)
    }

    @Test
    fun namedCoverFallbackWorksWithoutTreatingAnIllustrationAsTheCover() {
        val cover = raster(Color.MAGENTA)
        val entries = binaryEntries().apply {
            editPackage { it.replace("</manifest>", """
                <item id="drawing" href="Images/figure1.png" media-type="image/png"/>
                </manifest>
            """.trimIndent()) }
            put("OEBPS/Images/figure1.png", raster(Color.GRAY))
        }
        assertNull(EpubParser.parse(archiveBytes(entries), "fallback").coverImageBytes)
        entries.editPackage { it.replace("</manifest>", """
            <item id="artwork" href="Images/cover.png" media-type="image/png"/>
            </manifest>
        """.trimIndent()) }
        entries["OEBPS/Images/cover.png"] = cover
        assertArrayEquals(cover, EpubParser.parse(archiveBytes(entries), "fallback").coverImageBytes)
    }

    @Test
    fun leadingImageIsInferredEvenWhenLongProseAndManyIllustrationsFollowInTheSameChapter() {
        val cover = raster(Color.BLUE, 600, 800)
        val entries = binaryEntries().apply {
            firstChapterBody("""
                <!-- No visible text precedes the image. -->&#160;
                <style>.hidden { color: black; }</style><script>ignored()</script>
                <p><img src="art%23one.png#front"/></p>
                <p>${"正文持续阅读。".repeat(9000)}</p>
                ${"<p><img src=\"later.png\"/></p>".repeat(55)}
            """.trimIndent())
            editPackage { it.replace("</manifest>", """
                <item id="art-1" href="Text/art%23one.png" media-type="image/png"/>
                </manifest>
            """.trimIndent()) }
            put("OEBPS/Text/art#one.png", cover)
            put("OEBPS/Text/later.png", raster(Color.RED, 600, 800))
        }
        val book = EpubParser.parse(archiveBytes(entries), "fallback")
        assertArrayEquals(cover, book.coverImageBytes)
        assertTrue(book.chapters.first().text.length > 59_000)
        assertEquals(2, book.chapters.size)
    }

    @Test
    fun visibleTextBeforeAnImageAndLeadingImagesInLaterChaptersAreNotInferredAsCovers() {
        for (prefix in listOf("<h1>正文标题</h1>", "<p>开头正文。</p>", "&#169;")) {
            val entries = binaryEntries().apply {
                firstChapterBody("$prefix<p><img src=\"art.png\"/></p><p>图片后的正文。</p>")
                put("OEBPS/Text/a#b.xhtml", """
                    <html xmlns="http://www.w3.org/1999/xhtml"><body>
                      <img src="art.png"/><p>后续章节正文。</p>
                    </body></html>
                """.trimIndent().toByteArray())
                put("OEBPS/Text/art.png", raster(Color.GREEN, 600, 800))
            }
            val book = EpubParser.parse(archiveBytes(entries), "fallback")
            assertNull(book.coverImageBytes)
            assertEquals(2, book.chapters.size)
        }
    }

    @Test
    fun tinySquareLandscapeAndOverlyNarrowLeadingImagesDoNotBecomeCoversOrSelectALaterImage() {
        for ((width, height) in listOf(3 to 5, 199 to 300, 600 to 300, 300 to 300, 200 to 600)) {
            val entries = binaryEntries().apply {
                firstChapterBody("""
                    <p><img src="art.png"/></p><p>正文。</p><img src="later.png"/>
                """.trimIndent())
                put("OEBPS/Text/art.png", raster(Color.YELLOW, width, height))
                put("OEBPS/Text/later.png", raster(Color.BLUE, 600, 800))
            }
            assertNull("Inferred cover dimensions: $width x $height",
                EpubParser.parse(archiveBytes(entries), "fallback").coverImageBytes)
        }
    }

    @Test
    fun explicitCoverMetadataWinsOverAnOtherwiseValidLeadingImage() {
        val preferred = raster(Color.MAGENTA)
        val entries = binaryEntries().apply {
            firstChapterBody("<p><img src=\"art.png\"/></p><p>正文。</p>")
            editPackage { it.replace("</manifest>", """
                <item id="declared-art" href="Images/preferred.png" media-type="image/png" properties="cover-image"/>
                </manifest>
            """.trimIndent()) }
            put("OEBPS/Text/art.png", raster(Color.BLUE, 600, 800))
            put("OEBPS/Images/preferred.png", preferred)
        }
        assertArrayEquals(preferred, EpubParser.parse(archiveBytes(entries), "fallback").coverImageBytes)
    }

    @Test
    fun leadingSvgWrapperCanIdentifyAnUnlabelledPortraitCover() {
        val cover = raster(Color.CYAN, 600, 800)
        val entries = binaryEntries().apply {
            firstChapterBody("<p><img src=\"art.svg\"/></p><p>正文。</p>")
            put("OEBPS/Text/art.svg", """
                <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
                  <image xlink:href="../Images/art.png"/>
                </svg>
            """.trimIndent().toByteArray())
            put("OEBPS/Images/art.png", cover)
        }
        assertArrayEquals(cover, EpubParser.parse(archiveBytes(entries), "fallback").coverImageBytes)
    }

    @Test
    fun unsafeRemoteAndMissingLeadingImageReferencesDoNotBlockTextImport() {
        for (source in listOf("https://example.invalid/art.png", "../../../art.png", "/art.png",
            "missing.png", "data:image/png;base64,AAAA")) {
            val entries = binaryEntries().apply {
                firstChapterBody("<img src=\"$source\"/><p>正文。</p>")
                put("OEBPS/Text/art.png", raster(Color.BLUE, 600, 800))
            }
            val book = EpubParser.parse(archiveBytes(entries), "fallback")
            assertNull(book.coverImageBytes)
            assertTrue(book.chapters.first().text.contains("正文。"))
        }
    }

    @Test
    fun missingRemoteAndUnsafeCoverReferencesDoNotBlockLocalChapters() {
        for (href in listOf("https://example.invalid/front.png", "../../../front.png", "/front.png",
            "Images/missing.png", "data:image/png;base64,AAAA")) {
            val entries = binaryEntries().apply {
                editPackage { it.replace("</manifest>", """
                    <item id="artwork" href="$href" media-type="image/png" properties="cover-image"/>
                    </manifest>
                """.trimIndent()) }
            }
            val book = EpubParser.parse(archiveBytes(entries), "fallback")
            assertNull("Optional bad cover reference: $href", book.coverImageBytes)
            assertEquals(2, book.chapters.size)
            assertTrue(book.chapters.first().text.contains("正文一"))
        }
    }

    @Test
    fun invalidAndOversizedRasterCoversAreIgnoredWithoutLosingText() {
        val large = ByteArray(8 * 1024 * 1024 + 1).also {
            raster(Color.BLACK).copyInto(it)
        }
        for (cover in listOf("not an image".toByteArray(), large)) {
            val entries = binaryEntries().apply {
                editPackage { it.replace("</manifest>", """
                    <item id="artwork" href="Images/cover.png" media-type="image/png" properties="cover-image"/>
                    </manifest>
                """.trimIndent()) }
                put("OEBPS/Images/cover.png", cover)
            }
            val book = EpubParser.parse(archiveBytes(entries), "fallback")
            assertNull(book.coverImageBytes)
            assertEquals(2, book.chapters.size)
        }
    }

    @Test
    fun duplicateNormalizedImagePathsAreRejectedEvenWhenNotRetainedAsText() {
        val image = raster(Color.WHITE)
        val entries = binaryEntries().apply {
            put("OEBPS/Images/cover.png", image)
            put("OEBPS/Images/./cover.png", image)
        }
        val error = assertThrows(ImportException::class.java) {
            EpubParser.parse(archiveBytes(entries), "fallback")
        }
        assertTrue(error.message.orEmpty().contains("重复路径"))
    }

    @Test
    fun discardedImagesStillCountTowardExpandedArchiveLimit() {
        val entries = binaryEntries().apply { put("OEBPS/Images/unused.bin", ByteArray(33 * 1024 * 1024)) }
        val error = assertThrows(ImportException::class.java) {
            EpubParser.parse(archiveBytes(entries), "fallback")
        }
        assertTrue(error.message.orEmpty().contains("解压后的内容过大"))
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
        return archiveBytes(entries.mapValues { it.value.toByteArray(Charsets.UTF_8) })
    }

    private fun binaryEntries(): LinkedHashMap<String, ByteArray> = LinkedHashMap(
        baseEntries().mapValues { it.value.toByteArray(Charsets.UTF_8) },
    )

    private fun MutableMap<String, ByteArray>.editPackage(transform: (String) -> String) {
        this["OEBPS/book.opf"] = transform(requireNotNull(this["OEBPS/book.opf"]).toString(Charsets.UTF_8))
            .toByteArray(Charsets.UTF_8)
    }

    private fun MutableMap<String, ByteArray>.firstChapterBody(body: String) {
        this["OEBPS/Text/100%.xhtml"] = """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>hidden-head</title></head><body>$body</body>
            </html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
    }

    private fun raster(color: Int, width: Int = 3, height: Int = 5): ByteArray {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(color)
            return ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            image.recycle()
        }
    }

    private fun archiveBytes(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
