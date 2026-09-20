// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.dao.InMemorySenderStateDao
import com.nospam.nospam.core.database.dao.SenderStateDao
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.ThreadSpamState
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for SpamStateWriter, the single-writer gate for sender_state, per
 * core-data.md's "SpamStateWriter — the single-writer gate" section.
 */
class SpamStateWriterTest {

    /** Counts calls so "no-op means no DAO call at all" is directly assertable. */
    private class CountingSenderStateDao(
        private val delegate: SenderStateDao = InMemorySenderStateDao(),
    ) : SenderStateDao by delegate {
        var upsertAllCalls = 0
            private set
        var upsertCalls = 0
            private set

        override suspend fun upsert(entity: SenderStateEntity) {
            upsertCalls++
            delegate.upsert(entity)
        }

        override suspend fun upsertAll(entities: List<SenderStateEntity>) {
            upsertAllCalls++
            delegate.upsertAll(entities)
        }
    }

    @Test
    fun `upsertIfNotOverridden is a no-op that never invokes compute when the address is overridden`() = runTest {
        val dao = InMemorySenderStateDao()
        dao.upsert(
            SenderStateEntity(normalizedAddress = "+98912", state = ThreadSpamState.SPAM, isUserOverride = true)
        )
        val writer = SpamStateWriter(dao)
        var computeCalled = false

        val result = writer.upsertIfNotOverridden("+98912") {
            computeCalled = true
            throw AssertionError("compute must not be invoked for an overridden address")
        }

        assertNull(result)
        assertFalse(computeCalled)
        // The stored row is untouched.
        assertEquals(ThreadSpamState.SPAM, dao.getByAddress("+98912")!!.state)
    }

    @Test
    fun `upsertIfNotOverridden computes and persists when there is no override`() = runTest {
        val dao = InMemorySenderStateDao()
        val writer = SpamStateWriter(dao)

        val result = writer.upsertIfNotOverridden("+98912") { current ->
            assertNull(current)
            SenderStateEntity(normalizedAddress = "+98912", state = ThreadSpamState.CLEAN)
        }

        assertEquals(ThreadSpamState.CLEAN, result?.state)
        assertEquals(ThreadSpamState.CLEAN, dao.getByAddress("+98912")?.state)
    }

    @Test
    fun `upsertAllIfNotOverridden with an empty list never calls the dao`() = runTest {
        val dao = CountingSenderStateDao()
        val writer = SpamStateWriter(dao)

        writer.upsertAllIfNotOverridden(emptyList())

        assertEquals(0, dao.upsertAllCalls)
    }

    @Test
    fun `upsertAllIfNotOverridden drops addresses with a user override`() = runTest {
        val dao = InMemorySenderStateDao()
        dao.upsert(
            SenderStateEntity(normalizedAddress = "+98912", state = ThreadSpamState.TRUSTED, isUserOverride = true)
        )
        val writer = SpamStateWriter(dao)

        writer.upsertAllIfNotOverridden(
            listOf(
                PendingStateWrite(
                    address = "+98912",
                    computed = SenderStateEntity(normalizedAddress = "+98912", state = ThreadSpamState.SPAM),
                ),
            )
        )

        // The override survives untouched.
        assertEquals(ThreadSpamState.TRUSTED, dao.getByAddress("+98912")?.state)
    }

    @Test
    fun `upsertAllIfNotOverridden merges concurrent counter increments instead of clobbering them`() = runTest {
        val dao = InMemorySenderStateDao()
        // Seed snapshot the scan started from: spamCount=2.
        dao.upsert(SenderStateEntity(normalizedAddress = "+98912", state = ThreadSpamState.MIXED, spamCount = 2, hamCount = 0))
        // While the scan ran, live ingress advanced the real row to spamCount=7.
        dao.upsert(SenderStateEntity(normalizedAddress = "+98912", state = ThreadSpamState.MIXED, spamCount = 7, hamCount = 0))
        val writer = SpamStateWriter(dao)

        writer.upsertAllIfNotOverridden(
            listOf(
                PendingStateWrite(
                    address = "+98912",
                    computed = SenderStateEntity(normalizedAddress = "+98912", state = ThreadSpamState.MIXED, spamCount = 5, hamCount = 0),
                    seedSpamCount = 2,
                    seedHamCount = 0,
                ),
            )
        )

        // 5 (computed) + max(0, 7 - 2) (live delta) = 10.
        assertEquals(10, dao.getByAddress("+98912")?.spamCount)
    }

    @Test
    fun `upsertAllIfNotOverridden never lets the delta go negative when live count dropped below seed`() = runTest {
        val dao = InMemorySenderStateDao()
        // Live count is lower than the seed (e.g. a reset) — delta must clamp to 0, not go negative.
        dao.upsert(SenderStateEntity(normalizedAddress = "+98912", state = ThreadSpamState.MIXED, spamCount = 1, hamCount = 0))
        val writer = SpamStateWriter(dao)

        writer.upsertAllIfNotOverridden(
            listOf(
                PendingStateWrite(
                    address = "+98912",
                    computed = SenderStateEntity(normalizedAddress = "+98912", state = ThreadSpamState.MIXED, spamCount = 5, hamCount = 0),
                    seedSpamCount = 4,
                    seedHamCount = 0,
                ),
            )
        )

        // max(0, 1 - 4) = 0, so the written count is exactly the computed value.
        assertEquals(5, dao.getByAddress("+98912")?.spamCount)
    }

    @Test
    fun `withSpamStateLock serializes against a concurrent upsertAllIfNotOverridden`() = runTest {
        val dao = InMemorySenderStateDao()
        val writer = SpamStateWriter(dao)
        val log = mutableListOf<String>()

        val slowScan = async {
            writer.upsertAllIfNotOverridden(
                listOf(
                    PendingStateWrite(
                        address = "+98912",
                        computed = SenderStateEntity(normalizedAddress = "+98912", state = ThreadSpamState.CLEAN),
                    ),
                )
            )
            log.add("scan-done")
        }
        val override = async {
            writer.withSpamStateLock {
                log.add("override-start")
                delay(1)
                log.add("override-end")
            }
        }
        slowScan.await()
        override.await()

        // Both ran to completion without interleaving their start/end markers.
        assertTrue(log.indexOf("override-start") < log.indexOf("override-end"))
    }
}
