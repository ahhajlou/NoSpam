package com.example.nospam.core.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DispatcherProviderTest {
    @Test fun `TestDispatcherProvider defaults everything to main`() {
        val testDispatcher = UnconfinedTestDispatcher()
        val provider = TestDispatcherProvider(main = testDispatcher)
        assertSame(testDispatcher, provider.main)
        assertSame(testDispatcher, provider.mainImmediate)
        assertSame(testDispatcher, provider.io)
        assertSame(testDispatcher, provider.default)
        assertSame(testDispatcher, provider.unconfined)
    }

    @Test fun `SmsPermissions constants are stable`() {
        assertEquals("android.permission.READ_SMS", SmsPermissions.READ_SMS)
        assertEquals("android.permission.SEND_SMS", SmsPermissions.SEND_SMS)
        assertEquals("android.permission.RECEIVE_SMS", SmsPermissions.RECEIVE_SMS)
        assertEquals("android.permission.RECEIVE_MMS", SmsPermissions.RECEIVE_MMS)
        assertEquals("android.permission.READ_CONTACTS", SmsPermissions.READ_CONTACTS)
        assertEquals("android.permission.RECEIVE_WAP_PUSH", SmsPermissions.RECEIVE_WAP_PUSH)
    }
}
