// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController

/**
 * A developer tool surfaced in the navigation drawer, below the folders.
 *
 * Release builds carry them too since 2026-10-14: the phone in daily use runs a
 * release build, which `run-as` cannot read, so exporting messages for the
 * training data and checking the classifier have to work there.
 */
class DebugTool(
    val labelRes: Int,
    val icon: ImageVector,
    /** Substring used to mark the drawer item selected for the current route. */
    val routeTag: String,
    val navigate: (NavHostController) -> Unit,
)
