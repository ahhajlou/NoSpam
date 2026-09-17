package com.nospam.nospam.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Light tokens — direct from adaptive_messenger/DESIGN.md via CLAUDE.md §8
val md_light_primary = Color(0xFF005BBF)
val md_light_onPrimary = Color(0xFFFFFFFF)
val md_light_primaryContainer = Color(0xFF1A73E8)
val md_light_onPrimaryContainer = Color(0xFFFFFFFF)
val md_light_secondary = Color(0xFF575F6B)
val md_light_onSecondary = Color(0xFFFFFFFF)
val md_light_secondaryContainer = Color(0xFFDBE3F1)
val md_light_onSecondaryContainer = Color(0xFF5D6571)
val md_light_tertiary = Color(0xFF195ABC)
val md_light_onTertiary = Color(0xFFFFFFFF)
val md_light_tertiaryContainer = Color(0xFF3D74D7)
val md_light_onTertiaryContainer = Color(0xFF000414)
val md_light_error = Color(0xFFBA1A1A)
val md_light_onError = Color(0xFFFFFFFF)
val md_light_errorContainer = Color(0xFFFFDAD6)
val md_light_onErrorContainer = Color(0xFF93000A)
val md_light_background = Color(0xFFF8F9FA)
val md_light_onBackground = Color(0xFF191C1D)
val md_light_surface = Color(0xFFF8F9FA)
val md_light_onSurface = Color(0xFF191C1D)
val md_light_surfaceVariant = Color(0xFFE1E3E4)
val md_light_onSurfaceVariant = Color(0xFF414754)
val md_light_outline = Color(0xFF727785)
val md_light_outlineVariant = Color(0xFFC1C6D6)
val md_light_inverseSurface = Color(0xFF2E3132)
val md_light_inverseOnSurface = Color(0xFFF0F1F2)
val md_light_inversePrimary = Color(0xFFADC7FF)
val md_light_surfaceDim = Color(0xFFD9DADB)
val md_light_surfaceBright = Color(0xFFF8F9FA)
val md_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_light_surfaceContainerLow = Color(0xFFF3F4F5)
val md_light_surfaceContainer = Color(0xFFEDEEEF)
val md_light_surfaceContainerHigh = Color(0xFFE7E8E9)
val md_light_surfaceContainerHighest = Color(0xFFE1E3E4)

// Fixed accent variants from DESIGN.md
val md_light_primaryFixed = Color(0xFFD8E2FF)
val md_light_primaryFixedDim = Color(0xFFADC7FF)
val md_light_onPrimaryFixed = Color(0xFF001A41)
val md_light_onPrimaryFixedVariant = Color(0xFF004493)
val md_light_secondaryFixed = Color(0xFFDBE3F1)
val md_light_secondaryFixedDim = Color(0xFFBFC7D4)
val md_light_onSecondaryFixed = Color(0xFF141C26)
val md_light_onSecondaryFixedVariant = Color(0xFF3F4752)
val md_light_tertiaryFixed = Color(0xFFD8E2FF)
val md_light_tertiaryFixedDim = Color(0xFFAEC6FF)
val md_light_onTertiaryFixed = Color(0xFF001A43)
val md_light_onTertiaryFixedVariant = Color(0xFF004397)
val md_light_surfaceTint = Color(0xFF005BC0)

val LightColors = lightColorScheme(
    primary = md_light_primary, onPrimary = md_light_onPrimary,
    primaryContainer = md_light_primaryContainer, onPrimaryContainer = md_light_onPrimaryContainer,
    secondary = md_light_secondary, onSecondary = md_light_onSecondary,
    secondaryContainer = md_light_secondaryContainer, onSecondaryContainer = md_light_onSecondaryContainer,
    tertiary = md_light_tertiary, onTertiary = md_light_onTertiary,
    tertiaryContainer = md_light_tertiaryContainer, onTertiaryContainer = md_light_onTertiaryContainer,
    error = md_light_error, onError = md_light_onError,
    errorContainer = md_light_errorContainer, onErrorContainer = md_light_onErrorContainer,
    background = md_light_background, onBackground = md_light_onBackground,
    surface = md_light_surface, onSurface = md_light_onSurface,
    surfaceVariant = md_light_surfaceVariant, onSurfaceVariant = md_light_onSurfaceVariant,
    outline = md_light_outline, outlineVariant = md_light_outlineVariant,
    inverseSurface = md_light_inverseSurface, inverseOnSurface = md_light_inverseOnSurface,
    inversePrimary = md_light_inversePrimary,
    surfaceDim = md_light_surfaceDim, surfaceBright = md_light_surfaceBright,
    surfaceContainerLowest = md_light_surfaceContainerLowest,
    surfaceContainerLow = md_light_surfaceContainerLow,
    surfaceContainer = md_light_surfaceContainer,
    surfaceContainerHigh = md_light_surfaceContainerHigh,
    surfaceContainerHighest = md_light_surfaceContainerHighest,
    surfaceTint = md_light_surfaceTint,
)

// Dark scheme — hand-tuned from same seed, not inverted light (open item per CLAUDE.md §8)
// Neutral tones follow the M3 dark mapping: surface/background/surfaceDim at tone 6,
// containers lowest→highest at 4/10/12/17/22, surfaceBright 24, inverseOnSurface 20.
// Surface previously sat at tone 10, identical to surfaceContainerLow, so every
// container role was nearly indistinguishable from the page in dark mode.
val md_dark_primary = Color(0xFFADC7FF)
val md_dark_onPrimary = Color(0xFF002F65)
val md_dark_primaryContainer = Color(0xFF004493)
val md_dark_onPrimaryContainer = Color(0xFFD8E2FF)
val md_dark_secondary = Color(0xFFBFC7D4)
val md_dark_onSecondary = Color(0xFF29303B)
val md_dark_secondaryContainer = Color(0xFF3F4752)
val md_dark_onSecondaryContainer = Color(0xFFDBE3F1)
val md_dark_tertiary = Color(0xFFAEC6FF)
val md_dark_onTertiary = Color(0xFF002A5E)
val md_dark_tertiaryContainer = Color(0xFF004397)
val md_dark_onTertiaryContainer = Color(0xFFD8E2FF)
val md_dark_error = Color(0xFFFFB4AB)
val md_dark_onError = Color(0xFF690005)
val md_dark_errorContainer = Color(0xFF93000A)
val md_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_dark_background = Color(0xFF111415)
val md_dark_onBackground = Color(0xFFE1E3E4)
val md_dark_surface = Color(0xFF111415)
val md_dark_onSurface = Color(0xFFE1E3E4)
val md_dark_surfaceVariant = Color(0xFF414754)
val md_dark_onSurfaceVariant = Color(0xFFC1C6D6)
val md_dark_outline = Color(0xFF8B90A0)
val md_dark_outlineVariant = Color(0xFF414754)
val md_dark_inverseSurface = Color(0xFFE1E3E4)
val md_dark_inverseOnSurface = Color(0xFF2E3132)
val md_dark_inversePrimary = Color(0xFF005BBF)
val md_dark_surfaceDim = Color(0xFF111415)
val md_dark_surfaceBright = Color(0xFF373A3B)
val md_dark_surfaceContainerLowest = Color(0xFF0F1112)
val md_dark_surfaceContainerLow = Color(0xFF191C1D)
val md_dark_surfaceContainer = Color(0xFF1D1F20)
val md_dark_surfaceContainerHigh = Color(0xFF272A2B)
val md_dark_surfaceContainerHighest = Color(0xFF323536)
val md_dark_surfaceTint = Color(0xFFADC7FF)

val DarkColors = darkColorScheme(
    primary = md_dark_primary, onPrimary = md_dark_onPrimary,
    primaryContainer = md_dark_primaryContainer, onPrimaryContainer = md_dark_onPrimaryContainer,
    secondary = md_dark_secondary, onSecondary = md_dark_onSecondary,
    secondaryContainer = md_dark_secondaryContainer, onSecondaryContainer = md_dark_onSecondaryContainer,
    tertiary = md_dark_tertiary, onTertiary = md_dark_onTertiary,
    tertiaryContainer = md_dark_tertiaryContainer, onTertiaryContainer = md_dark_onTertiaryContainer,
    error = md_dark_error, onError = md_dark_onError,
    errorContainer = md_dark_errorContainer, onErrorContainer = md_dark_onErrorContainer,
    background = md_dark_background, onBackground = md_dark_onBackground,
    surface = md_dark_surface, onSurface = md_dark_onSurface,
    surfaceVariant = md_dark_surfaceVariant, onSurfaceVariant = md_dark_onSurfaceVariant,
    outline = md_dark_outline, outlineVariant = md_dark_outlineVariant,
    inverseSurface = md_dark_inverseSurface, inverseOnSurface = md_dark_inverseOnSurface,
    inversePrimary = md_dark_inversePrimary,
    surfaceDim = md_dark_surfaceDim, surfaceBright = md_dark_surfaceBright,
    surfaceContainerLowest = md_dark_surfaceContainerLowest,
    surfaceContainerLow = md_dark_surfaceContainerLow,
    surfaceContainer = md_dark_surfaceContainer,
    surfaceContainerHigh = md_dark_surfaceContainerHigh,
    surfaceContainerHighest = md_dark_surfaceContainerHighest,
    surfaceTint = md_dark_surfaceTint,
)
