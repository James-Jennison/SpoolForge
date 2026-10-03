package net.jamesjennison.filamajignfc

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Forest green on warm cream, with amber for work in progress. The palette is deliberately quiet:
// filament swatches are the strongest color on any screen, and nothing in the interface should resemble one.
private val LightColors = lightColorScheme(
    primary = Color(0xFF286354), onPrimary = Color(0xFFFFFCF5),
    primaryContainer = Color(0xFFCFE6DC), onPrimaryContainer = Color(0xFF0B2F26),
    secondary = Color(0xFF4E635B), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6EDE6), onSecondaryContainer = Color(0xFF1E2A26),
    tertiary = Color(0xFF8A5A00), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBE8C6), onTertiaryContainer = Color(0xFF5A3A06),
    error = Color(0xFF9B2C2C), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF5C1A1A),
    background = Color(0xFFF8F6F0), onBackground = Color(0xFF1E2A26),
    surface = Color(0xFFFFFCF5), onSurface = Color(0xFF1E2A26),
    surfaceVariant = Color(0xFFE3E6DE), onSurfaceVariant = Color(0xFF55625D),
    surfaceTint = Color(0xFF286354),
    outline = Color(0xFF7C8B84), outlineVariant = Color(0xFFCBD2CB),
    inverseSurface = Color(0xFF2C3632), inverseOnSurface = Color(0xFFEEF2EE), inversePrimary = Color(0xFF7FCDB6),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF4F2EA), surfaceContainer = Color(0xFFEFEEE6),
    surfaceContainerHigh = Color(0xFFEAE9E1), surfaceContainerHighest = Color(0xFFE5E5DC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FCDB6), onPrimary = Color(0xFF06302A),
    primaryContainer = Color(0xFF1F4B40), onPrimaryContainer = Color(0xFFBDE9DA),
    secondary = Color(0xFFB3C7BE), onSecondary = Color(0xFF1F332C),
    secondaryContainer = Color(0xFF1F2925), onSecondaryContainer = Color(0xFFD5E3DC),
    tertiary = Color(0xFFF2B45A), onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF4A3410), onTertiaryContainer = Color(0xFFFBE0AE),
    error = Color(0xFFF2B8B5), onError = Color(0xFF5C1A1A),
    errorContainer = Color(0xFF7A2626), onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF141A18), onBackground = Color(0xFFE6ECE8),
    surface = Color(0xFF171E1B), onSurface = Color(0xFFE6ECE8),
    surfaceVariant = Color(0xFF2C3632), onSurfaceVariant = Color(0xFF9FB0A9),
    surfaceTint = Color(0xFF7FCDB6),
    outline = Color(0xFF6E7F78), outlineVariant = Color(0xFF3A4541),
    inverseSurface = Color(0xFFE6ECE8), inverseOnSurface = Color(0xFF2C3632), inversePrimary = Color(0xFF286354),
    surfaceContainerLowest = Color(0xFF0F1412), surfaceContainerLow = Color(0xFF1A211E), surfaceContainer = Color(0xFF1D2522),
    surfaceContainerHigh = Color(0xFF242D29), surfaceContainerHighest = Color(0xFF2B3531),
)

@Composable internal fun FilamajigTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = Typography(), content = content)
}
