package io.github.odinreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import io.github.odinreader.R

internal enum class HomeTab(val label: String) {
    Library("书库"), Settings("设置"), Help("操作指南");

    fun shifted(direction: Int): HomeTab = entries[(ordinal + direction + entries.size) % entries.size]
}

/** Shoulder buttons activate pages; D-pad focuses the horizontal tabs before A confirms. */
@Composable
internal fun HomeMenu(
    selected: HomeTab,
    enabled: Boolean,
    requesters: List<FocusRequester>,
    contentFocus: FocusRequester,
    onSelect: (HomeTab) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(R.drawable.ic_launcher), null, Modifier.size(30.dp), tint = Color.Unspecified)
                Text("ODIN", style = MaterialTheme.typography.titleMedium)
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text("L1", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
                }
                HomeTab.entries.forEachIndexed { index, tab ->
                    val active = selected == tab
                    OutlinedButton(
                        onClick = { onSelect(tab) },
                        enabled = enabled,
                        shape = shape,
                        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("home-tab-${tab.name}")
                            .semantics { this.selected = active }
                            .focusRequester(requesters[index])
                            .focusProperties {
                                left = requesters[(index + requesters.size - 1) % requesters.size]
                                right = requesters[(index + 1) % requesters.size]
                                down = contentFocus
                            }
                            .controllerFocusRing(shape),
                    ) {
                        Icon(
                            when (tab) {
                                HomeTab.Library -> Icons.Default.Menu
                                HomeTab.Settings -> Icons.Default.Settings
                                HomeTab.Help -> Icons.Default.Info
                            },
                            null, Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(tab.label, maxLines = 1)
                    }
                }
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text("R1", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
