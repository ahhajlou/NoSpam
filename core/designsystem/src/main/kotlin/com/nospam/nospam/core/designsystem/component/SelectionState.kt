package com.nospam.nospam.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Which rows are selected in a list, keyed by id. Selection mode is simply
 * "at least one selected": long-press enters it; the close button, system
 * back or deselecting the last row leaves it.
 *
 * UI element state, not screen state: nothing reaches a repository until an
 * action runs, so it lives in the composition and survives rotation through
 * [rememberSelectionState] rather than in a ViewModel.
 */
@Stable
class SelectionState internal constructor(initial: Set<Long>) {
    var ids: Set<Long> by mutableStateOf(initial)
        private set

    val isActive: Boolean get() = ids.isNotEmpty()

    fun toggle(id: Long) {
        ids = toggledSelection(ids, id)
    }

    fun clear() {
        ids = emptySet()
    }

    /** Drops ids that left the list (archived, deleted, filtered away). */
    fun retainVisible(visible: Set<Long>) {
        val kept = ids intersect visible
        if (kept.size != ids.size) ids = kept
    }
}

internal fun toggledSelection(ids: Set<Long>, id: Long): Set<Long> = if (id in ids) ids - id else ids + id

@Composable
fun rememberSelectionState(): SelectionState =
    rememberSaveable(saver = SelectionSaver) { SelectionState(emptySet()) }

private val SelectionSaver = Saver<SelectionState, LongArray>(
    save = { it.ids.toLongArray() },
    restore = { SelectionState(it.toSet()) },
)

/** Keeps [selection] a subset of [visibleIds] as the list changes underneath it. */
@Composable
fun PruneSelection(selection: SelectionState, visibleIds: List<Long>) {
    val visible = visibleIds.toSet()
    LaunchedEffect(visible) { selection.retainVisible(visible) }
}
