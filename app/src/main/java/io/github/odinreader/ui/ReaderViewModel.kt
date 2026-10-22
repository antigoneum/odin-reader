package io.github.odinreader.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.odinreader.data.Book
import io.github.odinreader.data.BookContent
import io.github.odinreader.data.BookRepository
import io.github.odinreader.data.ReadingProgress
import io.github.odinreader.input.ControllerAction
import io.github.odinreader.input.ControllerBindings
import io.github.odinreader.input.ControllerButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PaperStyle(val label: String) { Day("日间"), Paper("纸张"), Night("夜间"), Theme("跟随主题") }

data class ReaderUiState(
    val books: List<Book> = emptyList(),
    val content: BookContent? = null,
    val progress: ReadingProgress = ReadingProgress(),
    val busy: Boolean = true,
    val message: String? = null,
    val fontSize: Float = 20f,
    val paperStyle: PaperStyle = PaperStyle.Theme,
    val appTheme: AppTheme = AppTheme.System,
    val controllerBindings: ControllerBindings = ControllerBindings(),
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BookRepository(application)
    private val preferences = application.getSharedPreferences("reader_settings", 0)
    private val mutableState = MutableStateFlow(
        ReaderUiState(
            fontSize = preferences.getFloat("font_size", 20f).coerceIn(14f, 32f),
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

    init { loadLibrary() }

    private fun loadLibrary() = runIo {
        val books = repository.listBooks()
        mutableState.update { it.copy(books = books, busy = false) }
    }

    fun importBook(uri: Uri) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        runIo {
            val book = repository.importBook(uri)
            val books = repository.listBooks()
            mutableState.update { it.copy(books = books, busy = false, message = "已导入《${book.title}》") }
        }
    }

    fun openBook(book: Book) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        runIo {
            val content = repository.readBook(book.id)
            val progress = repository.getProgress(book.id)
            mutableState.update { it.copy(content = content, progress = progress, busy = false) }
        }
    }

    fun closeBook() { mutableState.update { it.copy(content = null) } }

    fun saveProgress(bookId: String, progress: ReadingProgress) {
        // Memory is updated immediately; the repository queues its small persistent write.
        mutableState.update { if (it.content?.book?.id == bookId) it.copy(progress = progress) else it }
        repository.saveProgress(bookId, progress)
    }

    fun setFontSize(size: Float) {
        val value = size.coerceIn(14f, 32f)
        mutableState.update { it.copy(fontSize = value) }
        preferences.edit().putFloat("font_size", value).apply()
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

    private fun runIo(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(busy = false, message = error.message ?: "操作失败，请重试") }
        }
    }
}
