// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Wallpaper-based color needs Android 12 (API 31). */
val isDynamicColorSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * App theme. The brand scheme is the default; [dynamicColor] opts in to
 * Material You and is ignored below Android 12, where the brand scheme is used.
 *
 * Wrap once, at the top of the app. Screens must not wrap themselves again:
 * a nested theme silently resets whatever the outer one decided.
 *
 * [darkTheme] defaults to the configuration's night mode, which is the user's
 * light/dark choice: `:app` applies it through `AppCompatDelegate`, so the
 * window, the system bars and AppCompat views follow it too.
 */
@Composable
fun NoSpamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && isDynamicColorSupported -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = NoSpamTypography,
        shapes = NoSpamShapes,
        content = content
    )
}
