// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.data

import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.model.RawMessage
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.testing.FakeSpamClassifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SpamRepository, split out of the original RepositoryTest (Wave 2A). */
class SpamRepositoryTest {

    @Test fun `markNotSpam overrides a classifier-derived verdict`() = runTest {
        val db = NoSpamDatabase.inMemory()
        val repo = SpamRepository(db, FakeSpamClassifier.alwaysSpam())
        repo.classifyAndStore(ThreadId(5), RawMessage("x", "spam", 0L))
        assertTrue(repo.getVerdict(ThreadId(5))!!.isSpam)

        repo.markNotSpam(ThreadId(5), "+98912")
        val v = repo.getVerdict(ThreadId(5))!!
        assertFalse(v.isSpam)
        assertTrue(v.isUserOverride)
    }
}
