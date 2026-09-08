package com.nospam.nospam.core.telephony

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

object PhoneNumberNormalizer {
    fun normalize(context: Context, raw: String): String {
        val trimmed = raw.trim()
        // Alphanumeric sender IDs (e.g. "Snapp") — keep as upper-cased raw.
        if (trimmed.any { it.isLetter() }) return trimmed.uppercase(Locale.ROOT)
        val countryIso = getCountryIso(context)
        val e164 = try {
            PhoneNumberUtils.formatNumberToE164(trimmed, countryIso)
        } catch (_: Exception) { null }
        return e164 ?: trimmed
    }

    fun normalizedVariants(context: Context, raw: String): List<String> {
        val trimmed = raw.trim()
        val normalized = normalize(context, trimmed)
        return if (normalized == trimmed) listOf(trimmed)
        else listOf(trimmed, normalized, normalized.uppercase(Locale.ROOT))
    }

    private fun getCountryIso(context: Context): String {
        return try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            val network = tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            val sim = tm?.simCountryIso?.takeIf { it.isNotBlank() }
            (network ?: sim ?: Locale.getDefault().country).uppercase(Locale.ROOT)
        } catch (_: Exception) {
            Locale.getDefault().country
        }
    }

    /** Pure version for tests (no Context) — falls back to raw upper-cased. */
    fun normalizeForTest(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.any { it.isLetter() }) return trimmed.uppercase(Locale.ROOT)
        return try {
            PhoneNumberUtils.formatNumberToE164(trimmed, Locale.getDefault().country) ?: trimmed
        } catch (_: Exception) { trimmed }
    }
}
