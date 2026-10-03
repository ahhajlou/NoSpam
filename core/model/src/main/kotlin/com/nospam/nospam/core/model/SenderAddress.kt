// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.model

/**
 * Whether [address] is an alphanumeric sender ID ("MCI", "Snapp", "Irancell")
 * rather than a number: it holds a letter, in any script. Such a sender has no
 * number to reach, so a reply to it goes nowhere. Numeric short codes are not
 * alphanumeric and stay replyable: many services take replies such as "11".
 */
fun isAlphanumericSender(address: String): Boolean = address.any { it.isLetter() }
