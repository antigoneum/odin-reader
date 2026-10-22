package io.github.odinreader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Count only a rendered book in a resumed, focused window; menus and loading pause it. */
@Composable
internal fun ReadingActivityEffect(
    bookId: String?,
    bodyVisible: Boolean,
    onActiveChanged: (Boolean) -> Unit,
    onTick: () -> Unit,
) {
    val focused = LocalWindowInfo.current.isWindowFocused
    val owner = LocalLifecycleOwner.current
    val active = bookId != null && bodyVisible && focused
    val latestActiveChanged by rememberUpdatedState(onActiveChanged)
    val latestTick by rememberUpdatedState(onTick)
    LifecycleResumeEffect(bookId, active) {
        latestActiveChanged(active)
        onPauseOrDispose { latestActiveChanged(false) }
    }
    LaunchedEffect(bookId, active, owner) {
        if (active) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                delay(1_000)
                latestTick()
            }
        }
    }
}
