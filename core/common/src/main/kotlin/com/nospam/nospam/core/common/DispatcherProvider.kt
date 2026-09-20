// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

interface DispatcherProvider {
    val main: CoroutineDispatcher
    val mainImmediate: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val unconfined: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Main.immediate
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
}

class TestDispatcherProvider(
    override val main: CoroutineDispatcher,
    override val mainImmediate: CoroutineDispatcher = main,
    override val io: CoroutineDispatcher = main,
    override val default: CoroutineDispatcher = main,
    override val unconfined: CoroutineDispatcher = main,
) : DispatcherProvider
