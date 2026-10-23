package io.github.odinreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.odinreader.reader.PageTurnAnimation
import io.github.odinreader.reader.ReadingMode

private val DisplayControlShape = RoundedCornerShape(14.dp)

@Composable
internal fun DisplaySettingsEntry(
    mode: ReadingMode,
    animations: Map<ReadingMode, PageTurnAnimation>,
    focus: FocusRequester,
    restoreFocus: Boolean,
    onFocusRestored: () -> Unit,
    onClick: () -> Unit,
) {
    LaunchedEffect(restoreFocus) {
        if (restoreFocus) {
            focus.requestFocus()
            onFocusRestored()
        }
    }
    OutlinedButton(
        onClick = onClick,
        shape = DisplayControlShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp).focusRequester(focus)
            .testTag("reader-display-picker").controllerFocusRing(DisplayControlShape),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("阅读模式 · ${mode.label}", style = MaterialTheme.typography.titleMedium)
            Text("翻页动画 · ${(animations[mode] ?: mode.defaultAnimation).label}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Text("更换")
    }
}

/** Both mode and animation are immediately saved; every mode keeps its own animation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderDisplayPicker(
    readingMode: ReadingMode,
    pageAnimations: Map<ReadingMode, PageTurnAnimation>,
    onReadingMode: (ReadingMode) -> Unit,
    onPageAnimation: (ReadingMode, PageTurnAnimation) -> Unit,
    backFocus: FocusRequester,
    isPdf: Boolean,
) {
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = readingMode.ordinal)
    Row(Modifier.fillMaxSize().testTag("reader-display-options"), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        LazyColumn(Modifier.weight(0.42f).testTag("reading-mode-list"), state = scroll, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ReadingMode.entries, key = { it.id }) { mode ->
                OutlinedButton(
                    onClick = { onReadingMode(mode) },
                    shape = DisplayControlShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("reading-mode-${mode.id}")
                        .focusProperties { if (mode == ReadingMode.Single) up = backFocus }.controllerFocusRing(DisplayControlShape)
                        .semantics { selected = mode == readingMode; role = Role.RadioButton },
                ) {
                    RadioButton(selected = mode == readingMode, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(mode.label, Modifier.weight(1f))
                }
            }
        }
        Column(
            Modifier.weight(0.58f).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(readingMode.label, style = MaterialTheme.typography.headlineSmall)
            Text(readingMode.description, modifier = Modifier.testTag("reading-mode-description"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isPdf && readingMode == ReadingMode.DualColumn) {
                Text("PDF 保留原始版面，双列并排显示连续两页；TXT / EPUB 按正文连续分栏。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${readingMode.label}的翻页动画", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageTurnAnimation.entries.forEach { animation ->
                    FilterChip(
                        selected = (pageAnimations[readingMode] ?: readingMode.defaultAnimation) == animation,
                        onClick = { onPageAnimation(readingMode, animation) },
                        label = { Text(animation.label) }, shape = DisplayControlShape,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("page-animation-${animation.id}")
                            .controllerFocusRing(DisplayControlShape),
                    )
                }
            }
            Text("每种模式分别保存动画，切换模式后保持当前阅读段落。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
