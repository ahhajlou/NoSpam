// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.nospam.nospam.core.model.Participant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ContactLookup(private val context: Context) {
    private val _generation = MutableStateFlow(0)

    /**
     * Bumped whenever the contacts provider changes, after everything read
     * from it has been dropped. Anything built from lookups made under an
     * older value is out of date: the inbox re-reads on it.
     */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /**
     * Contacts were read once per process, so a contact added, renamed or
     * deleted while the app ran was not seen until a restart: the inbox kept
     * the bare number, and ingress kept treating a new contact as a stranger.
     * The provider announces every change under its authority URI.
     */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = invalidateAll()
    }

    init {
        // Registering needs no permission; without READ_CONTACTS the change is
        // announced all the same and the next read fails as it would anyway.
        runCatching {
            context.contentResolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
        }
    }
    /**
     * Wraps the lookup result so a miss can be cached too. `ConcurrentHashMap`
     * rejects null values, so storing a bare null threw and the caller's
     * `runCatching` swallowed it — every non-contact sender re-queried
     * PhoneLookup on each conversation-list rebuild.
     */
    private data class Cached(val participant: Participant?)

    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * Every contact phone number, keyed by [matchKey], read in a single query.
     *
     * The per-address `PhoneLookup` path cost one provider query per
     * conversation. A system trace of a 121-conversation inbox showed 444 of
     * them fanned out over as many as 29 threads, all queued against the one
     * contacts provider process and waiting 300-500 ms each, which was most of
     * the visible inbox delay. Contacts are a small table; reading it once is
     * cheaper than asking about 121 numbers individually.
     *
     * Null until [warm] has run, which is how [lookup] knows whether a miss is
     * trustworthy or whether it still has to ask the provider.
     */
    @Volatile private var directory: Map<String, Participant>? = null
    private val warming = AtomicBoolean(false)

    /** True once the contact directory has been read, so a miss is a real miss. */
    val isWarm: Boolean get() = directory != null

    /**
     * Loads the contact directory, once it can be read. Safe to call from any
     * thread. A failed load is retried on the next call: before READ_CONTACTS is
     * granted the read throws, and treating that as done left the app without
     * contacts until the process restarted, even after the grant in onboarding.
     */
    fun warm() {
        if (directory != null || !warming.compareAndSet(false, true)) return
        try {
            val generationAtStart = _generation.value
            val loaded = runCatching { loadDirectory() }.getOrNull()
            // A change during the read may be missing from it; the next warm reloads.
            synchronized(this) { if (_generation.value == generationAtStart) directory = loaded }
        } finally {
            warming.set(false)
        }
    }

    /** Drops everything read from the contacts provider. */
    internal fun invalidateAll() {
        synchronized(this) {
            _generation.value++
            directory = null
        }
        cache.clear()
    }

    fun lookup(address: String): Participant? {
        // Alphanumeric senders are not in contacts PhoneLookup
        if (address.any { it.isLetter() }) return null
        cache[address]?.let { return it.participant }

        val generationAtStart = _generation.value
        val dir = directory
        val result = if (dir == null) {
            // Not warmed, ask the provider. A failed read is not a miss: cached,
            // it outlived the READ_CONTACTS grant, and ingress then took a
            // contact's message for a stranger's.
            runCatching { query(address) }.getOrElse { return null }
        } else {
            dir[matchKey(address)]?.copy(address = address)
        }
        // Read before a change landed, it may be stale; answer, but do not keep
        // it. Checked again after the put, which could land after the clear.
        if (_generation.value == generationAtStart) {
            val entry = Cached(result)
            cache[address] = entry
            if (_generation.value != generationAtStart) cache.remove(address, entry)
        }
        return result
    }

    fun invalidate(address: String) { cache.remove(address) }

    /**
     * Last [MATCH_DIGITS] digits of the number, which is how the platform's own
     * PhoneLookup compares numbers. Keying on the suffix is what lets a stored
     * "+98912..." match an incoming "0912...".
     */
    private fun matchKey(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length <= MATCH_DIGITS) digits else digits.takeLast(MATCH_DIGITS)
    }

    private fun loadDirectory(): Map<String, Participant> {
        val out = HashMap<String, Participant>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null
        )?.use { c ->
            val number = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val name = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val id = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val photo = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
            val starred = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)
            while (c.moveToNext()) {
                val raw = c.getString(number) ?: continue
                val key = matchKey(raw)
                if (key.isEmpty()) continue
                // First row wins, matching PhoneLookup's single-result behaviour
                // when one number is attached to several contacts.
                if (out.containsKey(key)) continue
                out[key] = Participant(
                    address = raw,
                    displayName = c.getString(name),
                    contactId = c.getLong(id),
                    photoUri = c.getString(photo),
                    isStarred = c.getInt(starred) == 1,
                )
            }
        }
        return out
    }

    /** Throws when the provider cannot be read, e.g. without READ_CONTACTS. */
    private fun query(address: String): Participant? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.STARRED,
            ContactsContract.PhoneLookup.PHOTO_URI
        )
        return context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                Participant(
                    address = address,
                    displayName = c.getString(c.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)),
                    contactId = c.getLong(c.getColumnIndexOrThrow(ContactsContract.PhoneLookup._ID)),
                    photoUri = c.getString(c.getColumnIndexOrThrow(ContactsContract.PhoneLookup.PHOTO_URI)),
                    isStarred = c.getInt(c.getColumnIndexOrThrow(ContactsContract.PhoneLookup.STARRED)) == 1
                )
            } else null
        }
    }

    private companion object {
        /** Platform default for PHONE_NUMBERS_EQUAL suffix matching. */
        const val MATCH_DIGITS = 7
    }
}
