// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.nospam.nospam.core.data.ConversationsRepository
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.database.entity.SenderStateEntity
import com.nospam.nospam.core.model.Conversation
import com.nospam.nospam.core.model.Participant
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.model.ThreadSpamState
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Written from the task-brief spec for the loading-state change to
 * ArchivedViewModel/SpamViewModel (StateFlow<List<Conversation>?>, null until
 * the first repository emission), independently of ArchivedScreen.kt,
 * SpamScreen.kt, ConversationList.kt and SpamViewModel.kt/ArchivedViewModel.kt's
 * own source -- this file was written without opening any of them. It only
 * relies on the public constructors/strings documented in the brief.
 *
 * See CLAUDE.md §9 for why these run through Robolectric and need the qualifiers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ListLoadingRobolectricTest {

    @get:Rule val rule = createComposeRule()

    // Dispatchers.Main is pinned to this scheduler and NOT advanced until a test
    // asks for it: this is the seam that holds the first repository emission
    // back, so the loading state (rule 3) can be observed deterministically
    // instead of racing composition against a real dispatcher.
    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    @Before fun setUp() { Dispatchers.setMain(mainDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun conv(id: Long, address: String, snippet: String) = Conversation(
        threadId = ThreadId(id),
        participants = listOf(Participant(address)),
        snippet = snippet,
        date = 1L,
        messageCount = 1,
        read = true,
    )

    /**
     * Repeatedly drains the Main scheduler and lets Compose settle. The
     * repository's spam/archived queries hop onto a real dispatcher internally
     * (ConversationsRepository.observeSpam/observeArchived), so a single
     * advanceUntilIdle() is not guaranteed to see the value land back on the
     * (virtual) Main dispatcher that feeds the ViewModel's StateFlow -- this
     * polls for it instead of asserting on a single race-prone advance.
     */
    private fun settleUntilLoaded(current: () -> List<Conversation>?, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (current() == null && System.currentTimeMillis() < deadline) {
            scheduler.advanceUntilIdle()
            rule.waitForIdle()
            Thread.sleep(10)
        }
        scheduler.advanceUntilIdle()
        rule.waitForIdle()
    }

    // ---- Archived screen ----

    @Test
    fun `archived screen shows no empty state while loading, then the empty state once loaded empty`() {
        val telephony = FakeTelephonyDataSource(listOf(conv(1, "+98911", "hi")))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory())
        val vm = ArchivedViewModel(repo)

        rule.setContent { ArchivedScreen(title = "Archived", viewModel = vm) }

        // Rule 3: nothing has loaded yet (Main is paused) -- no empty-state text.
        rule.onNodeWithText("Archive is empty").assertDoesNotExist()
        rule.onNodeWithText("Messages you archive will appear here.").assertDoesNotExist()

        // Rule 4: loaded, nothing archived -> empty-state text shown.
        settleUntilLoaded({ vm.conversations.value })
        rule.onNodeWithText("Archive is empty").assertIsDisplayed()
    }

    @Test
    fun `archived screen shows the archived row once loaded with items`() {
        val telephony = FakeTelephonyDataSource(listOf(conv(1, "+98911", "Archived row snippet")))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory())
        runBlocking { repo.archive(ThreadId(1)) }
        val vm = ArchivedViewModel(repo)

        rule.setContent { ArchivedScreen(title = "Archived", viewModel = vm) }

        // Rule 3: nothing has loaded yet -- no empty state, no row.
        rule.onNodeWithText("Archive is empty").assertDoesNotExist()
        rule.onNodeWithText("Archived row snippet").assertDoesNotExist()

        // Rule 4: loaded with one archived item -> the row is shown, not the empty state.
        settleUntilLoaded({ vm.conversations.value })
        rule.onNodeWithText("Archive is empty").assertDoesNotExist()
        rule.onNodeWithText("Archived row snippet").assertIsDisplayed()
    }

    // ---- Spam screen ----

    @Test
    fun `spam screen shows no empty state while loading, then the empty state once loaded empty`() {
        val telephony = FakeTelephonyDataSource(listOf(conv(1, "+98911", "hi")))
        val repo = ConversationsRepository(telephony, NoSpamDatabase.inMemory())
        val vm = SpamViewModel(repo)

        rule.setContent { SpamScreen(title = "Spam & blocked", viewModel = vm) }

        // Rule 3: nothing has loaded yet -- no empty-state text.
        rule.onNodeWithText("No spam").assertDoesNotExist()
        rule.onNodeWithText("Conversations flagged as spam and senders you block appear here.").assertDoesNotExist()

        // Rule 4: loaded, nothing flagged -> empty-state text shown.
        settleUntilLoaded({ vm.conversations.value })
        rule.onNodeWithText("No spam").assertIsDisplayed()
    }

    @Test
    fun `spam screen shows the flagged row once loaded with items`() {
        val db = NoSpamDatabase.inMemory()
        val telephony = FakeTelephonyDataSource(listOf(conv(1, "+98911", "Spam row snippet")))
        val repo = ConversationsRepository(telephony, db)
        runBlocking {
            db.senderStateDao.upsert(SenderStateEntity(normalizedAddress = "+98911", state = ThreadSpamState.SPAM))
        }
        val vm = SpamViewModel(repo)

        rule.setContent { SpamScreen(title = "Spam & blocked", viewModel = vm) }

        // Rule 3: nothing has loaded yet -- no empty state, no row.
        rule.onNodeWithText("No spam").assertDoesNotExist()
        rule.onNodeWithText("Spam row snippet").assertDoesNotExist()

        // Rule 4: loaded with one flagged item -> the row is shown, not the empty state.
        settleUntilLoaded({ vm.conversations.value })
        rule.onNodeWithText("No spam").assertDoesNotExist()
        rule.onNodeWithText("Spam row snippet").assertIsDisplayed()
    }
}
