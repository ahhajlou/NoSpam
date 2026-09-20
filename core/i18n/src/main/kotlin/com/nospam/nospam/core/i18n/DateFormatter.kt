// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.i18n

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateFormatter {
    // Gregorian is default; Jalali behind feature flag pending decision (see CLAUDE.md §10)
    fun formatRelative(dateMillis: Long, localeTag: String = LocaleHelper.currentLocaleTag()): String {
        val locale = Locale.forLanguageTag(localeTag)
        val now = System.currentTimeMillis()
        val diff = now - dateMillis
        return when {
            diff < 60_000 -> "now"
            diff < 3600_000 -> "${diff / 60000}m"
            diff < 86400_000 -> SimpleDateFormat("HH:mm", locale).format(Date(dateMillis))
            diff < 604800_000 -> SimpleDateFormat("EEE", locale).format(Date(dateMillis))
            else -> SimpleDateFormat("MMM d", locale).format(Date(dateMillis))
        }
    }

    fun formatTime(dateMillis: Long, localeTag: String = LocaleHelper.currentLocaleTag()): String {
        val locale = Locale.forLanguageTag(localeTag)
        return SimpleDateFormat("HH:mm", locale).format(Date(dateMillis))
    }
}
