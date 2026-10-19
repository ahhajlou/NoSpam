// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performTouchInput
import com.nospam.nospam.core.data.SpamRepository
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.MessageVerdictEntity
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.testing.FakeSpamClassifier
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Links, phone numbers and email addresses in message bubbles: what a tap and a
 * long press do, and which messages have none. Where the links are is
 * [MessageLinksTest]'s business.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class MessageLinksScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val thread = ThreadId(9)
    private val sender = "+15550009"

    private fun received(id: Long, body: String) = Message(MessageId(id), thread, sender, body, id, MessageType.INBOX, true)
    private fun sent(id: Long, body: String) = Message(MessageId(id), thread, sender, body, id, MessageType.SENT, true)

    private fun open(
        vararg messages: Message,
        spamRepository: SpamRepository? = null,
        onOpenConversation: (String) -> Unit = {},
    ) {
        val telephony = FakeTelephonyDataSource().apply { emitMessages(thread, messages.toList()) }
        val viewModel = ThreadViewModel(telephony, spamRepository = spamRepository)
        rule.setContent {
            ThreadScreen(threadId = thread.value, onOpenConversation = onOpenConversation, viewModel = viewModel)
        }
    }

    /** A spam store holding [state] for the sender, and a suspected verdict for [flagged]. */
    private fun spamStore(state: ThreadSpamState, userOverride: Boolean = false, flagged: Long? = null): SpamRepository {
        val db = NoSpamDatabase.inMemory()
        runBlocking {
            db.senderStateDao.upsert(SenderStateEntity(sender, state, spamCount = 2, isUserOverride = userOverride))
            if (flagged != null) {
                db.messageVerdictDao.insert(MessageVerdictEntity(flagged, thread.value, sender, isSpam = true, score = 0.9, createdAt = 1L))
            }
        }
        return SpamRepository(db, FakeSpamClassifier.alwaysHam())
    }

    private val linked = SemanticsMatcher("has links") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.getLinkAnnotations(0, it.length).isNotEmpty() }
    }

    /** Links are found off the main thread, so they arrive after the text. */
    private fun awaitLinks(body: String) = rule.waitUntil(5_000) {
        rule.onAllNodes(hasText(body) and linked).fetchSemanticsNodes().isNotEmpty()
    }

    private fun assertNoLinks(body: String) {
        rule.onNodeWithText(body).assertIsDisplayed()
        assertTrue("\"$body\" has links", rule.onAllNodes(hasText(body) and linked).fetchSemanticsNodes().isEmpty())
    }

    private fun nextStarted(): Intent? = shadowOf(rule.activity).nextStartedActivity

    @Test fun `a tap on a link opens it in a browser`() {
        open(received(1, "see example.com/abc"))
        awaitLinks("see example.com/abc")
        rule.onNodeWithText("see example.com/abc").performFirstLinkClick()
        val intent = nextStarted()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("http://example.com/abc", intent.dataString)
        // Only an app that declares itself a browser may take it.
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test fun `a long press on a link selects the message and opens nothing`() {
        // The whole body is the link, so the press lands on it.
        open(received(1, "example.com/abc"))
        awaitLinks("example.com/abc")
        rule.onNodeWithText("example.com/abc").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        assertNull(nextStarted())
    }

    @Test fun `while selecting, a tap on a link selects its message instead`() {
        open(received(1, "first message"), received(2, "see example.com/abc"))
        awaitLinks("see example.com/abc")
        rule.onNodeWithText("first message").performTouchInput { longClick() }
        rule.onNodeWithText("see example.com/abc").performFirstLinkClick()
        rule.waitForIdle()
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        assertNull(nextStarted())
    }

    @Test fun `a phone number offers a call, which goes to the dialer`() {
        open(received(1, "call +1 650-253-0000 today"))
        awaitLinks("call +1 650-253-0000 today")
        rule.onNodeWithText("call +1 650-253-0000 today").performFirstLinkClick()
        rule.onNodeWithText("Send message").assertIsDisplayed()
        rule.onNodeWithText("Call").performClick()
        val intent = nextStarted()!!
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel", intent.data!!.scheme)
        assertEquals("+16502530000", intent.data!!.schemeSpecificPart)
    }

    @Test fun `send message on a phone number opens that number's conversation`() {
        var opened: String? = null
        open(received(1, "call +1 650-253-0000 today"), onOpenConversation = { opened = it })
        awaitLinks("call +1 650-253-0000 today")
        rule.onNodeWithText("call +1 650-253-0000 today").performFirstLinkClick()
        rule.onNodeWithText("Send message").performClick()
        rule.waitForIdle()
        assertEquals("+16502530000", opened)
    }

    @Test fun `copy on a phone number copies it as written`() {
        open(received(1, "call +1 650-253-0000 today"))
        awaitLinks("call +1 650-253-0000 today")
        rule.onNodeWithText("call +1 650-253-0000 today").performFirstLinkClick()
        rule.onNodeWithText("Copy").performClick()
        val clipboard = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("+1 650-253-0000", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun `an email address offers a new email to it`() {
        open(received(1, "write to support@example.com"))
        awaitLinks("write to support@example.com")
        rule.onNodeWithText("write to support@example.com").performFirstLinkClick()
        rule.onNodeWithText("Email").performClick()
        val intent = nextStarted()!!
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data!!.scheme)
        assertEquals("support@example.com", intent.data!!.schemeSpecificPart)
    }

    @Test fun `a link nothing on the phone can open says so`() {
        shadowOf(rule.activity.application).checkActivities(true)
        open(received(1, "see example.com/abc"))
        awaitLinks("see example.com/abc")
        rule.onNodeWithText("see example.com/abc").performFirstLinkClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("No app on this phone can open that").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun `a message labeled suspected spam keeps its links as text`() {
        open(
            received(1, "win at example.com/prize"),
            received(2, "see example.com/abc"),
            spamRepository = spamStore(ThreadSpamState.MIXED, flagged = 1),
        )
        // The other message gets its links, so the spam checks have answered.
        awaitLinks("see example.com/abc")
        rule.onNodeWithText("Suspected spam").assertIsDisplayed()
        assertNoLinks("win at example.com/prize")
    }

    @Test fun `a sender in Spam has no links, but the user's own messages keep theirs`() {
        open(
            received(1, "win at example.com/prize"),
            sent(2, "is example.com/abc yours?"),
            spamRepository = spamStore(ThreadSpamState.SPAM),
        )
        // Both messages' links are found in one pass, and the banner means the
        // sender's state has answered: a link on the first would be there by now.
        awaitLinks("is example.com/abc yours?")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("The on-device filter moved this conversation to Spam.").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        assertNoLinks("win at example.com/prize")
    }
}
