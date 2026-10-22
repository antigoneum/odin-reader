package io.github.odinreader.reader

import android.content.Context
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Fonts are decoded off the UI thread once, then shared by pagination and rendering. */
object ReaderFonts {
    private val loaded = ConcurrentHashMap<ReaderFont, Typeface>().apply {
        put(ReaderFont.SystemSerif, Typeface.SERIF)
        put(ReaderFont.SystemSans, Typeface.SANS_SERIF)
    }
    private val loadLock = Any()
    private val combinations = ConcurrentHashMap<ReaderFontSelection, ReaderTypography>()

    fun getCached(font: ReaderFont): Typeface? = loaded[font]

    suspend fun load(context: Context, font: ReaderFont): Typeface = getCached(font) ?: withContext(Dispatchers.IO) {
        // Keep only font objects, never an Activity or its Context, in this cache.
        synchronized(loadLock) {
            loaded[font] ?: Typeface.createFromAsset(context.applicationContext.assets, "fonts/${checkNotNull(font.asset)}")
                .also { loaded[font] = it }
        }
    }

    fun getCached(selection: ReaderFontSelection): ReaderTypography? = combinations[selection]

    suspend fun load(context: Context, selection: ReaderFontSelection): ReaderTypography {
        getCached(selection)?.let { return it }
        val chinese = load(context, selection.chinese)
        val english = load(context, selection.english)
        val typography = ReaderTypography(chinese, english)
        return combinations.putIfAbsent(selection, typography) ?: typography
    }
}
