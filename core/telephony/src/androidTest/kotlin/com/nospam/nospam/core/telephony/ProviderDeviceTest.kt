// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.ThreadId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [RealTelephonyDataSource] against the real SMS provider: thread pagination,
 * delete, read state and the SIM path. The JVM cannot check any of this; the
 * pagination cursor bug (messages restored out of order were unreachable) is
 * the kind it missed.
 *
 * Writes need the default-SMS role, which [SmsRoleRule] takes for each test.
 * Every row is written under the [addresses] below, numbers nobody has, and
 * deleted afterwards without going through the code under test.
 */
@RunWith(AndroidJUnit4::class)
class ProviderDeviceTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_PHONE_STATE,
    )

    @get:Rule
    val smsRole = SmsRoleRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dataSource by lazy { RealTelephonyDataSource(context) }

    private val addresses = listOf("+15557650101", "+15557650102", "+15557650103")

    @Before fun takeRole() {
        smsRole.require()
        deleteFixtures()
    }

    @After fun cleanUp() = deleteFixtures()

    private fun deleteFixtures() {
        context.contentResolver.delete(
            Telephony.Sms.CONTENT_URI,
            "${Telephony.Sms.ADDRESS} IN (${addresses.joinToString(",") { "?" }})",
            addresses.toTypedArray(),
        )
    }

    // --- Fixtures ---------------------------------------------------------------

    private val base = 1_700_000_000_000L

    /** Inserts a received row directly, without the code under test. */
    private fun insert(address: String, body: String, date: Long, read: Boolean = false): Long {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.READ, if (read) 1 else 0)
        }
        val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        assertNotNull("insert for $address failed", uri)
        return uri!!.lastPathSegment!!.toLong()
    }

    private fun threadOf(rowId: Long): ThreadId =
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms._ID} = ?",
            arrayOf(rowId.toString()),
            null,
        )!!.use { c ->
            assertTrue("row $rowId missing", c.moveToFirst())
            ThreadId(c.getLong(0))
        }

    /** Row id to read flag, for the rows still there. */
    private fun readFlags(rowIds: Collection<Long>): Map<Long, Boolean> =
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.READ),
            "${Telephony.Sms._ID} IN (${rowIds.joinToString(",") { "?" }})",
            rowIds.map { it.toString() }.toTypedArray(),
            null,
        )!!.use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getInt(1) == 1) }
        }

    private fun existing(rowIds: Collection<Long>): Set<Long> = readFlags(rowIds).keys

    /** Every page of [threadId], newest first, as the thread screen loads them while scrolling up. */
    private suspend fun allPages(threadId: ThreadId, limit: Int): List<List<Message>> {
        val pages = mutableListOf<List<Message>>()
        var before: Message? = null
        while (true) {
            val page = dataSource.getMessages(threadId, limit, before)
            if (page.isEmpty()) return pages
            pages += page
            before = page.first()
            assertTrue("paging did not stop", pages.size <= 20)
        }
    }

    /** Thread ids that do not exist, to push real ones past a 500-thread chunk. */
    private fun padding(count: Int): List<ThreadId> = (1..count).map { ThreadId(Long.MAX_VALUE - it) }

    // --- Pagination -------------------------------------------------------------

    @Test fun history_restored_newest_first_is_reachable_page_by_page_in_date_order() = runTest {
        val address = addresses[0]
        // Restored the way backup tools do it: newest first, so every older
        // message has a higher row id. Two share a date, which the row id orders.
        val dates = listOf(9, 8, 7, 6, 5, 5, 4, 3, 2, 1).map { base + it * 60_000L }
        val ids = dates.mapIndexed { i, date -> insert(address, "restored $i", date, read = true) }
        insert(addresses[1], "other thread", base + 5 * 60_000L)
        val threadId = threadOf(ids.first())

        val pages = allPages(threadId, limit = 3)

        assertEquals(listOf(3, 3, 3, 1), pages.map { it.size })
        // Each page is in ascending order, and pages run newest to oldest.
        val expected = ids.zip(dates)
            .sortedWith(compareBy({ it.second }, { it.first }))
            .map { it.first }
        assertEquals(expected, pages.reversed().flatten().map { it.id.value })
        assertTrue(pages.flatten().all { it.threadId == threadId })
    }

    @Test fun newest_page_holds_the_newest_messages_in_ascending_order() = runTest {
        val address = addresses[0]
        val ids = (1..5).map { insert(address, "message $it", base + it * 60_000L) }

        val page = dataSource.getMessages(threadOf(ids.first()), limit = 2)

        assertEquals(ids.takeLast(2), page.map { it.id.value })
    }

    @Test fun nothing_is_older_than_the_oldest_message() = runTest {
        val id = insert(addresses[0], "only", base)
        val threadId = threadOf(id)
        val oldest = dataSource.getMessages(threadId).single()

        assertTrue(dataSource.getMessages(threadId, before = oldest).isEmpty())
    }

    // --- Delete -----------------------------------------------------------------

    @Test fun deleting_a_message_removes_only_that_message() = runTest {
        val keep = insert(addresses[0], "keep", base)
        val drop = insert(addresses[0], "drop", base + 1)

        dataSource.deleteMessage(drop)

        assertEquals(setOf(keep), existing(listOf(keep, drop)))
    }

    @Test fun deleting_a_missing_message_does_nothing() = runTest {
        val keep = insert(addresses[0], "keep", base)
        val gone = insert(addresses[0], "gone", base + 1)
        dataSource.deleteMessage(gone)

        dataSource.deleteMessage(gone)

        assertEquals(setOf(keep), existing(listOf(keep, gone)))
    }

    @Test fun deleting_conversations_removes_their_messages_and_leaves_others() = runTest {
        val a = listOf(insert(addresses[0], "a1", base), insert(addresses[0], "a2", base + 1))
        val b = insert(addresses[1], "b1", base + 2)
        val c = insert(addresses[2], "c1", base + 3)
        val threads = listOf(threadOf(a.first()), threadOf(b))

        dataSource.deleteConversations(threads)

        assertEquals(setOf(c), existing(a + b + c))
        val listed = dataSource.getConversations().map { it.threadId }
        assertFalse("deleted thread still listed", listed.any { it in threads })
        assertTrue("other thread no longer listed", threadOf(c) in listed)
    }

    // Not a test of the chunking alone: on API 37 the per-thread Threads delete
    // that follows also removes the messages, so it passes with one chunk too.
    @Test fun deleting_more_than_a_chunk_of_conversations_deletes_all_of_them() = runTest {
        val first = insert(addresses[0], "first chunk", base)
        val last = insert(addresses[1], "second chunk", base + 1)
        val kept = insert(addresses[2], "kept", base + 2)

        dataSource.deleteConversations(listOf(threadOf(first)) + padding(600) + threadOf(last))

        assertEquals(setOf(kept), existing(listOf(first, last, kept)))
    }

    // --- Read state ---------------------------------------------------------------

    @Test fun marking_threads_read_marks_every_message_of_those_threads_only() = runTest {
        val a = listOf(insert(addresses[0], "a1", base), insert(addresses[0], "a2", base + 1))
        val b = insert(addresses[1], "b1", base + 2)
        val c = insert(addresses[2], "c1", base + 3)

        dataSource.setThreadsRead(listOf(threadOf(a.first()), threadOf(b)), read = true)

        assertEquals(mapOf(a[0] to true, a[1] to true, b to true, c to false), readFlags(a + b + c))
    }

    @Test fun marking_a_thread_unread_marks_its_messages_unread() = runTest {
        val a = listOf(insert(addresses[0], "a1", base, read = true), insert(addresses[0], "a2", base + 1, read = true))
        val b = insert(addresses[1], "b1", base + 2, read = true)

        dataSource.markAsUnread(threadOf(a.first()))

        assertEquals(mapOf(a[0] to false, a[1] to false, b to true), readFlags(a + b))

        dataSource.markAsRead(threadOf(a.first()))

        assertEquals(mapOf(a[0] to true, a[1] to true, b to true), readFlags(a + b))
    }

    @Test fun marking_more_than_one_chunk_of_threads_read_reaches_every_chunk() = runTest {
        val first = insert(addresses[0], "first chunk", base)
        val last = insert(addresses[1], "second chunk", base + 1)
        val other = insert(addresses[2], "other", base + 2)

        dataSource.setThreadsRead(listOf(threadOf(first)) + padding(600) + threadOf(last), read = true)

        assertEquals(mapOf(first to true, last to true, other to false), readFlags(listOf(first, last, other)))
    }

    @Test fun marking_one_message_read_leaves_the_rest_of_its_thread() = runTest {
        val one = insert(addresses[0], "one", base)
        val two = insert(addresses[0], "two", base + 1)

        dataSource.updateMessageRead(one, read = true)

        assertEquals(mapOf(one to true, two to false), readFlags(listOf(one, two)))
    }

    // --- SIM ----------------------------------------------------------------------

    private fun activeSubscriptionIds(): List<Int> =
        context.getSystemService(SubscriptionManager::class.java)
            .activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }

    @Test fun active_sims_are_the_ones_the_system_reports() = runTest {
        assertEquals(activeSubscriptionIds(), dataSource.getActiveSubscriptions().map { it.subscriptionId })
    }

    @Test fun each_kind_of_written_message_keeps_its_sim() = runTest {
        val sim = activeSubscriptionIds().firstOrNull()
        Assume.assumeTrue("no active SIM", sim != null)
        val address = addresses[0]
        val received = dataSource.insertInboxMessage(address, "in", base, read = false, subscriptionId = sim)
        val sent = dataSource.insertSentMessage(address, "sent", base + 1, subscriptionId = sim)
        val outbox = dataSource.insertOutboxMessage(address, "outbox", base + 2, subscriptionId = sim)
        assertNotNull(received)
        assertNotNull(sent)
        assertNotNull(outbox)

        val messages = dataSource.getMessages(threadOf(received!!)).associateBy { it.id.value }

        assertEquals(sim, messages.getValue(received).subscriptionId)
        assertEquals(sim, messages.getValue(sent!!).subscriptionId)
        assertEquals(sim, messages.getValue(outbox!!).subscriptionId)
    }

    @Test fun sms_manager_is_resolved_for_the_requested_sim() {
        val sim = activeSubscriptionIds().firstOrNull()
        Assume.assumeTrue("no active SIM", sim != null)

        assertEquals(sim, context.resolveSmsManager(sim).subscriptionId)
    }

    @Test fun sms_manager_without_a_sim_or_an_invalid_one_is_the_default() {
        val default = SubscriptionManager.getDefaultSubscriptionId()
        Assume.assumeTrue("no default SIM", default != SubscriptionManager.INVALID_SUBSCRIPTION_ID)

        assertEquals(default, context.resolveSmsManager(null).subscriptionId)
        assertEquals(default, context.resolveSmsManager(SubscriptionManager.INVALID_SUBSCRIPTION_ID).subscriptionId)
    }
}
