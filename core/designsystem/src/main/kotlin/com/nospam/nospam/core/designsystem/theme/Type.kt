package com.nospam.nospam.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// DESIGN.md: Hanken Grotesk for headlines, Inter for body/label.
// Until Google Fonts provider XMLs are added (Inter/Hanken + Vazirmatn for fa),
// use Default which maps to Roboto/Inter fallback; structure ready for replacement.
val HankenGroteskFamily = FontFamily.Default
val InterFamily = FontFamily.Default
val VazirmatnFamily = FontFamily.Default // Persian — swap to downloadable Vazirmatn when added

/**
 * All fifteen Material 3 type roles, defined explicitly so no screen falls back
 * to a library default with a different family or tracking.
 *
 * Where DESIGN.md names a token its values win; every other role uses the M3
 * baseline scale. DESIGN.md's `label-lg` (12/16, 500) is M3's `labelMedium`, not
 * `labelLarge`: `labelLarge` is the button, tab and chip role and is 14/20 in
 * the spec. It was previously mapped to `labelLarge`, which shrank every button
 * label to 12sp.
 */
val NoSpamTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = HankenGroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = HankenGroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = HankenGroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp
    ),
    // DESIGN.md headline-lg
    headlineLarge = TextStyle(
        fontFamily = HankenGroteskFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp
    ),
    // DESIGN.md headline-md
    headlineMedium = TextStyle(
        fontFamily = HankenGroteskFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = HankenGroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = HankenGroteskFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),
    // DESIGN.md body-lg
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp
    ),
    // DESIGN.md body-md
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),
    // DESIGN.md label-lg
    labelMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp
    ),
)
