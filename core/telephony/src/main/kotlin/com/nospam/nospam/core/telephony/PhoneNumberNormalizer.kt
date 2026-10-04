// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.content.Context
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import com.nospam.nospam.core.model.isAlphanumericSender

/**
 * The sender key: one string per sender, however the network spells the
 * number. Everything keyed by sender (spam state, verdicts, the app's block
 * list) goes through [normalize].
 *
 * A number is read with libphonenumber in the phone's country and written in
 * E.164 whether or not the library can validate it. Android's
 * `PhoneNumberUtils.formatNumberToE164` returns null for any number it cannot
 * validate, and the raw text then became the key, so Iranian service numbers
 * split in two: `5000301630` and `+985000301630` were two senders, one in
 * Spam with ten messages and one in the inbox with one, in one conversation
 * (found 2026-10-04). Validity and length are deliberately not checked: the
 * library calls 12-14 digit service numbers (3000…, 5000…) too long, yet reads
 * every spelling of them the same way.
 *
 * Sender IDs (any letter) are upper-cased text, and anything the library cannot
 * read at all keeps its trimmed text. Short codes become E.164 too (`1000` is
 * `+981000`), which cannot meet a real number: none is that short.
 *
 * Known limit: a foreign number sent without its "+" is read as a local one.
 */
object PhoneNumberNormalizer {
    /**
     * Identifies the key scheme. Stored with the data so that a change here, or
     * a libphonenumber update that reads some number differently, re-keys what
     * is stored (`SenderKeyRepair`). Bump it with the libphonenumber version;
     * a test checks the version catalog against it.
     */
    const val KEY_SCHEME = "libphonenumber-9.0.40/1"

    private val phoneUtil: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

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

    /**
     * Resolves the device country ahead of time. The first touch of the
     * telephony service is expensive — a trace measured the remaining calls at
     * ~68 ms each, largely service binding and proxy class loading — so
     * [com.nospam.nospam.core.telephony] callers should not pay it on the
     * inbox's critical path. Safe to call repeatedly; safe to call off Main.
     */
    fun warm(context: Context) {
        getCountryIso(context)
        // Loads the library's metadata, off the main thread.
        phoneUtil
    }

    fun normalize(context: Context, raw: String): String {
        val trimmed = raw.trim()
        return normalized.getOrPut(trimmed) { compute(context, trimmed) }
    }

    private fun compute(context: Context, trimmed: String): String =
        if (isAlphanumericSender(trimmed)) trimmed.uppercase(Locale.ROOT)
        else keyFor(trimmed, getCountryIso(context))

    /**
     * The sender key for [raw] read in [region] (ISO 3166 alpha-2, any case).
     * Pure, so the rule is testable on the JVM. Applying it to its own result
     * returns that result: re-keying stored keys a second time changes nothing.
     */
    fun keyFor(raw: String, region: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        if (isAlphanumericSender(trimmed)) return trimmed.uppercase(Locale.ROOT)
        return try {
            phoneUtil.format(phoneUtil.parse(trimmed, region.uppercase(Locale.ROOT)), PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (_: NumberParseException) {
            trimmed
        }
    }

    fun normalizedVariants(context: Context, raw: String): List<String> {
        val trimmed = raw.trim()
        val normalized = normalize(context, trimmed)
        return if (normalized == trimmed) listOf(trimmed)
        else listOf(trimmed, normalized, normalized.uppercase(Locale.ROOT))
    }

    /**
     * The country numbers are read in: the SIM's, else the last SIM country seen
     * (a SIM not ready yet, or removed for a history scan), else the language
     * region. Never the network's: while roaming it is another country, and
     * `0912…` would become a different key abroad.
     *
     * Cached for good once a SIM answers. A fallback is kept for a minute, since
     * asking the SIM is a binder call (~68 ms) and every new sender would pay it;
     * the keys computed with a fallback are dropped once the SIM answers.
     */
    private fun getCountryIso(context: Context): String {
        countryIso?.let { return it }
        fallbackCountry?.let { if (System.currentTimeMillis() - fallbackAt < FALLBACK_RETRY_MS) return it }
        val prefs = context.applicationContext.getSharedPreferences(COUNTRY_PREFS, Context.MODE_PRIVATE)
        val sim = try {
            context.getSystemService(TelephonyManager::class.java)?.simCountryIso?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        if (sim != null) {
            val resolved = sim.uppercase(Locale.ROOT)
            if (prefs.getString(KEY_LAST_SIM_COUNTRY, null) != resolved) {
                prefs.edit().putString(KEY_LAST_SIM_COUNTRY, resolved).apply()
            }
            if (fallbackUsed) {
                normalized.clear()
                fallbackUsed = false
                fallbackCountry = null
            }
            countryIso = resolved
            return resolved
        }
        fallbackUsed = true
        val fallback = (prefs.getString(KEY_LAST_SIM_COUNTRY, null) ?: Locale.getDefault().country).uppercase(Locale.ROOT)
        fallbackCountry = fallback
        fallbackAt = System.currentTimeMillis()
        return fallback
    }

    @Volatile private var fallbackUsed = false
    @Volatile private var fallbackCountry: String? = null
    @Volatile private var fallbackAt = 0L
    private const val FALLBACK_RETRY_MS = 60_000L
    private const val COUNTRY_PREFS = "sender_key_country"
    private const val KEY_LAST_SIM_COUNTRY = "last_sim_country"

}
