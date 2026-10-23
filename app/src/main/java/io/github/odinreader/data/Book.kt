package io.github.odinreader.data

import java.io.File

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: String,
    val addedAt: Long,
    val chapterCount: Int,
    val isDemo: Boolean = false,
    /** Bounded local artwork, if the imported book provides a usable cover. */
    val coverFile: File? = null,
)

data class BookChapter(val title: String, val text: String)

data class BookContent(
    val book: Book,
    val chapters: List<BookChapter>,
    /** PDFs retain their original pages instead of converting them to reflowable text. */
    val pdfFile: File? = null,
)

data class ReadingProgress(
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val charOffset: Int = 0,
    val completed: Boolean = false,
)

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
