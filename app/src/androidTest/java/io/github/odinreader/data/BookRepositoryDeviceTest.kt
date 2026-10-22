package io.github.odinreader.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Every test owns a cache directory and uniquely named preferences, never the user's library. */
@RunWith(AndroidJUnit4::class)
class BookRepositoryDeviceTest {
    private lateinit var context: IsolatedBookContext

    @Before
    fun createIsolatedStorage() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        context = IsolatedBookContext(app, "device-test-" + UUID.randomUUID().toString())
        check(context.filesDir.mkdirs())
    }

    @After
    fun removeOnlyIsolatedStorage() {
        if (!::context.isInitialized) return
        context.clearIsolatedPreferences()
        val directory = context.filesDir.canonicalFile
        val expectedParent = File(context.baseContext.cacheDir, "reader-repository-tests").canonicalFile
        check(directory.parentFile == expectedParent)
        directory.deleteRecursively()
    }

    @Test
    fun txtImportAndReadingProgressSurviveRepositoryRecreation() {
        val repository = BookRepository(context)
        val book = repository.importBook(fixture("journey.txt", "第一章 出发\n中文正文 📖。\n第二章 海边\n海风轻轻吹过。"))
        assertEquals("TXT", book.format)
        assertEquals(2, book.chapterCount)
        val progress = ReadingProgress(chapterIndex = 1, pageIndex = 3, charOffset = 12)
        repository.saveProgress(book.id, progress)

        val recreated = BookRepository(context)
        assertEquals(progress, recreated.getProgress(book.id))
        assertTrue(recreated.listBooks().any { it.id == book.id })
        val content = recreated.readBook(book.id)
        assertEquals(listOf("第一章 出发", "第二章 海边"), content.chapters.map { it.title })
        assertTrue(content.chapters.first().text.contains("中文正文 📖。"))
        assertEquals("海风轻轻吹过。", content.chapters.last().text)
    }

    @Test
    fun deletingImportedBookRemovesItsProgressAndLeavesOtherBooks() {
        val repository = BookRepository(context)
        val first = repository.importBook(fixture("first.txt", "第一章 起点\n第一本正文"))
        val second = repository.importBook(fixture("second.txt", "第一章 起点\n第二本正文"))
        repository.saveProgress(first.id, ReadingProgress(1, 2, 30))
        repository.saveProgress(second.id, ReadingProgress(2, 3, 40))
        repository.deleteBook(first.id)

        assertFalse(File(context.filesDir, "books/" + first.id).exists())
        assertEquals(ReadingProgress(), repository.getProgress(first.id))
        assertTrue(repository.listBooks().any { it.id == second.id })
        assertTrue(repository.listBooks().any { it.isDemo })
        assertEquals(ReadingProgress(2, 3, 40), repository.getProgress(second.id))
    }

    @Test
    fun bundledDemoRemainsReadableAfterDeleteRequest() {
        val repository = BookRepository(context)
        val demo = repository.listBooks().single()
        assertTrue(demo.isDemo)
        repository.deleteBook(demo.id)
        assertEquals(demo.id, repository.listBooks().single().id)
        assertEquals(4, repository.readBook(demo.id).chapters.size)
    }

    private fun fixture(name: String, text: String): Uri {
        val file = File(context.filesDir, name)
        file.writeText(text, Charsets.UTF_8)
        return Uri.fromFile(file)
    }

    private class IsolatedBookContext(base: Context, private val prefix: String) : ContextWrapper(base) {
        private val root = File(File(base.cacheDir, "reader-repository-tests"), prefix)
        private val preferenceNames = mutableSetOf<String>()

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + "-" + name
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
}
