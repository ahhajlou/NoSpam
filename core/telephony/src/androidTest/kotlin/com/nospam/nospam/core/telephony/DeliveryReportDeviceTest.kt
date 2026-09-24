// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.telephony

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsMessage
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * `SmsSender.recordDelivery` against the real SMS provider: a genuine 3GPP
 * SMS-STATUS-REPORT PDU is applied to a real SENT row and the row's `status`
 * column read back. Written from the spec independently of the implementation.
 *
 * Only the default SMS app may write the provider, so the test package takes
 * the SMS role for the duration of each test (through the shell, which may
 * assign roles) and hands it back to the previous holder afterwards. Skipped
 * when the role cannot be taken.
 */
@RunWith(AndroidJUnit4::class)
class DeliveryReportDeviceTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
    )

    @get:Rule
    val smsRole = SmsRoleRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val inserted = mutableListOf<Uri>()

    @After fun cleanUp() {
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    // --- Fixtures ---------------------------------------------------------------

    private val address = "+15557650042"

    private fun insertSentRow(status: Int): Uri {
        smsRole.require()
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, "delivery report device test ${System.nanoTime()}")
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.STATUS, status)
        }
        val uri = context.contentResolver.insert(Uri.parse("content://sms/sent"), values)
        assertNotNull("insert into content://sms/sent failed", uri)
        inserted += uri!!
        assertEquals("fixture status", status, statusOf(uri))
        return uri
    }

    private fun statusOf(uri: Uri): Int? =
        context.contentResolver.query(uri, arrayOf(Telephony.Sms.STATUS), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getInt(0) else null
        }

    /** Swapped-nibble BCD of [digits], padded with F. */
    private fun bcd(digits: String): ByteArray {
        val padded = if (digits.length % 2 == 0) digits else digits + "F"
        return ByteArray(padded.length / 2) { i ->
            val lo = padded[2 * i].digitToInt(16)
            val hi = padded[2 * i + 1].digitToInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }

    /** A 3GPP TS 23.040 SMS-STATUS-REPORT with TP-Status [tpStatus], preceded by an empty SMSC. */
    private fun statusReportPdu(tpStatus: Int, recipient: String = address.removePrefix("+")): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00)                 // SMSC length: none
        out.write(0x06)                 // first octet: TP-MTI = 10 (status report)
        out.write(0x2A)                 // TP-MR
        out.write(recipient.length)     // TP-RA length in digits
        out.write(0x91)                 // TOA: international, ISDN
        out.write(bcd(recipient))
        val timestamp = bcd("26092312000000") // 2026-09-23 12:00:00, TZ 0
        out.write(timestamp)            // TP-SCTS
        out.write(timestamp)            // TP-DT
        out.write(tpStatus)             // TP-ST
        return out.toByteArray()
    }

    private fun report(uri: Uri, tpStatus: Int) =
        SmsSender.recordDelivery(context, uri, statusReportPdu(tpStatus), "3gpp")

    // --- The PDU builder itself --------------------------------------------------

    @Test fun pdu_builder_produces_a_status_report_the_platform_parses() {
        for (tp in listOf(0x00, 0x20, 0x41)) {
            @Suppress("DEPRECATION")
            val parsed = SmsMessage.createFromPdu(statusReportPdu(tp), "3gpp")
            assertNotNull("tp=$tp", parsed)
            assertTrue("tp=$tp is a status report", parsed.isStatusReportMessage)
            assertEquals("tp=$tp", tp, parsed.status)
        }
    }

    // --- recordDelivery -------------------------------------------------------------

    @Test fun pending_row_and_complete_report_becomes_complete() {
        val uri = insertSentRow(Telephony.Sms.STATUS_PENDING)
        report(uri, 0x00)
        assertEquals(Telephony.Sms.STATUS_COMPLETE, statusOf(uri))
    }

    @Test fun pending_row_and_failed_report_becomes_failed() {
        val uri = insertSentRow(Telephony.Sms.STATUS_PENDING)
        report(uri, 0x41)
        assertEquals(Telephony.Sms.STATUS_FAILED, statusOf(uri))
    }

    @Test fun failed_row_stays_failed_after_a_complete_report() {
        val uri = insertSentRow(Telephony.Sms.STATUS_FAILED)
        report(uri, 0x00)
        assertEquals(Telephony.Sms.STATUS_FAILED, statusOf(uri))
    }

    @Test fun complete_row_stays_complete_after_a_pending_report() {
        val uri = insertSentRow(Telephony.Sms.STATUS_COMPLETE)
        report(uri, 0x20)
        assertEquals(Telephony.Sms.STATUS_COMPLETE, statusOf(uri))
    }

    @Test fun pending_row_and_pending_report_stays_pending() {
        val uri = insertSentRow(Telephony.Sms.STATUS_PENDING)
        report(uri, 0x20)
        assertEquals(Telephony.Sms.STATUS_PENDING, statusOf(uri))
    }

    @Test fun parts_of_a_long_message_complete_then_fail_ends_failed() {
        val uri = insertSentRow(Telephony.Sms.STATUS_PENDING)
        report(uri, 0x00)
        report(uri, 0x41)
        report(uri, 0x00)
        assertEquals(Telephony.Sms.STATUS_FAILED, statusOf(uri))
    }

    @Test fun garbage_pdu_changes_nothing() {
        val uri = insertSentRow(Telephony.Sms.STATUS_PENDING)
        SmsSender.recordDelivery(context, uri, byteArrayOf(0x01, 0x02, 0x03), "3gpp")
        SmsSender.recordDelivery(context, uri, ByteArray(0), "3gpp")
        SmsSender.recordDelivery(context, uri, byteArrayOf(0x00, 0x06), "3gpp") // truncated status report
        assertEquals(Telephony.Sms.STATUS_PENDING, statusOf(uri))
    }

    @Test fun deleted_row_does_not_crash() {
        val uri = insertSentRow(Telephony.Sms.STATUS_PENDING)
        context.contentResolver.delete(uri, null, null)
        report(uri, 0x00)
        report(uri, 0x41)
        assertEquals(null, statusOf(uri))
    }
}

/** Inert SMS_DELIVER receiver so the test APK qualifies for the SMS role (see androidTest manifest). */
class TestSmsDeliverReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) = Unit
}

/** Inert SENDTO activity so the test APK qualifies for the SMS role (see androidTest manifest). */
class TestSendToActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
