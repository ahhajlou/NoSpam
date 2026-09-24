// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Written from the task spec, independently of RealTelephonyDataSource's own
 * implementation of `changedThreadIds` / `inboxChanged`. The spec: a thread is
 * "changed" if it is new to `current` or differs from its cached version in
 * any field; a thread that disappeared from `current` is never in
 * `changedThreadIds`, but its disappearance alone must still make
 * `inboxChanged` true (the regression this guards: a deleted conversation
 * stayed in the inbox until restart because only threads still present were
 * compared). Order must not matter for either function.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboxCacheDecisionTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val dataSource by lazy { RealTelephonyDataSource(context) }

    private fun meta(
        id: Long,
        date: Long = 1_000L,
        count: Int = 1,
        snippet: String = "hello",
        read: Boolean = true,
    ) = RealTelephonyDataSource.ThreadMeta(id = id, date = date, count = count, snippet = snippet, read = read)

    @Test fun `null cache means every current id is changed`() {
        val current = listOf(meta(1), meta(2), meta(3))

        assertEquals(setOf(1L, 2L, 3L), dataSource.changedThreadIds(null, current))
    }

    @Test fun `null cache means the inbox changed`() {
        assertTrue(dataSource.inboxChanged(null, listOf(meta(1))))
    }

    @Test fun `identical lists mean nothing changed`() {
        val cached = listOf(meta(1), meta(2))
        val current = listOf(meta(1), meta(2))

        assertEquals(emptySet<Long>(), dataSource.changedThreadIds(cached, current))
        assertFalse(dataSource.inboxChanged(cached, current))
    }

    @Test fun `a different date on one thread is a change`() {
        val cached = listOf(meta(1, date = 1_000L), meta(2))
        val current = listOf(meta(1, date = 2_000L), meta(2))

        assertEquals(setOf(1L), dataSource.changedThreadIds(cached, current))
        assertTrue(dataSource.inboxChanged(cached, current))
    }

    @Test fun `a different count on one thread is a change`() {
        val cached = listOf(meta(1, count = 1), meta(2))
        val current = listOf(meta(1, count = 2), meta(2))

        assertEquals(setOf(1L), dataSource.changedThreadIds(cached, current))
        assertTrue(dataSource.inboxChanged(cached, current))
    }

    @Test fun `a different snippet on one thread is a change`() {
        val cached = listOf(meta(1, snippet = "hi"), meta(2))
        val current = listOf(meta(1, snippet = "bye"), meta(2))

        assertEquals(setOf(1L), dataSource.changedThreadIds(cached, current))
        assertTrue(dataSource.inboxChanged(cached, current))
    }

    @Test fun `a different read flag on one thread is a change`() {
        val cached = listOf(meta(1, read = true), meta(2))
        val current = listOf(meta(1, read = false), meta(2))

        assertEquals(setOf(1L), dataSource.changedThreadIds(cached, current))
        assertTrue(dataSource.inboxChanged(cached, current))
    }

    @Test fun `a thread added to current is a change`() {
        val cached = listOf(meta(1))
        val current = listOf(meta(1), meta(2))

        assertEquals(setOf(2L), dataSource.changedThreadIds(cached, current))
        assertTrue(dataSource.inboxChanged(cached, current))
    }

    @Test fun `a thread removed with the rest unchanged is not in changedThreadIds`() {
        val cached = listOf(meta(1), meta(2))
        val current = listOf(meta(1))

        assertEquals(emptySet<Long>(), dataSource.changedThreadIds(cached, current))
    }

    @Test fun `a thread removed with the rest unchanged still marks the inbox changed`() {
        val cached = listOf(meta(1), meta(2))
        val current = listOf(meta(1))

        assertTrue(dataSource.inboxChanged(cached, current))
    }

    @Test fun `a simultaneous removal and addition reports only the addition as changed`() {
        val cached = listOf(meta(1), meta(2))
        val current = listOf(meta(1), meta(3))

        assertEquals(setOf(3L), dataSource.changedThreadIds(cached, current))
        assertTrue(dataSource.inboxChanged(cached, current))
    }

    @Test fun `reordering the same threads is not a change`() {
        val cached = listOf(meta(1), meta(2), meta(3))
        val current = listOf(meta(3), meta(1), meta(2))

        assertEquals(emptySet<Long>(), dataSource.changedThreadIds(cached, current))
        assertFalse(dataSource.inboxChanged(cached, current))
    }

    @Test fun `two empty lists are not a change`() {
        assertEquals(emptySet<Long>(), dataSource.changedThreadIds(emptyList(), emptyList()))
        assertFalse(dataSource.inboxChanged(emptyList(), emptyList()))
    }

    @Test fun `everything deleted leaves current empty and still marks the inbox changed`() {
        val cached = listOf(meta(1), meta(2))
        val current = emptyList<RealTelephonyDataSource.ThreadMeta>()

        assertEquals(emptySet<Long>(), dataSource.changedThreadIds(cached, current))
        assertTrue(dataSource.inboxChanged(cached, current))
    }
}
