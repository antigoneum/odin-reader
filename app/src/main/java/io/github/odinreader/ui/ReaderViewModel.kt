package io.github.odinreader.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import io.github.odinreader.data.ActiveReadingClock
import io.github.odinreader.data.Book
import io.github.odinreader.data.BookContent
import io.github.odinreader.data.BookRepository
import io.github.odinreader.data.ReadingProgress
import io.github.odinreader.data.ReadingMetrics
import io.github.odinreader.data.ReadingRecord
import io.github.odinreader.data.ReadingSession
import io.github.odinreader.input.ControllerAction
import io.github.odinreader.input.ControllerBindings
import io.github.odinreader.input.ControllerButton
import io.github.odinreader.reader.ReaderFont
import io.github.odinreader.reader.ReaderFontSelection
import io.github.odinreader.reader.ReadingMode
import io.github.odinreader.reader.PageTurnAnimation
import io.github.odinreader.reader.ReaderLineSpacing
import io.github.odinreader.reader.ReaderLetterSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class PaperStyle(val label: String) { Day("日间"), Paper("纸张"), Night("夜间"), Theme("跟随主题") }

data class ReaderUiState(
    val books: List<Book> = emptyList(),
    val content: BookContent? = null,
    val progress: ReadingProgress = ReadingProgress(),
    val busy: Boolean = true,
    val message: String? = null,
    val fontSize: Float = 20f,
    val lineSpacing: Float = ReaderLineSpacing.Default,
    val letterSpacing: Float = ReaderLetterSpacing.Default,
    val readerFonts: ReaderFontSelection = ReaderFontSelection(),
    val paperStyle: PaperStyle = PaperStyle.Theme,
    val appTheme: AppTheme = AppTheme.System,
    val controllerBindings: ControllerBindings = ControllerBindings(),
    val readingRecords: Map<String, ReadingRecord> = emptyMap(),
    val readingMode: ReadingMode = ReadingMode.Single,
    val pageAnimations: Map<ReadingMode, PageTurnAnimation> = ReadingMode.entries.associateWith { it.defaultAnimation },
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BookRepository(application)
    private val preferences = application.getSharedPreferences("reader_settings", 0)
    private val mutableState = MutableStateFlow(
        ReaderUiState(
            fontSize = preferences.getFloat("font_size", 20f).coerceIn(14f, 32f),
            lineSpacing = ReaderLineSpacing.sanitize(preferences.getFloat("line_spacing", ReaderLineSpacing.Default)),
            letterSpacing = ReaderLetterSpacing.sanitize(preferences.getFloat("letter_spacing", ReaderLetterSpacing.Default)),
            readerFonts = ReaderFontSelection.fromPreferences(
                preferences.getString("reader_font_chinese", null),
                preferences.getString("reader_font_english", null),
                preferences.getString("reader_font", null),
            ),
            readingMode = ReadingMode.fromPreference(preferences.getString("reading_mode", null)),
            pageAnimations = ReadingMode.entries.associateWith { mode ->
                mode.animationFromPreferences(
                    value = preferences.getString("page_animation.${mode.id}", null),
                    legacyDualPageValue = preferences.getString("page_animation.dual_page", null),
                    legacyMode = preferences.getString("reading_mode", null),
                )
            },
            paperStyle = PaperStyle.entries.getOrElse(
                preferences.getInt("paper_style", PaperStyle.Theme.ordinal),
            ) { PaperStyle.Theme },
            appTheme = AppTheme.entries.firstOrNull { it.name == preferences.getString("app_theme", null) }
                ?: AppTheme.System,
            controllerBindings = ControllerBindings.fromPreferences(
                ControllerButton.entries.mapNotNull { button ->
                    preferences.getString("binding.${button.name}", null)?.let { button.name to it }
                }.toMap(),
            ),
        ),
    )
    val state = mutableState.asStateFlow()

    private sealed interface HistoryEvent {
        data object LoadLibrary : HistoryEvent
        data class Import(val uri: Uri) : HistoryEvent
        data class Open(val book: Book, val monotonic: Long, val wall: Long) : HistoryEvent
        data class Close(val monotonic: Long, val wall: Long) : HistoryEvent
        data class Position(val bookId: String, val progress: ReadingProgress, val monotonic: Long, val wall: Long, val pageStep: Int) : HistoryEvent
        data class Active(val active: Boolean, val monotonic: Long, val wall: Long) : HistoryEvent
        data class Tick(val monotonic: Long, val wall: Long) : HistoryEvent
        data class Flush(val monotonic: Long, val wall: Long) : HistoryEvent
        data class Barrier(val completion: CompletableDeferred<Unit>) : HistoryEvent
        data class Finish(val monotonic: Long, val wall: Long) : HistoryEvent
    }

    // This scope drains after ViewModel destruction. A cancelled viewModelScope
    // must never discard the last page or the final foreground-time checkpoint.
    private val historyScope = CoroutineScope(SupervisorJob() + historyDispatcher)
    private val events = Channel<HistoryEvent>(Channel.UNLIMITED)
    private var requestedReadingActive = false

    // Only the single IO worker below may access this mutable session state.
    private val clock = ActiveReadingClock()
    private var activeContent: BookContent? = null
    private var activeRecord: ReadingRecord? = null
    private var activeSession: ReadingSession? = null
    private var timeAtPreviousPosition = 0L
    private var uncheckpointedMillis = 0L
    private val historyWorker = historyScope.launch {
        try {
            for (event in events) {
                try {
                    when (event) {
                        HistoryEvent.LoadLibrary -> loadLibraryOnWorker()
                        is HistoryEvent.Import -> importOnWorker(event.uri)
                        is HistoryEvent.Open -> openOnWorker(event)
                        is HistoryEvent.Close -> {
                            stopSession(event.monotonic, event.wall)
                            activeContent = null
                            activeRecord = null
                            // A pending Open may have published after closeBook's immediate UI update.
                            mutableState.update { it.copy(content = null) }
                        }
                        is HistoryEvent.Position -> updatePosition(event)
                        is HistoryEvent.Active -> setActiveOnWorker(event)
                        is HistoryEvent.Tick -> {
                            advanceTime(event.monotonic, event.wall)
                            if (uncheckpointedMillis >= CHECKPOINT_MILLIS) persistCurrent()
                        }
                        is HistoryEvent.Flush -> {
                            advanceTime(event.monotonic, event.wall)
                            persistCurrent()
                        }
                        is HistoryEvent.Barrier -> {
                            persistCurrent()
                            event.completion.complete(Unit)
                        }
                        is HistoryEvent.Finish -> {
                            stopSession(event.monotonic, event.wall)
                            activeContent = null
                            activeRecord = null
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutableState.update { it.copy(busy = false, message = error.message ?: "无法保存阅读记录，请重试") }
                    if (event is HistoryEvent.Barrier) event.completion.completeExceptionally(error)
                }
            }
        } finally {
            events.close()
            historyScope.cancel()
        }
    }

    init { events.trySend(HistoryEvent.LoadLibrary) }

    fun importBook(uri: Uri) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        events.trySend(HistoryEvent.Import(uri))
    }

    fun openBook(book: Book) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        requestedReadingActive = false
        events.trySend(HistoryEvent.Open(book, SystemClock.elapsedRealtime(), System.currentTimeMillis()))
    }

    fun closeBook() {
        requestedReadingActive = false
        events.trySend(HistoryEvent.Close(SystemClock.elapsedRealtime(), System.currentTimeMillis()))
        mutableState.update { it.copy(content = null) }
    }

    fun saveProgress(bookId: String, progress: ReadingProgress) {
        if (state.value.content?.book?.id != bookId) return
        mutableState.update { it.copy(progress = progress) }
        events.trySend(HistoryEvent.Position(bookId, progress, SystemClock.elapsedRealtime(), System.currentTimeMillis(), state.value.readingMode.pageStep))
    }

    /** Called by the resumed, focused reading page; menus/loading never enable it. */
    fun setReadingActive(active: Boolean) {
        if (active && state.value.content == null) return
        if (requestedReadingActive == active) return
        requestedReadingActive = active
        events.trySend(HistoryEvent.Active(active, SystemClock.elapsedRealtime(), System.currentTimeMillis()))
    }

    /** Refreshes displayed elapsed time, with a durable checkpoint every five seconds. */
    fun tickReading() {
        if (requestedReadingActive) {
            events.trySend(HistoryEvent.Tick(SystemClock.elapsedRealtime(), System.currentTimeMillis()))
        }
    }

    fun flushReading() {
        events.trySend(HistoryEvent.Flush(SystemClock.elapsedRealtime(), System.currentTimeMillis()))
    }

    /** Also works after onCleared, so backups/tests can wait before restoring a book. */
    suspend fun awaitHistoryWrites() {
        val completion = CompletableDeferred<Unit>()
        if (events.trySend(HistoryEvent.Barrier(completion)).isSuccess) completion.await()
        else historyWorker.join()
    }

    private fun loadLibraryOnWorker() {
        val books = repository.listBooks()
        val records = repository.readingHistory.getRecords().toMutableMap()
        // Old SharedPreferences only contain the bookmark. Resolve its fraction
        // once on IO, preserving its original timestamp and zero reading time.
        books.forEach { book ->
            records[book.id]?.takeIf { it.totalUnits <= 0.0 }?.let { old ->
                runCatching { ReadingMetrics.initialize(repository.readBook(book.id), old) }.getOrNull()?.let { filled ->
                    repository.readingHistory.save(filled)
                    records[book.id] = filled
                }
            }
        }
        mutableState.update { it.copy(books = books, readingRecords = records, busy = false) }
    }

    private fun importOnWorker(uri: Uri) {
        val book = repository.importBook(uri)
        val books = repository.listBooks()
        val records = repository.readingHistory.getRecords()
        mutableState.update {
            it.copy(books = books, readingRecords = records,
                busy = false, message = "已导入《${book.title}》")
        }
    }

    private fun openOnWorker(event: HistoryEvent.Open) {
        stopSession(event.monotonic, event.wall)
        val content = repository.readBook(event.book.id)
        val previous = repository.readingHistory.getRecord(event.book.id) ?: ReadingRecord(bookId = event.book.id)
        val record = ReadingMetrics.initialize(content, previous).copy(lastReadAt = event.wall)
        activeContent = content
        activeRecord = record
        activeSession = null
        timeAtPreviousPosition = record.totalReadingMillis
        repository.readingHistory.save(record)
        uncheckpointedMillis = 0L
        mutableState.update {
            it.copy(content = content, progress = record.progress, busy = false,
                readingRecords = it.readingRecords + (record.bookId to record))
        }
    }

    private fun updatePosition(event: HistoryEvent.Position) {
        val content = activeContent?.takeIf { it.book.id == event.bookId } ?: return
        advanceTime(event.monotonic, event.wall)
        val before = activeRecord ?: return
        val oldUnits = ReadingMetrics.position(content, before.progress).positionUnits
        val updated = ReadingMetrics.withProgress(content, before, event.progress,
            (before.totalReadingMillis - timeAtPreviousPosition).coerceAtLeast(0L),
            sequentialPageStep = event.pageStep).copy(lastReadAt = event.wall)
        if (ReadingMetrics.position(content, event.progress).positionUnits != oldUnits) {
            timeAtPreviousPosition = updated.totalReadingMillis
        }
        activeRecord = updated
        publish(updated)
        persistCurrent()
    }

    private fun setActiveOnWorker(event: HistoryEvent.Active) {
        if (!event.active) {
            stopSession(event.monotonic, event.wall)
            return
        }
        val record = activeRecord ?: return
        if (!clock.active) {
            activeSession = ReadingSession(UUID.randomUUID().toString(), record.bookId, event.wall, event.wall, 0L)
            clock.setActive(true, event.monotonic)
        } else advanceTime(event.monotonic, event.wall)
    }

    private fun advanceTime(monotonic: Long, wall: Long) {
        val elapsed = clock.advance(monotonic)
        if (elapsed <= 0L) return
        val record = activeRecord ?: return
        val updated = record.copy(totalReadingMillis = ReadingMetrics.saturatingAdd(record.totalReadingMillis, elapsed),
            lastReadAt = wall)
        activeRecord = updated
        activeSession = activeSession?.let {
            it.copy(endedAt = maxOf(it.startedAt, wall), durationMillis = ReadingMetrics.saturatingAdd(it.durationMillis, elapsed))
        }
        uncheckpointedMillis = ReadingMetrics.saturatingAdd(uncheckpointedMillis, elapsed)
        publish(updated)
    }

    private fun stopSession(monotonic: Long, wall: Long) {
        advanceTime(monotonic, wall)
        clock.setActive(false, monotonic)
        persistCurrent()
        activeSession = null
    }

    private fun persistCurrent() {
        val record = activeRecord ?: return
        repository.readingHistory.save(record, activeSession?.takeIf { it.durationMillis > 0L })
        uncheckpointedMillis = 0L
    }

    private fun publish(record: ReadingRecord) {
        mutableState.update { it.copy(readingRecords = it.readingRecords + (record.bookId to record)) }
    }

    fun setFontSize(size: Float) {
        val value = size.coerceIn(14f, 32f)
        mutableState.update { it.copy(fontSize = value) }
        preferences.edit().putFloat("font_size", value).apply()
    }

    fun setReadingMode(mode: ReadingMode) {
        mutableState.update { it.copy(readingMode = mode) }
        val editor = preferences.edit().putString("reading_mode", mode.id)
        if (preferences.getString("reading_mode", null) == "dual_page") {
            // Loading is read-only. Preserve the inherited animation when a user
            // explicitly replaces the legacy mode, including its former default.
            editor.putString("page_animation.dual_column", state.value.pageAnimations.getValue(ReadingMode.DualColumn).id)
        }
        editor.apply()
    }

    fun setLineSpacing(spacing: Float) {
        val value = ReaderLineSpacing.sanitize(spacing)
        mutableState.update { it.copy(lineSpacing = value) }
        preferences.edit().putFloat("line_spacing", value).apply()
    }

    fun setLetterSpacing(spacing: Float) {
        val value = ReaderLetterSpacing.sanitize(spacing)
        mutableState.update { it.copy(letterSpacing = value) }
        preferences.edit().putFloat("letter_spacing", value).apply()
    }

    fun setPageAnimation(mode: ReadingMode, animation: PageTurnAnimation) {
        mutableState.update { it.copy(pageAnimations = it.pageAnimations + (mode to animation)) }
        preferences.edit().putString("page_animation.${mode.id}", animation.id).apply()
    }

    fun setChineseFont(font: ReaderFont) {
        if (font !in ReaderFont.chineseChoices) return
        mutableState.update { it.copy(readerFonts = it.readerFonts.copy(chinese = font)) }
        preferences.edit().putString("reader_font_chinese", font.id).apply()
    }

    fun setEnglishFont(font: ReaderFont) {
        mutableState.update { it.copy(readerFonts = it.readerFonts.copy(english = font)) }
        preferences.edit().putString("reader_font_english", font.id).apply()
    }

    fun setPaperStyle(style: PaperStyle) {
        mutableState.update { it.copy(paperStyle = style) }
        preferences.edit().putInt("paper_style", style.ordinal).apply()
    }

    fun setAppTheme(theme: AppTheme) {
        // Selecting a palette also applies it to the native book page. An explicit
        // paper background can still be chosen afterwards without changing the UI.
        mutableState.update { it.copy(appTheme = theme, paperStyle = PaperStyle.Theme) }
        preferences.edit().putString("app_theme", theme.name)
            .putInt("paper_style", PaperStyle.Theme.ordinal).apply()
    }

    fun setControllerBinding(button: ControllerButton, action: ControllerAction) {
        val bindings = state.value.controllerBindings.withBinding(button, action)
        saveControllerBindings(bindings)
    }

    fun resetControllerBindings() { saveControllerBindings(ControllerBindings()) }

    private fun saveControllerBindings(bindings: ControllerBindings) {
        mutableState.update { it.copy(controllerBindings = bindings) }
        val editor = preferences.edit()
        bindings.toPreferences().forEach { (button, action) -> editor.putString("binding.$button", action) }
        editor.apply()
    }

    fun dismissMessage() { mutableState.update { it.copy(message = null) } }

    override fun onCleared() {
        requestedReadingActive = false
        events.trySend(HistoryEvent.Finish(SystemClock.elapsedRealtime(), System.currentTimeMillis()))
        events.close()
        super.onCleared()
    }

    companion object {
        private const val CHECKPOINT_MILLIS = 5_000L
        private val historyDispatcher = Dispatchers.IO.limitedParallelism(1)
    }
}
