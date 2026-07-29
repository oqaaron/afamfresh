package com.techaus.afamfresh.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// This file was missing — I created Color.kt earlier but never the theme
// wrapper that MainActivity imports as com.techaus.afamfresh.ui.theme.AfamfreshTheme.

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = ForestSurface,
    onPrimaryContainer = Forest,
    secondary = ForestLight,
    onSecondary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = CardWhite,
    onSurface = Ink,
    surfaceVariant = PillGray,
    onSurfaceVariant = InkMuted,
    error = Tomato,
    onError = Color.White,
    outline = DividerGray
)

// ⚠️ Dark mode is defined but NOT well-tested — most screens in this app
// hardcode colors (Cream backgrounds, Ink text) rather than reading from
// MaterialTheme.colorScheme, so enabling dark mode would currently produce
// an inconsistent result. `useDarkTheme` defaults to false for that reason.
// If you want real dark mode support, the screens need to be migrated to
// MaterialTheme.colorScheme.* first.
private val DarkColors = darkColorScheme(
    primary = ForestLight,
    onPrimary = Color.Black,
    background = Color(0xFF121212),
    onBackground = Color(0xFFEAEAEA),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFEAEAEA),
    error = Tomato
)

@Composable
fun AfamfreshTheme(
    useDarkTheme: Boolean = false, // deliberately not isSystemInDarkTheme() — see note above
    content: @Composable () -> Unit
) {
    val colorScheme = if (useDarkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.primary.toArgb()
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
