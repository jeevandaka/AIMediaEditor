package com.aimediaeditor.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AccentPink = Color(0xFFE94560)
private val DarkBackground = Color(0xFF16213E)
private val DarkSurface = Color(0xFF1A1A2E)

private val DarkColors = darkColorScheme(
    primary = AccentPink,
    background = DarkBackground,
    surface = DarkSurface
)

private val LightColors = lightColorScheme(
    primary = AccentPink
)

/**
 * Section 28 of the spec calls for a dark-first editing interface.
 * We default to dark regardless of system setting; wire this to a
 * real setting once there's a settings screen.
 */
@Composable
fun AIMediaEditorTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
