package io.github.odinreader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Catppuccin palette v1.8.0, MIT licensed. See licenses/Catppuccin-LICENSE.txt.
// https://github.com/catppuccin/palette/tree/v1.8.0
private data class CatppuccinPalette(
    val dark: Boolean,
    val mauve: Color,
    val blue: Color,
    val teal: Color,
    val red: Color,
    val text: Color,
    val subtext1: Color,
    val overlay1: Color,
    val overlay2: Color,
    val surface0: Color,
    val surface1: Color,
    val base: Color,
    val mantle: Color,
    val crust: Color,
)

private val LattePalette = CatppuccinPalette(
    dark = false,
    mauve = Color(0xFF8839EF),
    blue = Color(0xFF1E66F5),
    teal = Color(0xFF179299),
    red = Color(0xFFD20F39),
    text = Color(0xFF4C4F69),
    subtext1 = Color(0xFF5C5F77),
    overlay1 = Color(0xFF8C8FA1),
    overlay2 = Color(0xFF7C7F93),
    surface0 = Color(0xFFCCD0DA),
    surface1 = Color(0xFFBCC0CC),
    base = Color(0xFFEFF1F5),
    mantle = Color(0xFFE6E9EF),
    crust = Color(0xFFDCE0E8),
)

private val FrappePalette = CatppuccinPalette(
    dark = true,
    mauve = Color(0xFFCA9EE6),
    blue = Color(0xFF8CAAEE),
    teal = Color(0xFF81C8BE),
    red = Color(0xFFE78284),
    text = Color(0xFFC6D0F5),
    subtext1 = Color(0xFFB5BFE2),
    overlay1 = Color(0xFF838BA7),
    overlay2 = Color(0xFF949CBB),
    surface0 = Color(0xFF414559),
    surface1 = Color(0xFF51576D),
    base = Color(0xFF303446),
    mantle = Color(0xFF292C3C),
    crust = Color(0xFF232634),
)

private val MacchiatoPalette = CatppuccinPalette(
    dark = true,
    mauve = Color(0xFFC6A0F6),
    blue = Color(0xFF8AADF4),
    teal = Color(0xFF8BD5CA),
    red = Color(0xFFED8796),
    text = Color(0xFFCAD3F5),
    subtext1 = Color(0xFFB8C0E0),
    overlay1 = Color(0xFF8087A2),
    overlay2 = Color(0xFF939AB7),
    surface0 = Color(0xFF363A4F),
    surface1 = Color(0xFF494D64),
    base = Color(0xFF24273A),
    mantle = Color(0xFF1E2030),
    crust = Color(0xFF181926),
)

private val MochaPalette = CatppuccinPalette(
    dark = true,
    mauve = Color(0xFFCBA6F7),
    blue = Color(0xFF89B4FA),
    teal = Color(0xFF94E2D5),
    red = Color(0xFFF38BA8),
    text = Color(0xFFCDD6F4),
    subtext1 = Color(0xFFBAC2DE),
    overlay1 = Color(0xFF7F849C),
    overlay2 = Color(0xFF9399B2),
    surface0 = Color(0xFF313244),
    surface1 = Color(0xFF45475A),
    base = Color(0xFF1E1E2E),
    mantle = Color(0xFF181825),
    crust = Color(0xFF11111B),
)

private fun CatppuccinPalette.colorScheme(): ColorScheme {
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    // Latte's pale blue/teal accents do not provide text contrast on its base.
    // Use its official neutral tokens for those roles; mauve remains the accent.
    val secondaryAccent = if (dark) blue else text
    val tertiaryAccent = if (dark) teal else subtext1
    return scheme.copy(
        primary = mauve,
        onPrimary = base,
        primaryContainer = surface0,
        onPrimaryContainer = text,
        inversePrimary = base,
        secondary = secondaryAccent,
        onSecondary = base,
        secondaryContainer = mantle,
        onSecondaryContainer = text,
        tertiary = tertiaryAccent,
        onTertiary = base,
        tertiaryContainer = crust,
        onTertiaryContainer = text,
        background = base,
        onBackground = text,
        surface = base,
        onSurface = text,
        surfaceVariant = surface0,
        onSurfaceVariant = if (dark) subtext1 else text,
        surfaceTint = mauve,
        inverseSurface = text,
        inverseOnSurface = base,
        error = red,
        onError = base,
        errorContainer = crust,
        onErrorContainer = text,
        outline = overlay2,
        outlineVariant = overlay1,
        scrim = Color.Black,
        surfaceBright = surface0,
        surfaceDim = crust,
        surfaceContainer = mantle,
        surfaceContainerLow = base,
        surfaceContainerLowest = crust,
        surfaceContainerHigh = if (dark) surface0 else crust,
        surfaceContainerHighest = if (dark) surface1 else surface0,
        primaryFixed = surface0,
        primaryFixedDim = crust,
        onPrimaryFixed = text,
        onPrimaryFixedVariant = if (dark) subtext1 else text,
        secondaryFixed = mantle,
        secondaryFixedDim = crust,
        onSecondaryFixed = text,
        onSecondaryFixedVariant = subtext1,
        tertiaryFixed = mantle,
        tertiaryFixedDim = crust,
        onTertiaryFixed = text,
        onTertiaryFixedVariant = subtext1,
    )
}

private val LatteColors = LattePalette.colorScheme()
private val FrappeColors = FrappePalette.colorScheme()
private val MacchiatoColors = MacchiatoPalette.colorScheme()
private val MochaColors = MochaPalette.colorScheme()

/** Also supplies the exact colors shown by the theme picker, without changing global state. */
fun catppuccinColorScheme(theme: AppTheme, systemDark: Boolean = false): ColorScheme = when (theme) {
    AppTheme.System -> if (systemDark) MochaColors else LatteColors
    AppTheme.Latte -> LatteColors
    AppTheme.Frappe -> FrappeColors
    AppTheme.Macchiato -> MacchiatoColors
    AppTheme.Mocha -> MochaColors
}

@Composable
fun OdinReaderTheme(theme: AppTheme = AppTheme.System, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = catppuccinColorScheme(theme, isSystemInDarkTheme()), content = content)
}
