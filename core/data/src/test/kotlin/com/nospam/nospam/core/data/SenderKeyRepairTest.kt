// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.BlocklistEntity
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadSpamPolicy
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.telephony.PhoneNumberNormalizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Black-box contract for re-keying senders when the sender-key scheme changes:
 * [mergeSenderStates], [planRekey] and [SenderKeyRepair.runIfNeeded].
 */
class SenderKeyRepairTest {

    private val irKey: (String) -> String = { PhoneNumberNormalizer.keyFor(it, "IR") }

    private fun st(
        key: String,
        s: ThreadSpamState,
        spam: Int = 0,
        ham: Int = 0,
        override: Boolean = false,
        at: Long = 1L,
        replied: Boolean = false,
    ) = SenderStateEntity(
        normalizedAddress = key, state = s, spamCount = spam, hamCount = ham,
        isUserOverride = override, updatedAt = at, hasReplied = replied,
    )

    private fun verdict(id: Long, key: String, spam: Boolean = true, label: Boolean? = null) =
        MessageVerdictEntity(messageId = id, threadId = 7, normalizedAddress = key, isSpam = spam, score = 0.9, createdAt = 1000 + id, userLabel = label)

    // ---------------------------------------------------------------- mergeSenderStates

    @Test
    fun `merge of no rows throws`() {
        try {
            mergeSenderStates("+98", emptyList())
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `single row gets the new key and keeps its fields`() {
        val row = st("5000301630", ThreadSpamState.SPAM, spam = 10, ham = 0, at = 42, replied = false)
        assertEquals(row.copy(normalizedAddress = "+985000301630"), mergeSenderStates("+985000301630", listOf(row)))
    }

    @Test
    fun `single user-override row keeps its decision`() {
        val row = st("x", ThreadSpamState.TRUSTED, spam = 3, override = true, at = 5)
        assertEquals(row.copy(normalizedAddress = "k"), mergeSenderStates("k", listOf(row)))
    }

    @Test
    fun `single automatic row whose state disagrees with counts is re-derived`() {
        // Same rule as for several rows: no override -> deriveState.
        val row = st("x", ThreadSpamState.CLEAN, spam = 2, at = 5)
        val merged = mergeSenderStates("k", listOf(row))
        assertEquals(ThreadSpamState.SPAM, merged.state)
        assertFalse(merged.isUserOverride)
    }

    @Test
    fun `automatic rows sum counts and derive state`() {
        val merged = mergeSenderStates(
            "+985000301630",
            listOf(
                st("5000301630", ThreadSpamState.SPAM, spam = 10, at = 10),
                st("+985000301630", ThreadSpamState.SUSPECTED, spam = 1, at = 20),
            ),
        )
        assertEquals(
            st("+985000301630", ThreadSpamState.SPAM, spam = 11, ham = 0, override = false, at = 20, replied = false),
            merged,
        )
    }

    @Test
    fun `two suspected rows become SPAM`() {
        val merged = mergeSenderStates(
            "k",
            listOf(st("a", ThreadSpamState.SUSPECTED, spam = 1), st("b", ThreadSpamState.SUSPECTED, spam = 1)),
        )
        assertEquals(ThreadSpamState.SPAM, merged.state)
        assertEquals(2, merged.spamCount)
    }

    @Test
    fun `ham on either side makes MIXED, and hasReplied is any`() {
        val m1 = mergeSenderStates("k", listOf(st("a", ThreadSpamState.SPAM, spam = 3), st("b", ThreadSpamState.CLEAN, ham = 2)))
        assertEquals(ThreadSpamState.MIXED, m1.state)
        assertEquals(3, m1.spamCount)
        assertEquals(2, m1.hamCount)

        val m2 = mergeSenderStates(
            "k",
            listOf(st("a", ThreadSpamState.SPAM, spam = 3), st("b", ThreadSpamState.CLEAN, replied = true)),
        )
        assertTrue(m2.hasReplied)
        assertEquals(ThreadSpamPolicy.deriveState(3, 0, true), m2.state)
        assertEquals(ThreadSpamState.MIXED, m2.state)

        val m3 = mergeSenderStates("k", listOf(st("a", ThreadSpamState.CLEAN), st("b", ThreadSpamState.CLEAN)))
        assertFalse(m3.hasReplied)
        assertEquals(ThreadSpamState.CLEAN, m3.state)
    }

    @Test
    fun `updatedAt is the max`() {
        val merged = mergeSenderStates(
            "k",
            listOf(st("a", ThreadSpamState.CLEAN, at = 300), st("b", ThreadSpamState.CLEAN, at = 100), st("c", ThreadSpamState.CLEAN, at = 200)),
        )
        assertEquals(300L, merged.updatedAt)
    }

    @Test
    fun `user TRUSTED decision survives a merge with automatic SPAM`() {
        val merged = mergeSenderStates(
            "+985000301630",
            listOf(
                st("5000301630", ThreadSpamState.TRUSTED, spam = 1, ham = 1, override = true, at = 10),
                st("+985000301630", ThreadSpamState.SPAM, spam = 5, at = 50),
            ),
        )
        assertEquals(ThreadSpamState.TRUSTED, merged.state)
        assertTrue(merged.isUserOverride)
        assertEquals(6, merged.spamCount)
        assertEquals(1, merged.hamCount)
        assertEquals(50L, merged.updatedAt)
        assertEquals("+985000301630", merged.normalizedAddress)
    }

    @Test
    fun `two conflicting user decisions - the latest wins, in either order`() {
        val trusted = st("a", ThreadSpamState.TRUSTED, override = true, at = 10)
        val reported = st("b", ThreadSpamState.SPAM, spam = 1, override = true, at = 20)
        val auto = st("c", ThreadSpamState.CLEAN, ham = 4, at = 99)
        assertEquals(ThreadSpamState.SPAM, mergeSenderStates("k", listOf(trusted, reported, auto)).state)
        assertEquals(ThreadSpamState.SPAM, mergeSenderStates("k", listOf(auto, reported, trusted)).state)

        val laterTrusted = trusted.copy(updatedAt = 30)
        val m = mergeSenderStates("k", listOf(reported, laterTrusted, auto))
        assertEquals(ThreadSpamState.TRUSTED, m.state)
        assertTrue(m.isUserOverride)
        assertEquals(99L, m.updatedAt)
    }

    @Test
    fun `blocked plus trusted is BLOCKED`() {
        val m = mergeSenderStates(
            "k",
            listOf(
                st("a", ThreadSpamState.BLOCKED, override = true, at = 1),
                st("b", ThreadSpamState.TRUSTED, override = true, at = 100),
            ),
        )
        assertEquals(ThreadSpamState.BLOCKED, m.state)
        assertTrue(m.isUserOverride)
    }

    @Test
    fun `blocked without override stays non-override even next to a TRUSTED override`() {
        val m = mergeSenderStates(
            "k",
            listOf(
                st("a", ThreadSpamState.BLOCKED, override = false, at = 1),
                st("b", ThreadSpamState.TRUSTED, override = true, at = 100),
            ),
        )
        assertEquals(ThreadSpamState.BLOCKED, m.state)
        assertFalse(m.isUserOverride)
    }

    @Test
    fun `blocked beats automatic SPAM and keeps summed counts`() {
        val m = mergeSenderStates(
            "k",
            listOf(st("a", ThreadSpamState.BLOCKED, spam = 1, override = true), st("b", ThreadSpamState.SPAM, spam = 4, replied = true)),
        )
        assertEquals(ThreadSpamState.BLOCKED, m.state)
        assertTrue(m.isUserOverride)
        assertEquals(5, m.spamCount)
        assertTrue(m.hasReplied)
    }

    // ---------------------------------------------------------------- planRekey

    @Test
    fun `planRekey with nothing to change is empty`() {
        val plan = planRekey(
            listOf(st("+985000301630", ThreadSpamState.SPAM, spam = 2), st("SNAPP", ThreadSpamState.CLEAN)),
            listOf("+989121234567", "SNAPP"),
            irKey,
        )
        assertEquals(emptyMap<String, String>(), plan.newKeyFor)
        assertEquals(emptyList<SenderStateEntity>(), plan.mergedStates)
    }

    @Test
    fun `planRekey merges the old-key row into the existing new-key row`() {
        val plan = planRekey(
            listOf(
                st("5000301630", ThreadSpamState.SPAM, spam = 10, at = 1),
                st("+985000301630", ThreadSpamState.SUSPECTED, spam = 1, at = 2),
                st("+989121234567", ThreadSpamState.MIXED, spam = 1, ham = 1, at = 3),
            ),
            emptyList(),
            irKey,
        )
        assertEquals(mapOf("5000301630" to "+985000301630"), plan.newKeyFor)
        assertEquals(1, plan.mergedStates.size)
        val m = plan.mergedStates.single()
        assertEquals("+985000301630", m.normalizedAddress)
        assertEquals(ThreadSpamState.SPAM, m.state)
        assertEquals(11, m.spamCount)
        assertEquals(2L, m.updatedAt)
    }

    @Test
    fun `planRekey includes keys only seen in verdicts or the block list`() {
        val plan = planRekey(
            listOf(st("+989121234567", ThreadSpamState.CLEAN)),
            listOf("5000301630", "09121234567", "Snapp", "5000301630"),
            irKey,
        )
        assertEquals(
            mapOf("5000301630" to "+985000301630", "09121234567" to "+989121234567", "Snapp" to "SNAPP"),
            plan.newKeyFor,
        )
        // No state row changed key, so there is nothing to merge.
        assertEquals(emptyList<SenderStateEntity>(), plan.mergedStates)
    }

    @Test
    fun `planRekey moves a lone old-key row to its new key`() {
        val plan = planRekey(listOf(st("05000301630", ThreadSpamState.SPAM, spam = 3, at = 9)), emptyList(), irKey)
        assertEquals(mapOf("05000301630" to "+985000301630"), plan.newKeyFor)
        assertEquals(listOf(st("+985000301630", ThreadSpamState.SPAM, spam = 3, at = 9)), plan.mergedStates)
    }

    @Test
    fun `planRekey merges several old keys into one`() {
        val plan = planRekey(
            listOf(
                st("5000301630", ThreadSpamState.SUSPECTED, spam = 1, at = 1),
                st("05000301630", ThreadSpamState.SUSPECTED, spam = 1, at = 2),
                st("00985000301630", ThreadSpamState.CLEAN, ham = 1, at = 3),
            ),
            emptyList(),
            irKey,
        )
        assertEquals(setOf("5000301630", "05000301630", "00985000301630"), plan.newKeyFor.keys)
        assertTrue(plan.newKeyFor.values.all { it == "+985000301630" })
        val m = plan.mergedStates.single()
        assertEquals("+985000301630", m.normalizedAddress)
        assertEquals(2, m.spamCount)
        assertEquals(1, m.hamCount)
        assertEquals(ThreadSpamState.MIXED, m.state)
        assertEquals(3L, m.updatedAt)
    }

    @Test
    fun `planRekey uses the given keyFor`() {
        val plan = planRekey(
            listOf(st("a", ThreadSpamState.SPAM, spam = 2), st("b", ThreadSpamState.SPAM, spam = 3)),
            listOf("c"),
            { it.uppercase() },
        )
        assertEquals(mapOf("a" to "A", "b" to "B", "c" to "C"), plan.newKeyFor)
        assertEquals(setOf("A", "B"), plan.mergedStates.map { it.normalizedAddress }.toSet())
    }

    // ---------------------------------------------------------------- runIfNeeded

    private class SchemeStore(var value: String?) {
        var saves = 0
        var reads = 0
    }

    private fun repair(db: NoSpamDatabase, store: SchemeStore, scheme: String = "s2", keyFor: (String) -> String = irKey) =
        SenderKeyRepair(
            db = db,
            spamStateWriter = SpamStateWriter(db.senderStateDao),
            keyFor = keyFor,
            scheme = scheme,
            storedScheme = { store.reads++; store.value },
            saveScheme = { store.saves++; store.value = it },
        )

    private suspend fun seedBugCase(db: NoSpamDatabase) {
        db.senderStateDao.upsert(st("5000301630", ThreadSpamState.SPAM, spam = 10, at = 10))
        db.senderStateDao.upsert(st("+985000301630", ThreadSpamState.SUSPECTED, spam = 1, at = 20))
        (1L..10L).forEach { db.messageVerdictDao.insert(verdict(it, "5000301630")) }
        db.messageVerdictDao.insert(verdict(11, "+985000301630"))
    }

    @Test
    fun `the bug case - split sender becomes one SPAM sender with 11 messages`() = runTest {
        val db = NoSpamDatabase.inMemory()
        seedBugCase(db)
        val store = SchemeStore(null)

        assertTrue(repair(db, store).runIfNeeded())

        val states = db.senderStateDao.getAll()
        assertEquals(1, states.size)
        val s = states.single()
        assertEquals("+985000301630", s.normalizedAddress)
        assertEquals(ThreadSpamState.SPAM, s.state)
        assertEquals(11, s.spamCount)
        assertNull(db.senderStateDao.getByAddress("5000301630"))
        val verdicts = db.messageVerdictDao.observeAll().first()
        assertEquals(11, verdicts.size)
        assertTrue(verdicts.all { it.normalizedAddress == "+985000301630" })
        assertEquals("s2", store.value)
        assertEquals(1, store.saves)
    }

    @Test
    fun `stored scheme equal - returns false and touches nothing`() = runTest {
        val db = NoSpamDatabase.inMemory()
        seedBugCase(db)
        val before = db.senderStateDao.getAll().toSet()
        val beforeVerdicts = db.messageVerdictDao.observeAll().first().toSet()
        val store = SchemeStore("s2")

        assertFalse(repair(db, store, scheme = "s2").runIfNeeded())

        assertEquals(before, db.senderStateDao.getAll().toSet())
        assertEquals(beforeVerdicts, db.messageVerdictDao.observeAll().first().toSet())
        assertEquals(0, store.saves)
    }

    @Test
    fun `stored scheme equal - keyFor is never called`() = runTest {
        val db = NoSpamDatabase.inMemory()
        seedBugCase(db)
        val r = repair(db, SchemeStore("s2"), keyFor = { throw AssertionError("keyFor called with up-to-date scheme") })
        assertFalse(r.runIfNeeded())
    }

    @Test
    fun `different stored scheme triggers the repair`() = runTest {
        val db = NoSpamDatabase.inMemory()
        seedBugCase(db)
        val store = SchemeStore("s1")
        assertTrue(repair(db, store, scheme = "s2").runIfNeeded())
        assertEquals("s2", store.value)
        assertEquals(listOf("+985000301630"), db.senderStateDao.getAll().map { it.normalizedAddress })
    }

    @Test
    fun `running again after a save does nothing`() = runTest {
        val db = NoSpamDatabase.inMemory()
        seedBugCase(db)
        val store = SchemeStore(null)
        assertTrue(repair(db, store).runIfNeeded())
        val after = db.senderStateDao.getAll().toSet()

        assertFalse(repair(db, store).runIfNeeded())
        assertEquals(after, db.senderStateDao.getAll().toSet())
        assertEquals(1, store.saves)
    }

    @Test
    fun `running again with a stale scheme does not change any count`() = runTest {
        val db = NoSpamDatabase.inMemory()
        seedBugCase(db)
        db.blocklistDao.insert(BlocklistEntity(address = "05000301630", createdAt = 1))
        // Save never sticks.
        val store = SchemeStore(null)
        val r = SenderKeyRepair(
            db = db,
            spamStateWriter = SpamStateWriter(db.senderStateDao),
            keyFor = irKey,
            scheme = "s2",
            storedScheme = { store.value },
            saveScheme = { store.saves++ },
        )
        assertTrue(r.runIfNeeded())
        val states1 = db.senderStateDao.getAll().toSet()
        val verdicts1 = db.messageVerdictDao.observeAll().first().toSet()
        val blocks1 = db.blocklistDao.observeAll().first().map { it.address }

        assertTrue(r.runIfNeeded())

        assertEquals(states1, db.senderStateDao.getAll().toSet())
        assertEquals(11, db.senderStateDao.getAll().single().spamCount)
        assertEquals(verdicts1, db.messageVerdictDao.observeAll().first().toSet())
        assertEquals(blocks1, db.blocklistDao.observeAll().first().map { it.address })
        assertEquals(2, store.saves)
    }

    @Test
    fun `block-list entry under the old key ends under the new key`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.blocklistDao.insert(BlocklistEntity(address = "5000301630", createdAt = 1))
        db.senderStateDao.upsert(st("5000301630", ThreadSpamState.BLOCKED, spam = 1, override = true))

        assertTrue(repair(db, SchemeStore(null)).runIfNeeded())

        assertEquals(listOf("+985000301630"), db.blocklistDao.observeAll().first().map { it.address })
        assertNull(db.blocklistDao.findByAddress("5000301630"))
        val s = db.senderStateDao.getAll().single()
        assertEquals("+985000301630", s.normalizedAddress)
        assertEquals(ThreadSpamState.BLOCKED, s.state)
        assertTrue(s.isUserOverride)
    }

    @Test
    fun `block-list-only key is moved even without a sender_state row`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.blocklistDao.insert(BlocklistEntity(address = "09121234567", createdAt = 1))
        assertTrue(repair(db, SchemeStore(null)).runIfNeeded())
        assertEquals(listOf("+989121234567"), db.blocklistDao.observeAll().first().map { it.address })
        assertEquals(emptyList<SenderStateEntity>(), db.senderStateDao.getAll())
    }

    @Test
    fun `verdict-only key is moved and user labels kept`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.messageVerdictDao.insert(verdict(1, "09121234567", spam = false, label = true))
        assertTrue(repair(db, SchemeStore(null)).runIfNeeded())
        val v = db.messageVerdictDao.getByMessageId(1)!!
        assertEquals("+989121234567", v.normalizedAddress)
        assertEquals(true, v.userLabel)
        assertFalse(v.isSpam)
    }

    @Test
    fun `user decision survives through runIfNeeded`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(st("5000301630", ThreadSpamState.TRUSTED, spam = 2, override = true, at = 5))
        db.senderStateDao.upsert(st("+985000301630", ThreadSpamState.SPAM, spam = 3, at = 50))
        assertTrue(repair(db, SchemeStore(null)).runIfNeeded())
        val s = db.senderStateDao.getAll().single()
        assertEquals("+985000301630", s.normalizedAddress)
        assertEquals(ThreadSpamState.TRUSTED, s.state)
        assertTrue(s.isUserOverride)
        assertEquals(5, s.spamCount)
    }

    @Test
    fun `hasReplied on the old key carries over`() = runTest {
        val db = NoSpamDatabase.inMemory()
        db.senderStateDao.upsert(st("5000301630", ThreadSpamState.CLEAN, replied = true))
        db.senderStateDao.upsert(st("+985000301630", ThreadSpamState.SUSPECTED, spam = 1))
        assertTrue(repair(db, SchemeStore(null)).runIfNeeded())
        val s = db.senderStateDao.getAll().single()
        assertTrue(s.hasReplied)
        assertEquals(ThreadSpamState.MIXED, s.state)
    }

    @Test
    fun `unrelated senders are untouched`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val keep = st("+989121234567", ThreadSpamState.MIXED, spam = 1, ham = 1, at = 3)
        val keepId = st("SNAPP", ThreadSpamState.TRUSTED, override = true, at = 4)
        db.senderStateDao.upsert(keep)
        db.senderStateDao.upsert(keepId)
        seedBugCase(db)
        assertTrue(repair(db, SchemeStore(null)).runIfNeeded())
        assertEquals(keep, db.senderStateDao.getByAddress("+989121234567"))
        assertEquals(keepId, db.senderStateDao.getByAddress("SNAPP"))
    }

    @Test
    fun `empty database still saves the scheme`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val store = SchemeStore(null)
        assertTrue(repair(db, store).runIfNeeded())
        assertEquals("s2", store.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `repair launched on a StandardTestDispatcher completes after advanceUntilIdle`() = runTest(StandardTestDispatcher()) {
        val db = NoSpamDatabase.inMemory()
        seedBugCase(db)
        val store = SchemeStore(null)
        var result: Boolean? = null

        launch { result = repair(db, store).runIfNeeded() }
        // Nothing has run yet on a StandardTestDispatcher.
        assertNull(result)
        advanceUntilIdle()

        assertEquals(true, result)
        assertEquals("s2", store.value)
        assertEquals(11, db.senderStateDao.getAll().single().spamCount)
    }
}
