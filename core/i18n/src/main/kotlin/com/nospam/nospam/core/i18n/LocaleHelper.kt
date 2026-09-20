// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object LocaleHelper {
    private val _currentLocale = MutableStateFlow(currentLocaleTag())

    val currentLocale: StateFlow<String> = _currentLocale

    fun setLocale(tag: String) {
        val localeList = LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(localeList)
        _currentLocale.value = tag
    }

    fun clearToSystemDefault() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        _currentLocale.value = SELECTED_SYSTEM
    }

    fun currentLocaleTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) "en" else locales.get(0)?.toLanguageTag() ?: "en"
    }

    /** Returns "system" when following system, otherwise the BCP-47 tag. */
    fun selectedOption(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) SELECTED_SYSTEM else locales.get(0)?.toLanguageTag() ?: "en"
    }

    const val SELECTED_SYSTEM = "system"

    fun isRtl(tag: String = currentLocaleTag()): Boolean {
        return tag.startsWith("fa") || tag.startsWith("ar") || tag.startsWith("he") || tag.startsWith("ur")
    }
}
