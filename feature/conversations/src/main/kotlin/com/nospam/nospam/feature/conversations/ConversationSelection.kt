package com.nospam.nospam.feature.conversations

import com.nospam.nospam.core.model.Conversation

/**
 * What a set of selected conversations has in common, which decides each
 * action's label. A mixed selection gets the action that changes something:
 * pinning a selection that is partly pinned pins the rest, it does not unpin.
 */
internal data class SelectionSummary(
    val count: Int,
    val allPinned: Boolean,
    val allStarred: Boolean,
    val allMuted: Boolean,
    val anyUnread: Boolean,
    val allBlocked: Boolean,
    /** The one selected conversation's address, for single-item actions (call, add contact). */
    val singleAddress: String?,
)

internal fun summarize(selected: List<Conversation>): SelectionSummary = SelectionSummary(
    count = selected.size,
    allPinned = selected.isNotEmpty() && selected.all { it.isPinned },
    allStarred = selected.isNotEmpty() && selected.all { it.isStarred },
    allMuted = selected.isNotEmpty() && selected.all { it.isMuted },
    anyUnread = selected.any { !it.read },
    allBlocked = selected.isNotEmpty() && selected.all { it.isBlocked },
    singleAddress = selected.singleOrNull()?.participants?.firstOrNull()?.address,
)

/** Addresses of the selected conversations, skipping any without one. */
internal fun addressesOf(selected: List<Conversation>): List<String> =
    selected.mapNotNull { it.participants.firstOrNull()?.address }.distinct()
