// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.nospam.nospam.core.notifications.SystemMessageSoundPlayer
import com.nospam.nospam.core.notifications.MessageSoundPlayer
import android.content.Context
import com.nospam.nospam.core.data.BlocklistRepository
import com.nospam.nospam.core.data.ConversationsRepository
import com.nospam.nospam.core.data.DraftRepository
import com.nospam.nospam.core.data.ExportRepository
import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.data.SmsIngressUseCase
import com.nospam.nospam.core.data.SpamBackfillUseCase
import com.nospam.nospam.core.data.SpamRepository
import com.nospam.nospam.core.data.SpamStateWriter
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.designsystem.component.ContactPhotoLoader
import com.nospam.nospam.core.ml.DeferredSpamClassifier
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.ml.TfidfSpamClassifier
import com.nospam.nospam.core.preferences.DataStorePreferencesDataSource
import com.nospam.nospam.core.preferences.PreferencesDataSource
import com.nospam.nospam.core.telephony.PhoneNumberNormalizer
import com.nospam.nospam.core.telephony.RealTelephonyDataSource
import com.nospam.nospam.core.telephony.TelephonyDataSource
import com.nospam.nospam.ui.ContactPhotoCache

/**
 * Manual service locator. No Hilt/Koin: the graph is four singletons and a
 * BroadcastReceiver cannot use constructor injection anyway. Repositories and
 * the ingress use-case take their collaborators as constructor params, so
 * tests can still substitute fakes without this container.
 */
class AppContainer(private val context: Context) {
    private val appContext: Context = context.applicationContext

    /** For work that must outlive the screen that started it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: NoSpamDatabase by lazy { NoSpamDatabase.persistent(appContext) }

    val telephony: TelephonyDataSource by lazy { RealTelephonyDataSource(appContext) }

    /** In-app message sounds (sent, and received in the conversation on screen). */
    val messageSounds: MessageSoundPlayer by lazy { SystemMessageSoundPlayer(appContext) }

    /** The conversation on screen, if any; set by the thread screen while resumed. */
    val visibleThread = MutableStateFlow<Long?>(null)

    /** Contact photos for every avatar in the app, through `LocalContactPhotoLoader`. */
    val contactPhotos: ContactPhotoLoader by lazy { ContactPhotoCache(telephony) }

    /** Opening it touches no disk; each file is read on first collection. */
    val preferences: PreferencesDataSource by lazy { DataStorePreferencesDataSource(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(preferences) }
    val draftRepository: DraftRepository by lazy { DraftRepository(preferences) }

    /**
     * Lazily loaded off the main thread (1.2 MB JSON). Callers must invoke
     * from a background coroutine — [sms.AppSmsReceiver] uses goAsync + IO.
     */
    val classifier: SpamClassifier by lazy {
        TfidfSpamClassifier.fromAsset(appContext)
    }

    /**
     * What the use cases hold instead of [classifier]: building them no longer
     * loads the model. The inbox's ViewModel builds [spamBackfill] on the main
     * thread during the first composition, and that waited ~430ms for the model
     * on every cold start; a thread's ViewModel does the same with
     * [spamRepository].
     */
    private val deferredClassifier: SpamClassifier by lazy {
        DeferredSpamClassifier(Dispatchers.Default) { classifier }
    }

    /**
     * Single-writer gate shared by ingress, backfill and user overrides so the
     * check-then-write on `sender_state` cannot interleave (CLAUDE.md §15).
     */
    val spamStateWriter: SpamStateWriter by lazy { SpamStateWriter(database.senderStateDao) }

    val spamRepository: SpamRepository by lazy { SpamRepository(database, deferredClassifier, appContext, spamStateWriter) }
    val blocklistRepository: BlocklistRepository by lazy { BlocklistRepository(database, appContext, telephony = telephony) }
    val conversationsRepository: ConversationsRepository by lazy {
        // Normalize in the same way SmsIngressUseCase/BlocklistRepository key
        // their rows, so inbox/spam-section lookups agree (§15 single key).
        ConversationsRepository(
            telephony,
            database,
            normalizer = { PhoneNumberNormalizer.normalize(appContext, it) }
        )
    }

    /**
     * Read-only corpus access for the debug export tool, so `feature:export`
     * goes through the repository layer like every other feature (CLAUDE.md §4).
     */
    val exportRepository: ExportRepository by lazy { ExportRepository(telephony, database) }

    val smsIngress: SmsIngressUseCase by lazy {
        SmsIngressUseCase(
            telephony, deferredClassifier, database, appContext,
            isSpamProtectionEnabled = { settingsRepository.isSpamProtectionEnabled() },
            spamStateWriter = spamStateWriter,
        )
    }

    /**
     * One-shot background scan over existing history. Idempotent and permission-
     * safe (returns fast when nothing is classifiable). Never auto-runs on app
     * launch: it starts after SMS permission is granted in onboarding (a
     * `history_backfill_pending` DataStore flag makes an interrupted scan resume
     * on the next cold start, exactly once), and the UI can trigger rescanning
     * from Settings.
     */
    val spamBackfill: SpamBackfillUseCase by lazy {
        SpamBackfillUseCase(
            telephony = telephony,
            classifier = deferredClassifier,
            db = database,
            context = appContext,
            spamStateWriter = spamStateWriter,
            isSpamProtectionEnabled = { settingsRepository.isSpamProtectionEnabled() },
        )
    }

    /**
     * Onboarding is done: every required permission and the SMS role are held.
     * Runs here rather than in the onboarding screen's coroutine scope, which is
     * cancelled as the screen leaves, and could stop the scan from starting.
     */
    fun onSetupComplete() {
        // The inbox list was first read before the permissions existed.
        conversationsRepository.refresh()
        scope.launch {
            // Pending first, so a process that dies mid-scan resumes it.
            settingsRepository.setBackfillPending(true)
            spamBackfill.ensureStarted()
        }
    }
}
