package com.nospam.nospam.core.database.dao

import com.nospam.nospam.core.database.entity.MutedThreadEntity
import com.nospam.nospam.core.database.entity.PinnedThreadEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PinnedThreadEntity and MutedThreadEntity share the same threadId-keyed flag
 * table delta shape as Archived/Starred (FlowDeltas.kt), but had no dedicated
 * test -- named coverage gap in the Wave 2A task brief.
 */
class FlowDeltasPinnedMutedTest {

    @Test fun `pinning a thread twice is idempotent, matching CONFLICT_IGNORE`() {
        val once = emptyList<PinnedThreadEntity>().withThread(7)
        assertEquals(listOf(7L), once.withThread(7).map { it.threadId })
    }

    @Test fun `unpinning drops exactly that thread`() {
        val rows = listOf(PinnedThreadEntity(7), PinnedThreadEntity(8))
        assertEquals(listOf(8L), rows.withoutThread(7).map { it.threadId })
    }

    @Test fun `unpinning a thread not in the list is a no-op`() {
        val rows = listOf(PinnedThreadEntity(8))
        assertEquals(listOf(8L), rows.withoutThread(7).map { it.threadId })
    }

    @Test fun `muting a thread twice is idempotent, matching CONFLICT_IGNORE`() {
        val once = emptyList<MutedThreadEntity>().withThread(7)
        assertEquals(listOf(7L), once.withThread(7).map { it.threadId })
    }

    @Test fun `unmuting drops exactly that thread`() {
        val rows = listOf(MutedThreadEntity(7), MutedThreadEntity(8))
        assertEquals(listOf(8L), rows.withoutThread(7).map { it.threadId })
    }

    @Test fun `unmuting a thread not in the list is a no-op`() {
        val rows = listOf(MutedThreadEntity(8))
        assertEquals(listOf(8L), rows.withoutThread(7).map { it.threadId })
    }
}
