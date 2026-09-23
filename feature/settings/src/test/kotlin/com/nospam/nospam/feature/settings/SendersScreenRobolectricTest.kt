// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nospam.nospam.core.data.BlocklistRepository
import com.nospam.nospam.core.data.SpamRepository
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Blocked and allowed senders" page on the JVM (CLAUDE.md §9).
 *
 * Written from the feature spec independently of the implementation: the
 * screen's and view model's bodies were not read. Phone numbers are matched as
 * substrings because they may be wrapped in bidi isolate characters;
 * alphanumeric sender ids are matched exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class SendersScreenRobolectricTest {
    @get:Rule val rule = createComposeRule()

    private val db = NoSpamDatabase.inMemory()
    private val tele = FakeTelephonyDataSource()
    private val blocklist = BlocklistRepository(db, telephony = tele)
    private val spam = SpamRepository(db, FakeSpamClassifier.alwaysHam())

    private fun allow(address: String, spamCount: Int = 0, updatedAt: Long = 1L) = runBlocking {
        db.senderStateDao.upsert(
            SenderStateEntity(address, ThreadSpamState.TRUSTED, spamCount = spamCount, isUserOverride = true, updatedAt = updatedAt)
        )
    }

    private fun show() {
        val vm = SendersViewModel(blocklist, spam, tele)
        rule.setContent { SendersScreen(viewModel = vm) }
    }

    private fun waitForText(text: String, substring: Boolean = false) =
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForTextGone(text: String, substring: Boolean = false) =
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty()
        }

    @Test fun `shows the explanation and both sections`() {
        show()
        rule.onNodeWithText("A block or an allow belongs to the sender", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Blocked").assertIsDisplayed()
        rule.onNodeWithText("Marked not spam").assertIsDisplayed()
    }

    @Test fun `empty sections show their empty text`() {
        show()
        waitForText("No blocked senders")
        waitForText("No senders marked as not spam")
        rule.onNodeWithText("No blocked senders").assertIsDisplayed()
        rule.onNodeWithText("No senders marked as not spam").assertIsDisplayed()
    }

    @Test fun `a blocked sender without a contact shows its address and an Unblock button`() {
        runBlocking { blocklist.block("NSTEST_A") }
        show()
        waitForText("NSTEST_A")
        rule.onNodeWithText("NSTEST_A").assertIsDisplayed()
        rule.onNodeWithText("Unblock").assertIsDisplayed()
        rule.onNodeWithText("No senders marked as not spam").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("No blocked senders").fetchSemanticsNodes().size)
    }

    @Test fun `a known contact shows its name with the number beneath`() {
        tele.contacts["+15550001"] = Participant("+15550001", displayName = "Ada Lovelace")
        runBlocking { blocklist.block("+15550001") }
        show()
        waitForText("Ada Lovelace")
        rule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        rule.onNodeWithText("+15550001", substring = true).assertIsDisplayed()
    }

    @Test fun `an allowed sender shows its address and a Remove button`() {
        allow("+15550002")
        show()
        waitForText("+15550002", substring = true)
        rule.onNodeWithText("+15550002", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Remove").assertIsDisplayed()
        rule.onNodeWithText("No blocked senders").assertIsDisplayed()
    }

    @Test fun `tapping Unblock removes the row without a confirmation`() {
        runBlocking {
            blocklist.block("NSTEST_A")
        }
        show()
        waitForText("NSTEST_A")

        rule.onNodeWithText("Unblock").performClick()

        waitForTextGone("NSTEST_A")
        waitForText("No blocked senders")
        assertFalse(runBlocking { blocklist.isBlocked("NSTEST_A") })
    }

    @Test fun `tapping Remove removes the allowed row without a confirmation`() {
        allow("NSTEST_B", spamCount = 1)
        show()
        waitForText("NSTEST_B")

        rule.onNodeWithText("Remove").performClick()

        waitForTextGone("NSTEST_B")
        waitForText("No senders marked as not spam")
        assertEquals(ThreadSpamState.MIXED, runBlocking { db.senderStateDao.getByAddress("NSTEST_B") }?.state)
    }

    @Test fun `unblocking one of two senders removes only that row`() {
        runBlocking {
            blocklist.block("NSTEST_A")
            blocklist.block("NSTEST_C")
        }
        show()
        waitForText("NSTEST_A")
        waitForText("NSTEST_C")
        rule.onAllNodes(hasText("Unblock") and hasClickAction()).assertCountEquals(2)

        // Which button belongs to which row is layout detail; after one click
        // exactly one of the two senders must remain.
        rule.onAllNodes(hasText("Unblock") and hasClickAction())[0].performClick()

        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("Unblock") and hasClickAction()).fetchSemanticsNodes().size == 1
        }
        val remaining = listOf("NSTEST_A", "NSTEST_C").filter {
            rule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, remaining.size)
        assertEquals(listOf(remaining.single()), runBlocking { listOf("NSTEST_A", "NSTEST_C").filter { blocklist.isBlocked(it) } })
    }
}
