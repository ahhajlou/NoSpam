package com.nospam.nospam.core.common

import org.junit.Assert.*
import org.junit.Test

class ResultTest {
    @Test fun `Success holds data`() {
        val r: Result<Int> = Result.Success(42)
        assertTrue(r.isSuccess())
        assertEquals(42, r.getOrNull())
    }
    @Test fun `Failure holds error`() {
        val e = RuntimeException("oops")
        val r: Result<Int> = Result.Failure(e)
        assertTrue(r.isFailure())
        assertEquals(e, r.errorOrNull())
    }
    @Test fun `map transforms Success`() {
        val r: Result<Int> = Result.Success(2)
        assertEquals(Result.Success(4), r.map { it * 2 })
    }
    @Test fun `map preserves Failure`() {
        val e = RuntimeException("fail")
        val r: Result<Int> = Result.Failure(e)
        assertEquals(e, (r.map { it * 2 } as Result.Failure).error)
    }
    @Test fun `fold handles all branches`() {
        assertEquals("ok", Result.Success(1).fold({ "ok" }, { "fail" }))
        assertEquals("fail", Result.Failure(RuntimeException()).fold({ "ok" }, { "fail" }))
    }
    @Test fun `PermissionChecker fake`() {
        val checker = object : PermissionChecker {
            override fun hasPermission(permission: String) = permission == SmsPermissions.READ_SMS
        }
        assertTrue(checker.hasPermission(SmsPermissions.READ_SMS))
        assertFalse(checker.hasPermission(SmsPermissions.SEND_SMS))
    }
}
