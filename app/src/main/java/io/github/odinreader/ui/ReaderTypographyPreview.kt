package io.github.odinreader.ui

import android.annotation.SuppressLint
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.odinreader.reader.ReaderFont
import io.github.odinreader.reader.ReaderFontSelection
import io.github.odinreader.reader.ReaderFonts
import io.github.odinreader.reader.ReaderTypography
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlin.math.roundToInt

private const val TypographyPreviewText = "山海之间，慢慢阅读。\n" +
    "The quick brown fox jumps over the lazy dog.\n" +
    "清晨的光落在纸上，每一个字都安静而清晰。行与行之间，留一点呼吸的距离。\n" +
    "0123456789 · {} [] => != · Reading, one page at a time."

/** A bounded sample uses the same native text metrics and paper colors as the reader. */
@Composable
@SuppressLint("InlinedApi")
internal fun ReaderTypographyPreview(
    selection: ReaderFontSelection,
    fontSize: Float,
    lineSpacing: Float,
    letterSpacing: Float,
    paperStyle: PaperStyle,
    modifier: Modifier = Modifier,
    tag: String = "reader-typography-preview",
) {
    val context = LocalContext.current.applicationContext
    var typography by remember(selection) { mutableStateOf<ReaderTypography?>(ReaderFonts.getCached(selection)) }
    var failed by remember(selection) { mutableStateOf(false) }
    LaunchedEffect(selection, context) {
        try {
            typography = ReaderFonts.load(context, selection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val background = when (paperStyle) {
        PaperStyle.Day -> Color(0xFFFAFAF7)
        PaperStyle.Paper -> Color(0xFFF2EBD9)
        PaperStyle.Night -> Color(0xFF121614)
        PaperStyle.Theme -> MaterialTheme.colorScheme.background
    }
    val foreground = when (paperStyle) {
        PaperStyle.Theme -> MaterialTheme.colorScheme.onBackground
        PaperStyle.Night -> Color(0xFFD0D5CD)
        else -> Color(0xFF303A31)
    }
    val sample = TypographyPreviewText + if (selection.english == ReaderFont.JetBrainsMono) "\n\uf120  \uf02d  \ue0b0" else ""
    Surface(modifier.testTag(tag), shape = RoundedCornerShape(14.dp), color = background,
        border = BorderStroke(1.dp, foreground.copy(alpha = 0.18f)),
        contentColor = foreground, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("正文实时预览", style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("中文 · ${selection.chinese.label}", style = MaterialTheme.typography.labelSmall)
                Text("英文 · ${selection.english.label}", style = MaterialTheme.typography.labelSmall)
                Text(String.format(Locale.ROOT, "%d sp · 行距 %.2f · 字距 %d%%", fontSize.roundToInt(),
                    lineSpacing, (letterSpacing * 100f).roundToInt()), style = MaterialTheme.typography.labelSmall)
            }
            val loaded = typography
            when {
                failed -> Text("字体暂时无法加载，请重新选择。", color = MaterialTheme.colorScheme.error)
                loaded == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds().testTag("$tag-viewport"),
                    factory = { viewContext ->
                        ScrollView(viewContext).apply {
                            isFocusable = false
                            isFocusableInTouchMode = false
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                            isFillViewport = true
                            isVerticalScrollBarEnabled = true
                            addView(TextView(viewContext).apply {
                                this.tag = "$tag-text"
                                includeFontPadding = false
                                setFallbackLineSpacing(true)
                                isElegantTextHeight = true
                                if (android.os.Build.VERSION.SDK_INT >= 35) {
                                    setUseBoundsForWidth(true)
                                    setLocalePreferredLineHeightForMinimumUsed(true)
                                }
                                setPadding(0, 0, 0, 0)
                                breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
                                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                                gravity = Gravity.TOP or Gravity.START
                                textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
                                isFocusable = false
                                isFocusableInTouchMode = false
                            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        }
                    },
                    update = { viewport ->
                        val view = viewport.getChildAt(0) as TextView
                        if (view.typeface !== loaded.englishTypeface) view.typeface = loaded.englishTypeface
                        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
                        view.setLineSpacing(0f, lineSpacing)
                        view.letterSpacing = letterSpacing
                        view.setTextColor(foreground.toArgb())
                        view.text = loaded.style(sample)
                        view.contentDescription = sample
                    },
                )
            }
            Text("与正文使用相同排版，样文可上下滑动。", style = MaterialTheme.typography.labelSmall,
                color = foreground.copy(alpha = 0.7f))
        }
    }
}
