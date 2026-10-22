package io.github.odinreader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatppuccinThemeTest {
    @Test
    fun fourFlavorsPreserveOfficialBackgroundTextAndMauveColors() {
        val expected = listOf(
            Triple(AppTheme.Latte, 0xFFEFF1F5, 0xFF4C4F69),
            Triple(AppTheme.Frappe, 0xFF303446, 0xFFC6D0F5),
            Triple(AppTheme.Macchiato, 0xFF24273A, 0xFFCAD3F5),
            Triple(AppTheme.Mocha, 0xFF1E1E2E, 0xFFCDD6F4),
        )
        val mauve = listOf(0xFF8839EF, 0xFFCA9EE6, 0xFFC6A0F6, 0xFFCBA6F7)
        expected.forEachIndexed { index, (theme, background, text) ->
            val scheme = catppuccinColorScheme(theme)
            assertEquals(theme.label, Color(background), scheme.background)
            assertEquals(theme.label, Color(text), scheme.onBackground)
            assertEquals(theme.label, Color(mauve[index]), scheme.primary)
        }
    }

    @Test
    fun systemUsesLatteAndMochaWhileExplicitChoicesIgnoreSystemMode() {
        assertSame(catppuccinColorScheme(AppTheme.Latte), catppuccinColorScheme(AppTheme.System, false))
        assertSame(catppuccinColorScheme(AppTheme.Mocha), catppuccinColorScheme(AppTheme.System, true))
        AppTheme.entries.filter { it != AppTheme.System }.forEach { theme ->
            assertSame(catppuccinColorScheme(theme, false), catppuccinColorScheme(theme, true))
        }
    }

    @Test
    fun textAndInteractiveColorPairsMeetNormalTextContrastInEveryFlavor() {
        AppTheme.entries.filter { it != AppTheme.System }.forEach { theme ->
            val scheme = catppuccinColorScheme(theme)
            val pairs = listOf(
                "body" to (scheme.onBackground to scheme.background),
                "surface" to (scheme.onSurface to scheme.surface),
                "surface variant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
                "raised surface" to (scheme.onSurface to scheme.surfaceContainerHighest),
                "primary button" to (scheme.onPrimary to scheme.primary),
                "primary chip" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "secondary button" to (scheme.onSecondary to scheme.secondary),
                "secondary chip" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "tertiary button" to (scheme.onTertiary to scheme.tertiary),
                "tertiary chip" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                "error" to (scheme.onError to scheme.error),
                "error container" to (scheme.onErrorContainer to scheme.errorContainer),
                "inverse surface" to (scheme.inverseOnSurface to scheme.inverseSurface),
            )
            pairs.forEach { (role, colors) ->
                val ratio = contrast(colors.first, colors.second)
                assertTrue("${theme.label} $role contrast is $ratio; expected at least 4.5:1", ratio >= 4.5f)
            }
        }
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val a = foreground.luminance()
        val b = background.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
}
