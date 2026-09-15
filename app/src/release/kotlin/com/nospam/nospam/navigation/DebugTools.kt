package com.nospam.nospam.navigation

import android.content.Context
import androidx.navigation.NavGraphBuilder
import com.nospam.nospam.AppContainer

/**
 * Release builds ship no developer tools. `:feature:export` and
 * `:feature:mldebug` are `debugImplementation`, so their classes are not on
 * this classpath at all.
 */
val debugTools: List<DebugTool> = emptyList()

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.debugToolDestinations(container: AppContainer?, context: Context) = Unit
