package com.nospam.nospam.core.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nospam.nospam.core.database.SqliteNoSpamOpenHelper
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a real device or emulator (`./gradlew :core:database:connectedDebugAndroidTest`). */
@RunWith(AndroidJUnit4::class)
class SqliteMessageVerdictDaoTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var dao: SqliteMessageVerdictDao

    @Before
    fun setUp() {
        context.deleteDatabase("nospam.db")
        dao = SqliteMessageVerdictDao(SqliteNoSpamOpenHelper(context))
    }

    @After
    fun tearDown() {
        context.deleteDatabase("nospam.db")
    }

    @Test
    fun insert_then_getByMessageId_round_trips_a_null_userLabel() = runTest {
        dao.insert(MessageVerdictEntity(messageId = 1, threadId = 1, normalizedAddress = "+98912", isSpam = true, score = 1.0, createdAt = 100L))
        val found = dao.getByMessageId(1)
        assertEquals(true, found?.isSpam)
        assertNull(found?.userLabel)
    }

    @Test
    fun insert_replaces_by_messageId() = runTest {
        dao.insert(MessageVerdictEntity(messageId = 1, threadId = 1, normalizedAddress = "+98912", isSpam = true, score = 1.0, createdAt = 100L))
        dao.insert(MessageVerdictEntity(messageId = 1, threadId = 1, normalizedAddress = "+98912", isSpam = false, score = -1.0, createdAt = 200L))
        assertEquals(1, dao.getAllMessageIds().size)
        assertEquals(false, dao.getByMessageId(1)?.isSpam)
    }

    @Test
    fun insertAll_is_one_batch_and_a_second_call_replaces_colliding_ids() = runTest {
        dao.insertAll(
            listOf(
                MessageVerdictEntity(messageId = 1, threadId = 1, normalizedAddress = "+98912", isSpam = true, score = 1.0, createdAt = 100L),
                MessageVerdictEntity(messageId = 2, threadId = 1, normalizedAddress = "+98912", isSpam = false, score = -1.0, createdAt = 200L),
            )
        )
        assertEquals(setOf(1L, 2L), dao.getAllMessageIds())
    }

    @Test
    fun insertAll_with_an_empty_list_is_a_no_op() = runTest {
        dao.insertAll(emptyList())
        assertTrue(dao.getAllMessageIds().isEmpty())
    }

    @Test
    fun getByThread_returns_only_that_threads_rows() = runTest {
        dao.insert(MessageVerdictEntity(messageId = 1, threadId = 1, normalizedAddress = "+98911", isSpam = true, score = 1.0))
        dao.insert(MessageVerdictEntity(messageId = 2, threadId = 2, normalizedAddress = "+98922", isSpam = true, score = 1.0))
        assertEquals(listOf(1L), dao.getByThread(1).map { it.messageId })
    }

    @Test
    fun deleteByThread_removes_every_row_for_that_thread() = runTest {
        dao.insert(MessageVerdictEntity(messageId = 1, threadId = 1, normalizedAddress = "+98911", isSpam = true, score = 1.0))
        dao.insert(MessageVerdictEntity(messageId = 2, threadId = 1, normalizedAddress = "+98911", isSpam = false, score = -1.0))
        dao.deleteByThread(1)
        assertTrue(dao.getByThread(1).isEmpty())
    }

    @Test
    fun updateUserLabel_touches_only_the_named_row() = runTest {
        dao.insert(MessageVerdictEntity(messageId = 1, threadId = 1, normalizedAddress = "+98911", isSpam = true, score = 1.0))
        dao.insert(MessageVerdictEntity(messageId = 2, threadId = 1, normalizedAddress = "+98911", isSpam = true, score = 1.0))
        dao.updateUserLabel(1, false)
        assertEquals(false, dao.getByMessageId(1)?.userLabel)
        assertNull(dao.getByMessageId(2)?.userLabel)
    }

    @Test
    fun deleteAutoOlderThan_prunes_only_auto_ham_keeping_spam_and_user_labels_forever() = runTest {
        val old = 1_000L
        // Auto spam older than the cutoff must survive forever (per-message marker).
        dao.insert(MessageVerdictEntity(messageId = 1, threadId = 1, normalizedAddress = "+98911", isSpam = true, score = 1.0, createdAt = old))
        // Auto ham older than the cutoff is the only thing pruned.
        dao.insert(MessageVerdictEntity(messageId = 2, threadId = 1, normalizedAddress = "+98911", isSpam = false, score = -1.0, createdAt = old))
        // A user-labelled row is never pruned regardless of age or isSpam.
        dao.insert(MessageVerdictEntity(messageId = 3, threadId = 1, normalizedAddress = "+98911", isSpam = false, score = -1.0, createdAt = old, userLabel = false))
        // Recent ham is not old enough to prune.
        dao.insert(MessageVerdictEntity(messageId = 4, threadId = 1, normalizedAddress = "+98911", isSpam = false, score = -1.0, createdAt = 9_000L))

        val removed = dao.deleteAutoOlderThan(2_000L)

        assertEquals(1, removed)
        assertEquals(setOf(1L, 3L, 4L), dao.getAllMessageIds())
    }

    @Test
    fun observeAll_is_ordered_by_createdAt_descending() = runTest {
        dao.insert(MessageVerdictEntity(messageId = 1, threadId = 1, normalizedAddress = "+98911", isSpam = true, score = 1.0, createdAt = 1L))
        dao.insert(MessageVerdictEntity(messageId = 2, threadId = 1, normalizedAddress = "+98911", isSpam = true, score = 1.0, createdAt = 3L))
        dao.insert(MessageVerdictEntity(messageId = 3, threadId = 1, normalizedAddress = "+98911", isSpam = true, score = 1.0, createdAt = 2L))
        assertEquals(listOf(2L, 3L, 1L), dao.observeAll().first().map { it.messageId })
    }
}
