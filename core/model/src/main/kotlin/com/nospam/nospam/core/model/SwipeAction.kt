// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.model

/** What swiping an inbox row does. */
enum class SwipeAction { NONE, ARCHIVE, DELETE, TOGGLE_READ }

/**
 * The inbox's swipe actions, by physical direction: a right swipe is a right
 * swipe in a right-to-left layout too, as Google Messages presents it.
 * Both default to archive, as there.
 */
data class SwipeActions(
    val right: SwipeAction = SwipeAction.ARCHIVE,
    val left: SwipeAction = SwipeAction.ARCHIVE,
)
