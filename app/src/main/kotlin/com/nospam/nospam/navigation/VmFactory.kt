// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Builds a one-off [ViewModelProvider.Factory] around a lambda. Shared with the
 * variant-specific debug destinations in `src/debug`, which is why it is
 * `internal` rather than private to the NavHost.
 */
internal inline fun <reified VM : ViewModel> vmFactory(crossinline create: () -> VM) =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
