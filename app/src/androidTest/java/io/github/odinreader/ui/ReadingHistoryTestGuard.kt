package io.github.odinreader.ui

import android.content.Context
import android.os.Looper
import io.github.odinreader.data.BookRepository
import io.github.odinreader.data.ReadingHistorySnapshot
import kotlinx.coroutines.runBlocking

/** Isolates specified books, including sessions, without replacing the user's database. */
internal class ReadingHistoryTestGuard(context: Context, vararg bookIds: String) {
    private val history = BookRepository(context).readingHistory
    private val ids = bookIds.distinct().also { values ->
        require(values.all(String::isNotBlank)) { "History guard needs nonblank book IDs" }
    }
    private val models = linkedSetOf<ReaderViewModel>()
    private var saved: Map<String, ReadingHistorySnapshot>? = null

    fun before() {
        checkWorkerThread()
        check(saved == null) { "History guard is already active" }
        val snapshots = ids.associateWith(history::snapshot)
        try {
            ids.forEach { history.restore(it, ReadingHistorySnapshot(null, emptyList())) }
            saved = snapshots
        } catch (failure: Throwable) {
            snapshots.forEach { (id, snapshot) ->
                runCatching { history.restore(id, snapshot) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    fun attachModel(model: ReaderViewModel) { models += model }

    /** Use before assertions that inspect the asynchronous, durable position. */
    fun awaitWrites() {
        checkWorkerThread()
        runBlocking { models.forEach { it.awaitHistoryWrites() } }
    }

    fun after() {
        checkWorkerThread()
        val snapshots = saved ?: return
        // The Activity rule is the inner rule: its lifecycle/session has ended.
        // A cleared ViewModel's independent IO worker must finish before restore.
        awaitWrites()
        snapshots.forEach { (id, snapshot) -> history.restore(id, snapshot) }
        saved = null
        models.clear()
    }

    private fun checkWorkerThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Blocking history test work must run off the main thread" }
    }
}
