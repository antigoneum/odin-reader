package io.github.odinreader.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Local reading history. Call these blocking operations from a worker thread.
 *
 * The database belongs to filesDir so a Context with isolated book storage also
 * isolates its history. Helpers are closed after each operation; a shared path
 * lock serializes independent repository instances without retaining Contexts.
 */
class ReadingHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val databaseFile = File(appContext.filesDir, "reading/reading.db").absoluteFile
    private val lock = locks.getOrPut(databaseFile.path) { Any() }

    fun getRecord(bookId: String): ReadingRecord? = withDatabase { readRecord(it, bookId) }

    fun getRecords(): Map<String, ReadingRecord> = withDatabase { database ->
        database.query(RECORDS, null, null, null, null, null, "last_read_at DESC, book_id ASC").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val record = cursor.toRecord()
                    put(record.bookId, record)
                }
            }
        }
    }

    /** Position/statistics and this session's cumulative duration are committed together. */
    fun save(record: ReadingRecord, session: ReadingSession? = null) {
        require(record.bookId.isNotBlank()) { "Book ID cannot be blank" }
        require(session == null || session.bookId == record.bookId) { "Session belongs to another book" }
        withDatabase { database ->
            database.transaction {
                writeRecord(database, record)
                session?.let { writeSession(database, it) }
            }
        }
    }

    /** Compatibility entry point; the read/modify/write preserves analytics atomically. */
    fun updateProgress(bookId: String, progress: ReadingProgress, lastReadAt: Long) {
        require(bookId.isNotBlank()) { "Book ID cannot be blank" }
        withDatabase { database ->
            database.transaction {
                val record = readRecord(database, bookId) ?: ReadingRecord(bookId)
                writeRecord(database, record.copy(progress = progress, lastReadAt = lastReadAt))
            }
        }
    }

    fun getSessions(bookId: String): List<ReadingSession> = withDatabase { readSessions(it, bookId) }

    fun snapshot(bookId: String): ReadingHistorySnapshot = withDatabase { database ->
        ReadingHistorySnapshot(readRecord(database, bookId), readSessions(database, bookId))
    }

    /** Restores exactly one book, including the original absence of a record. */
    fun restore(bookId: String, snapshot: ReadingHistorySnapshot) {
        require(snapshot.record == null || snapshot.record.bookId == bookId) { "Snapshot belongs to another book" }
        require(snapshot.sessions.all { it.bookId == bookId }) { "Snapshot sessions belong to another book" }
        require(snapshot.record != null || snapshot.sessions.isEmpty()) { "Sessions require a reading record" }
        withDatabase { database ->
            database.transaction {
                removeBook(database, bookId)
                snapshot.record?.let { writeRecord(database, it) }
                snapshot.sessions.forEach { writeSession(database, it) }
            }
        }
    }

    fun deleteBook(bookId: String) {
        withDatabase { database -> database.transaction { removeBook(database, bookId) } }
    }

    private fun <T> withDatabase(block: (SQLiteDatabase) -> T): T = synchronized(lock) {
        val directory = requireNotNull(databaseFile.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建阅读记录目录" }
        val helper = HistoryHelper(appContext, databaseFile.path)
        try {
            val database = helper.writableDatabase
            migrateLegacyProgress(database)
            block(database)
        } finally {
            helper.close()
        }
    }

    private fun migrateLegacyProgress(database: SQLiteDatabase) {
        database.query(META, arrayOf("value"), "name = ?", arrayOf(LEGACY_MIGRATED), null, null, null).use {
            if (it.moveToFirst()) return
        }
        // Read the snapshot once. Old preferences remain untouched for compatibility/backups.
        val legacy = appContext.getSharedPreferences("reading_progress", Context.MODE_PRIVATE).all
        val ids = legacy.keys.mapNotNull { key ->
            LEGACY_SUFFIXES.firstOrNull { key.endsWith(it) }
                ?.let { key.removeSuffix(it).takeIf(String::isNotBlank) }
        }.toSet()
        database.transaction {
            ids.forEach { id ->
                val progress = ReadingProgress(
                    chapterIndex = legacy.nonNegativeInt("$id.chapter"),
                    pageIndex = legacy.nonNegativeInt("$id.page"),
                    charOffset = legacy.nonNegativeInt("$id.offset"),
                )
                database.insertWithOnConflict(
                    RECORDS, null, ReadingRecord(id, progress).toValues(), SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            database.insertOrThrow(META, null, ContentValues().apply {
                put("name", LEGACY_MIGRATED)
                put("value", "1")
            })
        }
    }

    private fun readRecord(database: SQLiteDatabase, id: String): ReadingRecord? =
        database.query(RECORDS, null, "book_id = ?", arrayOf(id), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRecord() else null
        }

    private fun readSessions(database: SQLiteDatabase, id: String): List<ReadingSession> =
        database.query(SESSIONS, null, "book_id = ?", arrayOf(id), null, null, "started_at DESC, id ASC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ReadingSession(
                    id = cursor.string("id"),
                    bookId = cursor.string("book_id"),
                    startedAt = cursor.long("started_at"),
                    endedAt = cursor.long("ended_at"),
                    durationMillis = cursor.long("duration_millis"),
                ))
            }
        }

    private fun writeRecord(database: SQLiteDatabase, record: ReadingRecord) {
        val values = record.toValues()
        // REPLACE would delete the old parent row and cascade-delete its session history.
        if (database.update(RECORDS, values, "book_id = ?", arrayOf(record.bookId)) == 0) {
            database.insertOrThrow(RECORDS, null, values)
        }
    }

    private fun writeSession(database: SQLiteDatabase, session: ReadingSession) {
        require(session.id.isNotBlank()) { "Session ID cannot be blank" }
        database.query(SESSIONS, arrayOf("book_id"), "id = ?", arrayOf(session.id), null, null, null).use {
            require(!it.moveToFirst() || it.getString(0) == session.bookId) { "Session ID already belongs to another book" }
        }
        val start = session.startedAt.coerceAtLeast(0L)
        val values = ContentValues().apply {
            put("id", session.id)
            put("book_id", session.bookId)
            put("started_at", start)
            put("ended_at", session.endedAt.coerceAtLeast(start))
            put("duration_millis", session.durationMillis.coerceAtLeast(0L))
        }
        if (database.update(SESSIONS, values, "id = ?", arrayOf(session.id)) == 0) {
            database.insertOrThrow(SESSIONS, null, values)
        }
    }

    private fun removeBook(database: SQLiteDatabase, id: String) {
        database.delete(SESSIONS, "book_id = ?", arrayOf(id))
        database.delete(RECORDS, "book_id = ?", arrayOf(id))
    }

    private fun ReadingRecord.toValues() = ContentValues().apply {
        put("book_id", bookId)
        put("chapter_index", progress.chapterIndex.coerceAtLeast(0))
        put("page_index", progress.pageIndex.coerceAtLeast(0))
        put("char_offset", progress.charOffset.coerceAtLeast(0))
        put("completed", if (progress.completed) 1 else 0)
        put("progress_fraction", progressFraction.finiteNonNegative().coerceAtMost(1.0))
        put("total_reading_millis", totalReadingMillis.coerceAtLeast(0L))
        if (estimatedRemainingMillis == null) putNull("estimated_remaining_millis")
        else put("estimated_remaining_millis", estimatedRemainingMillis.coerceAtLeast(0L))
        put("last_read_at", lastReadAt.coerceAtLeast(0L))
        put("speed_units", speedUnits.finiteNonNegative())
        put("speed_reading_millis", speedReadingMillis.coerceAtLeast(0L))
        put("furthest_units", furthestUnits.finiteNonNegative())
        put("total_units", totalUnits.finiteNonNegative())
    }

    private fun Cursor.toRecord() = ReadingRecord(
        bookId = string("book_id"),
        progress = ReadingProgress(int("chapter_index"), int("page_index"), int("char_offset"), int("completed") != 0),
        progressFraction = double("progress_fraction"),
        totalReadingMillis = long("total_reading_millis"),
        estimatedRemainingMillis = getColumnIndexOrThrow("estimated_remaining_millis").let { if (isNull(it)) null else getLong(it) },
        lastReadAt = long("last_read_at"),
        speedUnits = double("speed_units"),
        speedReadingMillis = long("speed_reading_millis"),
        furthestUnits = double("furthest_units"),
        totalUnits = double("total_units"),
    )

    private class HistoryHelper(context: Context, path: String) : SQLiteOpenHelper(context, path, null, VERSION) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE $RECORDS (
                    book_id TEXT PRIMARY KEY NOT NULL,
                    chapter_index INTEGER NOT NULL DEFAULT 0,
                    page_index INTEGER NOT NULL DEFAULT 0,
                    char_offset INTEGER NOT NULL DEFAULT 0,
                    completed INTEGER NOT NULL DEFAULT 0,
                    progress_fraction REAL NOT NULL DEFAULT 0,
                    total_reading_millis INTEGER NOT NULL DEFAULT 0,
                    estimated_remaining_millis INTEGER,
                    last_read_at INTEGER NOT NULL DEFAULT 0,
                    speed_units REAL NOT NULL DEFAULT 0,
                    speed_reading_millis INTEGER NOT NULL DEFAULT 0,
                    furthest_units REAL NOT NULL DEFAULT 0,
                    total_units REAL NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE $SESSIONS (
                    id TEXT PRIMARY KEY NOT NULL,
                    book_id TEXT NOT NULL REFERENCES $RECORDS(book_id) ON DELETE CASCADE,
                    started_at INTEGER NOT NULL,
                    ended_at INTEGER NOT NULL,
                    duration_millis INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX reading_sessions_book_start ON $SESSIONS(book_id, started_at DESC)")
            db.execSQL("CREATE TABLE $META (name TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Future schema changes must add an explicit migration; never reset user history.
            throw SQLiteException("Missing reading history migration: $oldVersion to $newVersion")
        }
    }

    companion object {
        private const val VERSION = 1
        private const val RECORDS = "reading_records"
        private const val SESSIONS = "reading_sessions"
        private const val META = "reading_meta"
        private const val LEGACY_MIGRATED = "legacy_progress_migrated"
        private val LEGACY_SUFFIXES = listOf(".chapter", ".page", ".offset")
        private val locks = ConcurrentHashMap<String, Any>()

        private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
        private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
        private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
        private fun Cursor.double(name: String): Double = getDouble(getColumnIndexOrThrow(name))
        private fun Double.finiteNonNegative(): Double = if (isFinite()) coerceAtLeast(0.0) else 0.0
        private fun Map<String, *>.nonNegativeInt(name: String): Int =
            (this[name] as? Number)?.toLong()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0

        private inline fun <T> SQLiteDatabase.transaction(block: () -> T): T {
            beginTransaction()
            try {
                val result = block()
                setTransactionSuccessful()
                return result
            } finally {
                endTransaction()
            }
        }
    }
}
