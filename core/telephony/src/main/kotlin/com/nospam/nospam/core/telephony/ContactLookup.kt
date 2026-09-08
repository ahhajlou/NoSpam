package com.nospam.nospam.core.telephony

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.nospam.nospam.core.model.Participant
import java.util.concurrent.ConcurrentHashMap

class ContactLookup(private val context: Context) {
    private val cache = ConcurrentHashMap<String, Participant?>()

    fun lookup(address: String): Participant? {
        // Alphanumeric senders are not in contacts PhoneLookup
        if (address.any { it.isLetter() }) return null
        cache[address]?.let { return it }
        val result = query(address)
        cache[address] = result
        return result
    }

    fun invalidate(address: String) { cache.remove(address) }

    private fun query(address: String): Participant? {
        return try {
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
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
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
        } catch (_: Exception) { null }
    }
}
