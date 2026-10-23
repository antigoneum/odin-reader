package io.github.odinreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.odinreader.reader.ReaderFont
import io.github.odinreader.reader.ReaderFontSelection
import io.github.odinreader.reader.ReaderLineSpacing
import io.github.odinreader.reader.ReaderLetterSpacing

private val FontChoiceShape = RoundedCornerShape(14.dp)

/** A scrollable controller-friendly list and a single, lazily loaded native preview. */
@Composable
internal fun ReaderFontPicker(
    selection: ReaderFontSelection,
    onChineseFont: (ReaderFont) -> Unit,
    onEnglishFont: (ReaderFont) -> Unit,
    backFocus: FocusRequester,
    fontSize: Float = 20f,
    lineSpacing: Float = ReaderLineSpacing.Default,
    letterSpacing: Float = ReaderLetterSpacing.Default,
    paperStyle: PaperStyle = PaperStyle.Theme,
) {
    var editingEnglish by rememberSaveable { mutableStateOf(false) }
    val chineseFocus = remember { FocusRequester() }
    val englishFocus = remember { FocusRequester() }
    val firstVisibleFontFocus = remember { FocusRequester() }
    val chineseScroll = rememberLazyListState(
        initialFirstVisibleItemIndex = ReaderFont.chineseChoices.indexOf(selection.chinese).coerceAtLeast(0),
    )
    val englishScroll = rememberLazyListState(
        initialFirstVisibleItemIndex = ReaderFont.englishChoices.indexOf(selection.english).coerceAtLeast(0),
    )
    val listState = if (editingEnglish) englishScroll else chineseScroll
    val firstVisibleIndex by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0 }
    }
    val hasVisibleFont by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }
    }
    val selectedFont = if (editingEnglish) selection.english else selection.chinese
    val choices = if (editingEnglish) ReaderFont.englishChoices else ReaderFont.chineseChoices
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(
                selected = !editingEnglish,
                onClick = { editingEnglish = false },
                label = { Text("中文字体") },
                shape = FontChoiceShape,
                modifier = Modifier.testTag("font-language-chinese").focusRequester(chineseFocus)
                    .heightIn(min = 48.dp)
                    .focusProperties {
                        up = backFocus
                        right = englishFocus
                        if (hasVisibleFont) down = firstVisibleFontFocus
                    }.controllerFocusRing(FontChoiceShape),
            )
            FilterChip(
                selected = editingEnglish,
                onClick = { editingEnglish = true },
                label = { Text("英文字体") },
                shape = FontChoiceShape,
                modifier = Modifier.testTag("font-language-english").focusRequester(englishFocus)
                    .heightIn(min = 48.dp)
                    .focusProperties {
                        up = backFocus
                        left = chineseFocus
                        if (hasVisibleFont) down = firstVisibleFontFocus
                    }.controllerFocusRing(FontChoiceShape),
            )
            Text("分别选择，自动保存；阅读位置保持不变。",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1.1f).testTag("reader-font-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(choices, key = { _, font -> font.id }) { index, font ->
                    OutlinedButton(
                        onClick = { if (editingEnglish) onEnglishFont(font) else onChineseFont(font) },
                        shape = FontChoiceShape,
                        modifier = Modifier.fillMaxWidth().testTag("reader-font-${font.id}")
                            .heightIn(min = 68.dp)
                            .then(if (index == firstVisibleIndex) Modifier.focusRequester(firstVisibleFontFocus) else Modifier)
                            .focusProperties { if (index == 0) up = if (editingEnglish) englishFocus else chineseFocus }
                            .controllerFocusRing(FontChoiceShape)
                            .semantics { selected = selectedFont == font; role = Role.RadioButton },
                    ) {
                        RadioButton(selected = selectedFont == font, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(font.label, style = MaterialTheme.typography.titleSmall)
                            Text(fontChoiceDescription(font, editingEnglish), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            ReaderTypographyPreview(selection, fontSize, lineSpacing, letterSpacing, paperStyle,
                Modifier.weight(1f), tag = "reader-font-preview")
        }
    }
}

private fun fontChoiceDescription(font: ReaderFont, english: Boolean): String = when {
    !english -> font.description
    font == ReaderFont.JetBrainsMono -> "英文等宽字体，包含 Nerd 图标"
    font == ReaderFont.Literata -> "为电子阅读设计的英文衬线字体"
    else -> "英文、数字和西文标点使用这套字体"
}
