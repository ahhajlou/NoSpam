// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.conversations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * The scope for a test's [com.nospam.nospam.core.data.ConversationsRepository], stopped
 * after the test.
 *
 * Without one the repository makes its own IO scope and starts its shared flows at once,
 * and nothing ever cancels them. A flow still loading a DAO then opened SQLite after the
 * test had ended, from a Robolectric sandbox no longer bound to its native library: CI
 * logged `UnsatisfiedLinkError` from `SQLiteConnectionNatives.nativeOpen` on 2026-10-15.
 * This scope is the production one (IO), cancelled and waited for in [after].
 */
class RepositoryScopeRule : ExternalResource() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun after() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }
}
