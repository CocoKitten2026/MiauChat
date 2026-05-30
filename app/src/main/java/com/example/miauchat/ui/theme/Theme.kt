package com.example.miauchat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TerminalColorScheme = darkColorScheme(
    primary = ColorAccentBlue,
    secondary = ColorAccentAmber,
    tertiary = ColorTextMuted,
    background = ColorBackground,
    surface = ColorSurfaceDark,
    onPrimary = ColorBackground,
    onSecondary = ColorBackground,
    onTertiary = ColorBackground,
    onBackground = ColorTextPrimary,
    onSurface = ColorTextPrimary,
    outline = ColorBorderDim
)

@Composable
fun MiauChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TerminalColorScheme,
        typography = Typography,
        content = content
    )
}
