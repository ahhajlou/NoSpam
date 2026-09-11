package com.nospam.nospam.core.telephony

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object PhoneNumberNormalizer {
    /**
     * Normalized forms, keyed by the trimmed raw address.
     *
     * ConversationsRepository normalizes the same address several times per
     * emission (withFlags, applyFilter and the blocklist check each call it),
     * and a trace showed that costing 1332 binder round trips to
     * com.android.phone on the main thread for a 121-conversation inbox.
     * Bounded by the number of distinct senders.
     */
    private val normalized = ConcurrentHashMap<String, String>()

    /**
     * The device country, resolved once. Reading it hits com.android.phone over
     * binder, and it cannot meaningfully change while the process is alive
     * short of a SIM swap, which restarts the relevant state anyway.
     */
    @Volatile private var countryIso: String? = null

    fun normalize(context: Context, raw: String): String {
        val trimmed = raw.trim()
        return normalized.getOrPut(trimmed) { compute(context, trimmed) }
    }

    private fun compute(context: Context, trimmed: String): String {
        // Alphanumeric sender IDs (e.g. "Snapp") — keep as upper-cased raw.
        if (trimmed.any { it.isLetter() }) return trimmed.uppercase(Locale.ROOT)
        val e164 = try {
            PhoneNumberUtils.formatNumberToE164(trimmed, getCountryIso(context))
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
        countryIso?.let { return it }
        val resolved = try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            // Evaluated lazily: each read is a binder call, and the SIM one is
            // only needed when the network has not reported a country.
            val network = tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            val sim = { tm?.simCountryIso?.takeIf { it.isNotBlank() } }
            (network ?: sim() ?: Locale.getDefault().country).uppercase(Locale.ROOT)
        } catch (_: Exception) {
            Locale.getDefault().country
        }
        countryIso = resolved
        return resolved
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
