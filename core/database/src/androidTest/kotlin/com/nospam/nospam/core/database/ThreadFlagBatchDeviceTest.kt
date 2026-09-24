// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nospam.nospam.core.database.dao.SqliteArchivedDao
import com.nospam.nospam.core.database.dao.SqliteMutedDao
import com.nospam.nospam.core.database.dao.SqlitePinnedDao
import com.nospam.nospam.core.database.dao.SqliteStarredDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Batch writes on the four thread-flag DAOs (archived, pinned, starred, muted)
 * against real SQLite: `archiveAll/unarchiveAll`, `pinAll/unpinAll`,
 * `starAll/unstarAll`, `muteAll/unmuteAll`.
 *
 * Written from the spec (one transaction per batch, one `observeAll` publish per
 * batch, set semantics, empty is a no-op, larger than SQLite's bound-parameter
 * limit works) independently of the implementation, which was not read.
 *
 * Every test runs against all four DAOs through [FlagOps]; failures name the DAO.
 */
@RunWith(AndroidJUnit4::class)
class ThreadFlagBatchDeviceTest {

    /** The shape the four DAOs share, so each test covers all of them. */
    private class FlagOps(
        val name: String,
        val addAll: suspend (Collection<Long>) -> Unit,
        val removeAll: suspend (Collection<Long>) -> Unit,
        val addOne: suspend (Long) -> Unit,
        val isSet: suspend (Long) -> Boolean,
        val observe: () -> Flow<List<Long>>,
    )

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var ops: List<FlagOps>

    @Before
    fun setUp() {
        context.deleteDatabase("nospam.db")
        val helper = SqliteNoSpamOpenHelper(context)
        val archived = SqliteArchivedDao(helper)
        val pinned = SqlitePinnedDao(helper)
        val starred = SqliteStarredDao(helper)
        val muted = SqliteMutedDao(helper)
        ops = listOf(
            FlagOps("archived", archived::archiveAll, archived::unarchiveAll, archived::archive, archived::isArchived) {
                archived.observeAll().map { l -> l.map { it.threadId } }
            },
            FlagOps("pinned", pinned::pinAll, pinned::unpinAll, pinned::pin, pinned::isPinned) {
                pinned.observeAll().map { l -> l.map { it.threadId } }
            },
            FlagOps("starred", starred::starAll, starred::unstarAll, starred::star, starred::isStarred) {
                starred.observeAll().map { l -> l.map { it.threadId } }
            },
            FlagOps("muted", muted::muteAll, muted::unmuteAll, muted::mute, muted::isMuted) {
                muted.observeAll().map { l -> l.map { it.threadId } }
            },
        )
    }

    @After
    fun tearDown() {
        context.deleteDatabase("nospam.db")
    }

    private fun forEachDao(block: suspend CoroutineScope.(FlagOps) -> Unit) = runBlocking {
        ops.forEach { block(it) }
    }

    @Test
    fun addAll_sets_every_id_and_leaves_others_alone() = forEachDao { dao ->
        dao.addOne(99L)
        dao.addAll(listOf(1L, 2L, 3L))
        for (id in listOf(1L, 2L, 3L, 99L)) assertTrue("${dao.name}: $id", dao.isSet(id))
        assertFalse("${dao.name}: 4 never added", dao.isSet(4L))
        assertEquals(dao.name, setOf(1L, 2L, 3L, 99L), dao.observe().first().toSet())
    }

    @Test
    fun addAll_is_idempotent_and_never_duplicates() = forEachDao { dao ->
        dao.addOne(2L)
        dao.addAll(listOf(1L, 2L, 3L))
        dao.addAll(listOf(1L, 2L, 3L))
        dao.addAll(listOf(3L, 3L))
        val ids = dao.observe().first()
        assertEquals("${dao.name}: no duplicate rows, got $ids", ids.distinct().size, ids.size)
        assertEquals(dao.name, setOf(1L, 2L, 3L), ids.toSet())
    }

    @Test
    fun removeAll_clears_every_id_ignores_absent_ones_and_keeps_others() = forEachDao { dao ->
        dao.addAll(listOf(1L, 2L, 3L, 4L))
        dao.removeAll(listOf(1L, 3L, 500L))
        assertFalse(dao.name, dao.isSet(1L))
        assertFalse(dao.name, dao.isSet(3L))
        assertFalse(dao.name, dao.isSet(500L))
        assertEquals(dao.name, setOf(2L, 4L), dao.observe().first().toSet())
        // Removing again is harmless.
        dao.removeAll(listOf(1L, 3L))
        assertEquals(dao.name, setOf(2L, 4L), dao.observe().first().toSet())
    }

    @Test
    fun empty_collections_change_nothing() = forEachDao { dao ->
        dao.addAll(listOf(7L, 8L))
        dao.addAll(emptyList())
        dao.removeAll(emptyList())
        assertEquals(dao.name, setOf(7L, 8L), dao.observe().first().toSet())
    }

    @Test
    fun twelve_hundred_ids_round_trip() = forEachDao { dao ->
        val ids = (1L..1_200L).toList()
        dao.addAll(ids)
        val stored = dao.observe().first()
        assertEquals("${dao.name}: row count", 1_200, stored.size)
        assertEquals(dao.name, ids.toSet(), stored.toSet())
        assertTrue(dao.name, dao.isSet(1L))
        assertTrue(dao.name, dao.isSet(1_200L))

        // Remove all but the last one, including ids that were never there.
        dao.removeAll((1L..1_199L).toList() + (5_000L..5_300L).toList())
        assertEquals(dao.name, listOf(1_200L), dao.observe().first())
    }

    /**
     * The batch must reach observers as one new list: after the initial snapshot,
     * one `addAll`/`removeAll` produces exactly one emission, and no emission ever
     * contains only part of the batch. The collector runs on `Unconfined` so each
     * publish is delivered inline rather than conflated away.
     */
    @Test
    fun observeAll_emits_exactly_one_new_list_per_batch_call() = forEachDao { dao ->
        dao.addOne(1_000L)
        val seen = Collections.synchronizedList(mutableListOf<Set<Long>>())
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            dao.observe().collect { seen += it.toSet() }
        }
        withTimeout(5_000) { while (seen.isEmpty()) delay(10) }
        delay(200) // let any trailing initial emissions settle
        val baseline = seen.size
        assertEquals("${dao.name}: initial snapshot", setOf(1_000L), seen.last())

        val batch = (1L..50L).toList()
        dao.addAll(batch)
        delay(300)
        val afterAdd = seen.drop(baseline)
        assertEquals("${dao.name}: emissions after addAll: ${afterAdd.map { it.size }}", 1, afterAdd.size)
        assertEquals(dao.name, batch.toSet() + 1_000L, afterAdd.single())

        val base2 = seen.size
        dao.removeAll(batch)
        delay(300)
        val afterRemove = seen.drop(base2)
        assertEquals("${dao.name}: emissions after removeAll: ${afterRemove.map { it.size }}", 1, afterRemove.size)
        assertEquals(dao.name, setOf(1_000L), afterRemove.single())

        job.cancel()
    }
}
