// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.common

/**
 * Pure-Kotlin permission check — no Android import.
 * Real impl (in feature:onboarding or core:telephony later) uses Context.checkSelfPermission.
 * Fake in core:testing returns canned values.
 */
interface PermissionChecker {
    fun hasPermission(permission: String): Boolean
    fun hasPermissions(permissions: List<String>): Boolean = permissions.all { hasPermission(it) }
}

object SmsPermissions {
    const val READ_SMS = "android.permission.READ_SMS"
    const val SEND_SMS = "android.permission.SEND_SMS"
    const val RECEIVE_SMS = "android.permission.RECEIVE_SMS"
    const val RECEIVE_MMS = "android.permission.RECEIVE_MMS"
    const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    const val RECEIVE_WAP_PUSH = "android.permission.RECEIVE_WAP_PUSH"
}
