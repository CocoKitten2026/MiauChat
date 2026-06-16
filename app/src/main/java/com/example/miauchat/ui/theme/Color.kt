package com.example.miauchat.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val ColorBackground = Color(0xFF000000)
private val ColorSurfaceDark = Color(0xFF161616)
private val ColorSurfaceVariantDark = Color(0xFF1E1E1E)
private val ColorAccentBlue = Color(0xFF3B99FC)
private val ColorAccentAmber = Color(0xFFFFB244)
private val ColorTextPrimary = Color(0xFFFFFFFF)
private val ColorTextMuted = Color(0xFF888888)
private val ColorModalOverlay = Color(0xCC000000)
private val ColorBorderDim = Color(0xFF222222)
private val ColorError = Color(0xFFCF6679)

// Light theme — keep the terminal aesthetic inverted or as forced-dark
private val LightBackground = Color(0xFFF5F5F5)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFE8E8E8)
private val LightPrimary = Color(0xFF1A73E8)
private val LightSecondary = Color(0xFFE8910A)
private val LightTextPrimary = Color(0xFF1F1F1F)
private val LightTextMuted = Color(0xFF6E6E6E)
private val LightBorderDim = Color(0xFFD0D0D0)
private val LightOverlay = Color(0x33000000)
private val LightError = Color(0xFFB3261E)

fun terminalDarkColorScheme() = darkColorScheme(
    primary = ColorAccentBlue,
    onPrimary = ColorBackground,
    secondary = ColorAccentAmber,
    onSecondary = ColorBackground,
    tertiary = ColorTextMuted,
    onTertiary = ColorBackground,
    background = ColorBackground,
    onBackground = ColorTextPrimary,
    surface = ColorSurfaceDark,
    onSurface = ColorTextPrimary,
    surfaceVariant = ColorSurfaceVariantDark,
    onSurfaceVariant = ColorTextMuted,
    outline = ColorBorderDim,
    outlineVariant = ColorBorderDim,
    scrim = ColorModalOverlay,
    error = ColorError,
    onError = ColorBackground
)

fun terminalLightColorScheme() = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightSurface,
    secondary = LightSecondary,
    onSecondary = LightSurface,
    tertiary = LightTextMuted,
    onTertiary = LightSurface,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextMuted,
    outline = LightBorderDim,
    outlineVariant = LightBorderDim,
    scrim = LightOverlay,
    error = LightError,
    onError = LightSurface
)
