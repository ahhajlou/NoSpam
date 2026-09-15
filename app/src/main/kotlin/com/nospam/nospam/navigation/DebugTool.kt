package com.nospam.nospam.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController

/**
 * A developer-only destination surfaced in the navigation drawer.
 *
 * The list of these is variant-specific: `src/debug` supplies the real tools,
 * `src/release` supplies an empty list, and the corresponding feature modules
 * are wired with `debugImplementation` so a release build cannot link them at
 * all. Gating on `BuildConfig.DEBUG` alone would not be enough — the classes
 * have to be absent from the release classpath, not merely unreachable.
 */
class DebugTool(
    val labelRes: Int,
    val icon: ImageVector,
    /** Substring used to mark the drawer item selected for the current route. */
    val routeTag: String,
    val navigate: (NavHostController) -> Unit,
)
