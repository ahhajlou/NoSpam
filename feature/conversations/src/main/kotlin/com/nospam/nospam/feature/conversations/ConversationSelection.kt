package com.nospam.nospam.feature.conversations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.nospam.nospam.core.model.Conversation

/**
 * Which conversations are selected in a list. Selection mode is simply
 * "at least one selected": long-press enters it, deselecting the last row,
 * the close button or back leaves it.
 *
 * UI element state, not screen state: it never reaches a repository until an
 * action runs, so it lives in the composition and survives rotation through
 * [rememberConversationSelection] rather than in a ViewModel.
 */
@Stable
class ConversationSelection internal constructor(initial: Set<Long>) {
    var ids: Set<Long> by mutableStateOf(initial)
        private set

    val isActive: Boolean get() = ids.isNotEmpty()

    fun toggle(threadId: Long) {
        ids = toggled(ids, threadId)
    }

    fun clear() {
        ids = emptySet()
    }

    /** Drops rows that left the list (archived, deleted, filtered away). */
    fun retainVisible(visible: Set<Long>) {
        val kept = ids intersect visible
        if (kept.size != ids.size) ids = kept
    }
}

internal fun toggled(ids: Set<Long>, id: Long): Set<Long> = if (id in ids) ids - id else ids + id

@Composable
fun rememberConversationSelection(): ConversationSelection =
    rememberSaveable(saver = SelectionSaver) { ConversationSelection(emptySet()) }

private val SelectionSaver = Saver<ConversationSelection, LongArray>(
    save = { it.ids.toLongArray() },
    restore = { ConversationSelection(it.toSet()) },
)

/** Keeps [selection] a subset of [conversations] as the list changes underneath it. */
@Composable
internal fun PruneSelection(selection: ConversationSelection, conversations: List<Conversation>) {
    val visible = conversations.map { it.threadId.value }.toSet()
    LaunchedEffect(visible) { selection.retainVisible(visible) }
}

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
