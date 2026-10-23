// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.model

/**
 * Temporary counters of the spam check of incoming SMS (TODO.md, "Late spam
 * checks"). [since] is when the first count after the last reset happened
 * (epoch millis), null while nothing has been counted.
 */
data class ClassifyStats(
    val since: Long? = null,
    /** Checks started: not a saved contact, not a blocked sender. */
    val checked: Long = 0,
    /** Checks that had no verdict when the wait ended. */
    val late: Long = 0,
    /** Checks where the classifier threw, in time or late. */
    val failed: Long = 0,
    /** Late checks announced normally whose verdict then said they should not have been. */
    val leaked: Long = 0,
)
