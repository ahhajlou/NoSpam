package com.example.nospam.core.model

/**
 * Cross-module constants for telephony — prevents core:telephony → core:notifications dep.
 * Both modules depend only on core:model, per CLAUDE.md §4.
 */
object TelephonyConstants {
    // Intent actions (plain strings, no Android dependency)
    const val ACTION_SMS_DELIVER = "android.provider.Telephony.SMS_DELIVER"
    const val ACTION_WAP_PUSH_DELIVER = "android.provider.Telephony.WAP_PUSH_DELIVER"
    const val ACTION_RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE"

    // Permissions required in manifest fragments
    const val PERMISSION_BROADCAST_SMS = "android.permission.BROADCAST_SMS"
    const val PERMISSION_BROADCAST_WAP_PUSH = "android.permission.BROADCAST_WAP_PUSH"
    const val PERMISSION_SEND_RESPOND_VIA_MESSAGE = "android.permission.SEND_RESPOND_VIA_MESSAGE"

    // Direct-reply extras (used by core:notifications PendingIntent → HeadlessSmsSendService)
    const val EXTRA_MESSAGE = "android.intent.extra.TEXT"
    const val EXTRA_URI = "android.intent.extra.\"SMS_URI\"" // not used directly, placeholder

    // RemoteInput key for notification inline reply. Single source of truth so
    // core:telephony never imports core:notifications (core→core is forbidden).
    const val KEY_TEXT_REPLY = "key_text_reply"
    const val EXTRA_THREAD_ID = "thread_id"
    const val EXTRA_SUBSCRIPTION_ID = "subscription_id"

    // MMS mime
    const val MMS_MIME_TYPE = "application/vnd.wap.mms-message"

    // Send schemes
    val SEND_SCHEMES = listOf("sms", "smsto", "mms", "mmsto")
}
