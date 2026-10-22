package io.github.odinreader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D6950),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E9D9),
    onPrimaryContainer = Color(0xFF163C27),
    secondary = Color(0xFF536456),
    background = Color(0xFFF8F9F4),
    surface = Color(0xFFF8F9F4),
    surfaceVariant = Color(0xFFE1E6DD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA0D2AE),
    onPrimary = Color(0xFF123722),
    primaryContainer = Color(0xFF244D35),
    onPrimaryContainer = Color(0xFFD5E9D9),
    background = Color(0xFF111612),
    onBackground = Color(0xFFE0E5DD),
    surface = Color(0xFF111612),
    onSurface = Color(0xFFE0E5DD),
)

@Composable
fun OdinReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
