package io.github.odinreader.ui

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Apply before a clickable control's focus target and use the same [shape] on
 * that control. The border draws inside the measured surface, without adding
 * a second touch-target outline or changing its layout when focus changes.
 */
@Composable
internal fun Modifier.controllerFocusRing(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    return onFocusChanged { focused = it.isFocused }.border(3.dp, color, shape)
}