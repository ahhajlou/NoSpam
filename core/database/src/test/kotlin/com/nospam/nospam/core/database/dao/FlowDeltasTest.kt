// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.entity.BlocklistEntity
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.database.entity.SpamVerdictEntity
import com.nospam.nospam.core.database.entity.StarredThreadEntity
import com.nospam.nospam.core.model.ThreadSpamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `Sqlite*Dao` classes need `android.database` and cannot run off-device, so
 * the delta each one applies to its observable snapshot lives in `FlowDeltas` and
 * is covered here instead. Every case below states the SQL behaviour it pins.
 */
class FlowDeltasTest {

    // ---- blocklist ----

    @Test fun `blocklist upsert replaces the row with the same address`() {
        val existing = listOf(BlocklistEntity(id = 1, address = "+98912", createdAt = 100))
        val result = existing.withEntry(BlocklistEntity(id = 2, address = "+98912", reason = "spam", createdAt = 200))

        assertEquals(1, result.size)
        assertEquals(2L, result[0].id)
        assertEquals("spam", result[0].reason)
    }

    @Test fun `blocklist stays ordered by createdAt descending`() {
        val result = listOf(
            BlocklistEntity(id = 1, address = "a", createdAt = 300),
            BlocklistEntity(id = 2, address = "b", createdAt = 100),
        ).withEntry(BlocklistEntity(id = 3, address = "c", createdAt = 200))

        assertEquals(listOf(300L, 200L, 100L), result.map { it.createdAt })
    }

    @Test fun `blocklist delete removes by id and by address`() {
        val rows = listOf(
            BlocklistEntity(id = 1, address = "a", createdAt = 1),
            BlocklistEntity(id = 2, address = "b", createdAt = 2),
        )
        assertEquals(listOf(2L), rows.withoutId(1).map { it.id })
        assertEquals(listOf("a"), rows.withoutAddress("b").map { it.address })
    }

    // ---- message_verdict ----

    private fun verdict(
        id: Long,
        threadId: Long = 1,
        isSpam: Boolean = true,
        createdAt: Long = 0,
        userLabel: Boolean? = null,
    ) = MessageVerdictEntity(id, threadId, "+98912", isSpam, 1.0, createdAt, userLabel)

    @Test fun `verdict upsert replaces by messageId and re-sorts newest first`() {
        val result = listOf(verdict(1, createdAt = 100), verdict(2, createdAt = 300))
            .withVerdict(verdict(1, createdAt = 400, isSpam = false))

        assertEquals(2, result.size)
        assertEquals(listOf(400L, 300L), result.map { it.createdAt })
        assertEquals(false, result.first { it.messageId == 1L }.isSpam)
    }

    @Test fun `batched verdict upsert replaces every colliding id at once`() {
        val result = listOf(verdict(1, createdAt = 100), verdict(2, createdAt = 200))
            .withVerdicts(listOf(verdict(2, createdAt = 500), verdict(3, createdAt = 50)))

        assertEquals(listOf(1L, 2L, 3L), result.map { it.messageId }.sorted())
        assertEquals(500L, result.first { it.messageId == 2L }.createdAt)
        assertEquals(listOf(500L, 100L, 50L), result.map { it.createdAt })
    }

    @Test fun `an empty batch is a no-op`() {
        val rows = listOf(verdict(1))
        assertSame(rows, rows.withVerdicts(emptyList()))
    }

    @Test fun `verdicts are removed by thread`() {
        val result = listOf(verdict(1, threadId = 7), verdict(2, threadId = 8)).withoutThread(7)
        assertEquals(listOf(2L), result.map { it.messageId })
    }

    @Test fun `retention prunes only old auto ham`() {
        val cutoff = 1_000L
        val rows = listOf(
            verdict(1, isSpam = false, createdAt = 500),                    // old auto ham -> pruned
            verdict(2, isSpam = true, createdAt = 500),                     // old spam -> kept, it is the evidence
            verdict(3, isSpam = false, createdAt = 500, userLabel = false), // user-labelled -> kept
            verdict(4, isSpam = false, createdAt = 5_000),                  // recent ham -> kept
        )

        assertEquals(listOf(2L, 3L, 4L), rows.prunedAutoHamBefore(cutoff).map { it.messageId })
    }

    @Test fun `a user label is applied to one row only`() {
        val result = listOf(verdict(1), verdict(2)).withUserLabel(1, false)

        assertEquals(false, result.first { it.messageId == 1L }.userLabel)
        assertEquals(null, result.first { it.messageId == 2L }.userLabel)
    }

    // ---- sender_state ----

    private fun state(address: String, spamCount: Int = 0, override: Boolean = false) =
        SenderStateEntity(address, ThreadSpamState.CLEAN, spamCount, 0, override, 0)

    @Test fun `sender state upsert replaces by normalized address`() {
        val result = listOf(state("+98912", spamCount = 1)).withState(state("+98912", spamCount = 2))

        assertEquals(1, result.size)
        assertEquals(2, result[0].spamCount)
    }

    @Test fun `batched sender state upsert keeps untouched rows`() {
        val result = listOf(state("a", 1), state("b", 1)).withStates(listOf(state("b", 9), state("c", 3)))

        assertEquals(setOf("a", "b", "c"), result.map { it.normalizedAddress }.toSet())
        assertEquals(9, result.first { it.normalizedAddress == "b" }.spamCount)
    }

    @Test fun `sender state is removed by address`() {
        assertEquals(listOf("b"), listOf(state("a"), state("b")).withoutAddress("a").map { it.normalizedAddress })
    }

    // ---- spam_verdict: the snapshot only ever holds isSpam = 1 rows ----

    private fun spam(threadId: Long, isSpam: Boolean = true, override: Boolean = false, updatedAt: Long = 0) =
        SpamVerdictEntity(threadId, isSpam, 1.0, override, updatedAt)

    @Test fun `a verdict flipped to ham leaves the spam snapshot`() {
        val result = listOf(spam(1), spam(2)).withSpamVerdict(spam(1, isSpam = false))
        assertEquals(listOf(2L), result.map { it.threadId })
    }

    @Test fun `a verdict still spam is replaced in place`() {
        val result = listOf(spam(1, updatedAt = 100)).withSpamVerdict(spam(1, updatedAt = 900))
        assertEquals(1, result.size)
        assertEquals(900L, result[0].updatedAt)
    }

    @Test fun `clearing auto spam keeps user overrides`() {
        val result = listOf(spam(1, override = true), spam(2)).withoutAutoSpam()
        assertEquals(listOf(1L), result.map { it.threadId })
        assertTrue(result.all { it.isUserOverride })
    }

    @Test fun `auto spam retention spares overrides and recent rows`() {
        val result = listOf(
            spam(1, updatedAt = 100),                   // old auto -> pruned
            spam(2, override = true, updatedAt = 100),  // old override -> kept
            spam(3, updatedAt = 9_000),                 // recent auto -> kept
        ).withoutAutoSpamBefore(1_000)

        assertEquals(listOf(2L, 3L), result.map { it.threadId })
    }

    // ---- threadId-keyed flag tables ----

    @Test fun `adding a thread twice is idempotent, matching CONFLICT_IGNORE`() {
        val once = emptyList<StarredThreadEntity>().withThread(7)
        assertEquals(listOf(7L), once.withThread(7).map { it.threadId })
    }

    @Test fun `removing a thread drops exactly that row`() {
        val rows = listOf(StarredThreadEntity(7), StarredThreadEntity(8))
        assertEquals(listOf(8L), rows.withoutThread(7).map { it.threadId })
    }
}
