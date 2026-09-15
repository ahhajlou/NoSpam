package com.nospam.nospam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AppContainer had no unit test source set at all before Wave 2A -- named gap
 * in the task brief ("At minimum cover AppContainer wiring"). This confirms
 * the manual DI graph actually resolves (every lazy property constructs
 * without throwing) and that each property is a true singleton -- the whole
 * point of a service locator -- rather than asserting on internal wiring
 * details not exposed by the public API.
 *
 * `classifier` (and anything that touches it -- `spamRepository`,
 * `smsIngress`, `spamBackfill`) is deliberately not exercised here:
 * `TfidfSpamClassifier.fromAsset` reads `spam_model.json` from `core:ml`'s
 * assets via `context.assets`, and Robolectric does not resolve a
 * library-module's merged assets from `:app`'s unit test task in this setup
 * -- the same limitation `feature:mldebug`'s MlDebugViewModelTest already
 * works around by loading the asset from an explicit file path instead of a
 * Context. That real asset-loading path is core:ml's own test responsibility
 * (TfidfTest), not this wiring test's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppContainerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test fun `every asset-independent collaborator resolves without throwing`() {
        val container = AppContainer(context)

        container.database
        container.telephony
        container.spamStateWriter
        container.blocklistRepository
        container.conversationsRepository
        container.exportRepository
    }

    @Test fun `database is a singleton across accesses`() {
        val container = AppContainer(context)
        assertSame(container.database, container.database)
    }

    @Test fun `telephony is a singleton across accesses`() {
        val container = AppContainer(context)
        assertSame(container.telephony, container.telephony)
    }

    @Test fun `spamStateWriter is a singleton across accesses`() {
        val container = AppContainer(context)
        assertSame(container.spamStateWriter, container.spamStateWriter)
    }

    @Test fun `blocklistRepository, conversationsRepository and exportRepository are each singletons`() {
        val container = AppContainer(context)
        assertSame(container.blocklistRepository, container.blocklistRepository)
        assertSame(container.conversationsRepository, container.conversationsRepository)
        assertSame(container.exportRepository, container.exportRepository)
    }

    @Test fun `two different containers do not share a database instance`() {
        // AppContainer takes a Context per instance (e.g. tests could
        // construct their own) -- confirms the singleton is per-container,
        // not accidentally process-global like SpamPreferences' DataStore.
        val first = AppContainer(context)
        val second = AppContainer(context)
        assertNotSame(first.database, second.database)
    }
}
