// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.i18n

object BidiHelper {
    /**
     * Wraps mixed-direction content (e.g. Latin phone numbers inside Persian text)
     * with Unicode bidi isolates \u2068/\u2069 to prevent display corruption.
     * Pure JVM implementation — no Android BidiFormatter needed for unit tests.
     * For UI, Compose RTL via LocalLayoutDirection handles layout; this is for text isolates.
     */
    fun wrap(text: String): String = "\u2068$text\u2069"

    fun isRtlContext(localeTag: String): Boolean = LocaleHelper.isRtl(localeTag)
}
