// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.nospam.nospam.core.model.DeliveryStatus
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import com.nospam.nospam.core.testing.FakeTelephonyDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Delivery labels in a thread: `newestDeliveredId` (which outgoing message
 * carries "Delivered") and the labels ThreadScreen draws -- one "Delivered"
 * on the newest delivered outgoing message, "Not delivered" on every sent
 * message whose report failed, nothing for pending or none. Written from the
 * spec independently of the implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class DeliveryLabelsTest {
    @get:Rule val rule = createComposeRule()

    private fun msg(
        id: Long,
        date: Long = id,
        type: MessageType = MessageType.SENT,
        status: DeliveryStatus = DeliveryStatus.NONE,
        body: String = "message $id",
    ) = Message(MessageId(id), ThreadId(9), "+15550009", body, date, type, true, deliveryStatus = status)

    // --- newestDeliveredId --------------------------------------------------

    @Test fun `empty list has none`() {
        assertNull(newestDeliveredId(emptyList()))
    }

    @Test fun `no delivered message has none`() {
        assertNull(
            newestDeliveredId(
                listOf(
                    msg(1, status = DeliveryStatus.NONE),
                    msg(2, status = DeliveryStatus.PENDING),
                    msg(3, status = DeliveryStatus.FAILED),
                ),
            ),
        )
    }

    @Test fun `incoming messages are ignored even when marked delivered`() {
        assertNull(newestDeliveredId(listOf(msg(1, type = MessageType.INBOX, status = DeliveryStatus.DELIVERED))))
        assertEquals(
            2L,
            newestDeliveredId(
                listOf(
                    msg(2, status = DeliveryStatus.DELIVERED),
                    msg(3, type = MessageType.INBOX, status = DeliveryStatus.DELIVERED),
                ),
            ),
        )
    }

    @Test fun `picks the newest by date, not by id or list order`() {
        val list = listOf(
            msg(5, date = 100, status = DeliveryStatus.DELIVERED),
            msg(3, date = 300, status = DeliveryStatus.DELIVERED),
            msg(9, date = 200, status = DeliveryStatus.DELIVERED),
        )
        assertEquals(3L, newestDeliveredId(list))
        assertEquals(3L, newestDeliveredId(list.reversed()))
    }

    @Test fun `ties on date are broken by the higher id`() {
        val list = listOf(
            msg(4, date = 100, status = DeliveryStatus.DELIVERED),
            msg(6, date = 100, status = DeliveryStatus.DELIVERED),
        )
        assertEquals(6L, newestDeliveredId(list))
        assertEquals(6L, newestDeliveredId(list.reversed()))
    }

    @Test fun `newer non-delivered outgoing messages do not move the label`() {
        val list = listOf(
            msg(1, status = DeliveryStatus.DELIVERED),
            msg(2, status = DeliveryStatus.PENDING),
            msg(3, status = DeliveryStatus.FAILED),
            msg(4, status = DeliveryStatus.NONE),
        )
        assertEquals(1L, newestDeliveredId(list))
    }

    // --- Screen ----------------------------------------------------------------

    private fun show(vararg messages: Message) {
        val fake = FakeTelephonyDataSource()
        fake.emitMessages(ThreadId(9), messages.toList())
        val vm = ThreadViewModel(fake)
        rule.setContent { ThreadScreen(threadId = 9L, viewModel = vm) }
        rule.waitForIdle()
    }

    private fun top(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
    private fun bottom(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom

    @Test fun `exactly one Delivered label, under the newest delivered outgoing message`() {
        show(
            msg(1, body = "older delivered", status = DeliveryStatus.DELIVERED),
            msg(2, body = "newest delivered", status = DeliveryStatus.DELIVERED),
            msg(3, body = "still pending", status = DeliveryStatus.PENDING),
        )
        rule.onAllNodesWithText("Delivered", useUnmergedTree = true).assertCountEquals(1)
        val label = top("Delivered")
        assertTrue("label is below the newest delivered message", label >= bottom("newest delivered"))
        assertTrue("label is above the next message", label < top("still pending"))
        assertTrue("label is not attached to the older delivered message", label > bottom("older delivered"))
    }

    @Test fun `an incoming message marked delivered gets no label`() {
        show(msg(1, type = MessageType.INBOX, body = "from them", status = DeliveryStatus.DELIVERED))
        rule.onAllNodesWithText("Delivered", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun `Not delivered on every failed report, nothing for pending or none`() {
        show(
            msg(1, body = "failed one", status = DeliveryStatus.FAILED),
            msg(2, body = "pending one", status = DeliveryStatus.PENDING),
            msg(3, body = "failed two", status = DeliveryStatus.FAILED),
            msg(4, body = "no report", status = DeliveryStatus.NONE),
        )
        rule.onAllNodesWithText("Not delivered", useUnmergedTree = true).assertCountEquals(2)
        rule.onAllNodesWithText("Delivered", useUnmergedTree = true).assertCountEquals(0)
        val first = top("failed one")
        val second = top("failed two")
        val labels = rule.onAllNodesWithText("Not delivered", useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot.top }.sorted()
        assertTrue("first label under 'failed one', above 'pending one'", labels[0] > first && labels[0] < top("pending one"))
        assertTrue("second label under 'failed two', above 'no report'", labels[1] > second && labels[1] < top("no report"))
    }

    @Test fun `pending and none alone show no delivery label`() {
        show(
            msg(1, body = "pending one", status = DeliveryStatus.PENDING),
            msg(2, body = "no report", status = DeliveryStatus.NONE),
        )
        rule.onAllNodesWithText("Delivered", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText("Not delivered", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun `Delivered and Not delivered can appear together`() {
        show(
            msg(1, body = "got there", status = DeliveryStatus.DELIVERED),
            msg(2, body = "did not get there", status = DeliveryStatus.FAILED),
        )
        rule.onAllNodesWithText("Delivered", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithText("Not delivered", useUnmergedTree = true).assertCountEquals(1)
    }
}
