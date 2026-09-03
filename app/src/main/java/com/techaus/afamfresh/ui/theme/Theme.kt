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

private val DarkColors = darkColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = CardDark,
    onPrimaryContainer = ForestLight,
    secondary = ForestLight,
    onSecondary = Color.Black,
    background = BackgroundDark,
    onBackground = InkDark,
    surface = CardDark,
    onSurface = InkDark,
    surfaceVariant = DividerGrayDark,
    onSurfaceVariant = InkMutedDark,
    error = Tomato,
    onError = Color.White,
    outline = DividerGrayDark
)

@Composable
fun AfamfreshTheme(
    useDarkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (useDarkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                // Pin status bar directly to the vibrant top green (#1EB85A)
                window.statusBarColor = Forest.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}