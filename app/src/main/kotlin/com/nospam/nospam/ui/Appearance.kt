// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.ui

import androidx.appcompat.app.AppCompatDelegate
import com.nospam.nospam.core.model.ThemeSetting

/**
 * The `AppCompatDelegate` night mode for a [ThemeSetting]. Night mode rather
 * than a flag passed to Compose, because it also reaches what Compose does not
 * draw: the window background, the system bar icons (`enableEdgeToEdge`'s auto
 * style reads the configuration) and AppCompat views such as the emoji picker.
 */
fun ThemeSetting.toNightMode(): Int = when (this) {
    ThemeSetting.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    ThemeSetting.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    ThemeSetting.DARK -> AppCompatDelegate.MODE_NIGHT_YES
}
