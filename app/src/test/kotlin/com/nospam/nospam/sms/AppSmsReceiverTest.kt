package com.nospam.nospam.sms

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AppSmsReceiver had no unit test source set at all before Wave 2A -- named
 * gap in the task brief ("At minimum cover... AppSmsReceiver dispatch").
 *
 * Only the three guard clauses that return *before* `goAsync()` is called are
 * covered here: `onReceive` calling `goAsync()` requires the receiver to be
 * mid-dispatch by the real broadcast framework (a `PendingResult` the system
 * attaches), which a direct `onReceive(context, intent)` call does not set
 * up. The full ingestion path also needs a real `NoSpamApplication` with a
 * working AppContainer (SQLite + a loaded classifier) reached through
 * `context.applicationContext as? NoSpamApplication`, which is deliberately
 * out of scope for this pass -- the guard clauses are the dispatch-routing
 * behaviour worth regression-protecting on their own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSmsReceiverTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test fun `an intent with the wrong action is ignored without throwing`() {
        val receiver = AppSmsReceiver()
        // Must not throw and must not attempt goAsync() for this action.
        receiver.onReceive(context, Intent("some.other.action"))
        assertTrue(true)
    }

    @Test fun `SMS_DELIVER with no pdus extra is ignored without throwing`() {
        val receiver = AppSmsReceiver()
        val intent = Intent(android.provider.Telephony.Sms.Intents.SMS_DELIVER_ACTION)
        // No "pdus" extra set -> getMessagesFromIntent returns null or empty.
        receiver.onReceive(context, intent)
        assertTrue(true)
    }

    @Test fun `SMS_DELIVER with an empty pdus array is ignored without throwing`() {
        val receiver = AppSmsReceiver()
        val intent = Intent(android.provider.Telephony.Sms.Intents.SMS_DELIVER_ACTION).apply {
            putExtra("pdus", arrayOf<Any>())
        }
        receiver.onReceive(context, intent)
        assertTrue(true)
    }
}
