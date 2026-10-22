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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Every database and legacy preference used here lives in isolated test storage. */
@RunWith(AndroidJUnit4::class)
class ReadingHistoryStoreDeviceTest {
    private lateinit var context: IsolatedHistoryContext

    @Before
    fun createIsolatedStorage() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        context = IsolatedHistoryContext(app, "history-test-${UUID.randomUUID()}")
        check(context.filesDir.mkdirs())
    }

    @After
    fun removeOnlyIsolatedStorage() {
        if (!::context.isInitialized) return
        context.clearIsolatedPreferences()
        val directory = context.filesDir.canonicalFile
        val expectedParent = File(context.baseContext.cacheDir, "reader-history-tests").canonicalFile
        check(directory.parentFile == expectedParent)
        directory.deleteRecursively()
    }

    @Test
    fun legacyProgressMigratesOnceWithoutChangingPreferencesOrReplacingDatabaseRecords() {
        val preferences = context.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)
        check(preferences.edit().putInt("legacy-book.chapter", 2)
            .putInt("legacy-book.page", 3).putInt("legacy-book.offset", 120).commit())
        val originalPreferences = preferences.all.toMap()
        val store = ReadingHistoryStore(context)
        assertEquals(ReadingProgress(2, 3, 120), store.getRecord("legacy-book")?.progress)
        assertEquals(originalPreferences, preferences.all)

        val updated = ReadingRecord(
            bookId = "legacy-book", progress = ReadingProgress(4, 8, 900, completed = true),
            progressFraction = 1.0, totalReadingMillis = 90_000L, lastReadAt = 100_000L,
        )
        store.save(updated)
        check(preferences.edit().putInt("legacy-book.offset", 5).putInt("later-book.page", 9).commit())
        val reopened = ReadingHistoryStore(context)
        assertEquals(updated, reopened.getRecord("legacy-book"))
        assertNull("A completed migration must not re-import stale preference keys", reopened.getRecord("later-book"))
        reopened.deleteBook("legacy-book")
        assertNull("Deleting history must not resurrect its legacy position", ReadingHistoryStore(context).getRecord("legacy-book"))
    }

    @Test
    fun cumulativeSessionUpdatesAndStatisticsSurviveNewStoreInstances() {
        val store = ReadingHistoryStore(context)
        val first = ReadingRecord(
            bookId = "book", progress = ReadingProgress(1, 2, 30), progressFraction = 0.25,
            totalReadingMillis = 1_000L, estimatedRemainingMillis = 3_000L, lastReadAt = 11_000L,
            speedUnits = 40.0, speedReadingMillis = 1_000L, furthestUnits = 400.0, totalUnits = 1_600.0,
        )
        val session = ReadingSession("session-1", "book", 10_000L, 11_000L, 1_000L)
        store.save(first, session)
        assertEquals(first, ReadingHistoryStore(context).getRecord("book"))
        assertEquals(listOf(session), ReadingHistoryStore(context).getSessions("book"))

        val extended = first.copy(totalReadingMillis = 2_500L, lastReadAt = 12_500L)
        val extendedSession = session.copy(endedAt = 12_500L, durationMillis = 2_500L)
        store.save(extended, extendedSession)
        assertEquals(listOf(extendedSession), store.getSessions("book"))
        val secondSession = ReadingSession("session-2", "book", 20_000L, 20_500L, 500L)
        store.save(extended.copy(totalReadingMillis = 3_000L), secondSession)
        // Updating only the parent row must never cascade-delete its previous sessions.
        store.save(extended.copy(totalReadingMillis = 3_000L, estimatedRemainingMillis = null))
        assertEquals(listOf(secondSession, extendedSession), store.getSessions("book"))
        assertEquals(3_000L, store.getRecord("book")?.totalReadingMillis)
        assertNull(store.getRecord("book")?.estimatedRemainingMillis)
        assertEquals(setOf("book"), store.getRecords().keys)
    }

    @Test
    fun failedSessionWriteRollsBackItsReadingRecordInTheSameTransaction() {
        val store = ReadingHistoryStore(context)
        val original = ReadingRecord("first", totalReadingMillis = 1_000L)
        val session = ReadingSession("shared-id", "first", 1_000L, 2_000L, 1_000L)
        store.save(original, session)
        try {
            store.save(ReadingRecord("second"), session.copy(bookId = "second"))
            fail("An existing session ID cannot move to another book")
        } catch (_: IllegalArgumentException) {
            // The inserted 'second' parent must be rolled back together with the invalid session.
        }
        assertNull(store.getRecord("second"))
        assertEquals(original, store.getRecord("first"))
        assertEquals(listOf(session), store.getSessions("first"))
    }

    @Test
    fun snapshotRestoresOnlyItsBookIncludingAbsentHistory() {
        val store = ReadingHistoryStore(context)
        val original = ReadingRecord("restored", progress = ReadingProgress(1, 2, 3), totalReadingMillis = 5_000L)
        val originalSession = ReadingSession("restored-session", "restored", 1_000L, 6_000L, 5_000L)
        val untouched = ReadingRecord("untouched", progressFraction = 0.8, totalReadingMillis = 12_000L)
        store.save(original, originalSession)
        store.save(untouched)
        val snapshot = store.snapshot("restored")
        val absent = store.snapshot("new-book")
        assertNull(absent.record)
        assertTrue(absent.sessions.isEmpty())

        store.save(original.copy(progressFraction = 1.0, totalReadingMillis = 20_000L),
            ReadingSession("extra-session", "restored", 10_000L, 25_000L, 15_000L))
        store.restore("restored", snapshot)
        assertEquals(original, store.getRecord("restored"))
        assertEquals(listOf(originalSession), store.getSessions("restored"))
        assertEquals(untouched, store.getRecord("untouched"))

        store.save(ReadingRecord("new-book"), ReadingSession("new-session", "new-book", 1L, 2L, 1L))
        store.restore("new-book", absent)
        assertNull(store.getRecord("new-book"))
        assertTrue(store.getSessions("new-book").isEmpty())
        assertEquals(untouched, store.getRecord("untouched"))
    }

    @Test
    fun repositoryPositionUpdatesPreserveStatisticsAndDeletingBookRemovesSessions() {
        val source = File(context.filesDir, "isolated.txt").apply { writeText("第一章\n测试正文") }
        val repository = BookRepository(context)
        val book = repository.importBook(Uri.fromFile(source))
        val record = ReadingRecord(book.id, progressFraction = 0.2, totalReadingMillis = 6_000L,
            estimatedRemainingMillis = 24_000L, speedUnits = 200.0, speedReadingMillis = 6_000L)
        val session = ReadingSession("session", book.id, 1_000L, 7_000L, 6_000L)
        repository.readingHistory.save(record, session)
        val progress = ReadingProgress(0, 3, 12, completed = true)
        repository.saveProgress(book.id, progress)
        assertEquals(progress, BookRepository(context).getProgress(book.id))
        assertEquals(record.totalReadingMillis, repository.readingHistory.getRecord(book.id)?.totalReadingMillis)
        assertEquals(record.estimatedRemainingMillis, repository.readingHistory.getRecord(book.id)?.estimatedRemainingMillis)
        assertEquals(listOf(session), repository.readingHistory.getSessions(book.id))

        repository.deleteBook(book.id)
        assertNull(repository.readingHistory.getRecord(book.id))
        assertTrue(repository.readingHistory.getSessions(book.id).isEmpty())
        assertEquals(ReadingProgress(), repository.getProgress(book.id))
        assertTrue(repository.listBooks().single().isDemo)
    }

    @Test
    fun databaseIsCreatedUnderTheIsolatedFilesDirectory() {
        val root = context.filesDir.canonicalFile
        val database = File(root, "reading/reading.db")
        assertFalse(database.exists())
        val store = ReadingHistoryStore(context)
        store.save(ReadingRecord("isolated-book"))
        assertTrue(database.isFile)
        assertEquals(File(root, "reading").canonicalFile, database.canonicalFile.parentFile)
        assertEquals("isolated-book", ReadingHistoryStore(context).getRecords().values.single().bookId)
    }

    private class IsolatedHistoryContext(base: Context, private val prefix: String) : ContextWrapper(base) {
        private val root = File(File(base.cacheDir, "reader-history-tests"), prefix)
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
}
