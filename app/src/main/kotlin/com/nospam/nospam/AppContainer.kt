// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.nospam.nospam.core.notifications.BundledMessageSoundPlayer
import com.nospam.nospam.core.notifications.MessageSoundPlayer
import android.content.Context
import com.nospam.nospam.core.data.BlocklistRepository
import com.nospam.nospam.core.data.ClassifyStatsRepository
import com.nospam.nospam.core.data.ConversationsRepository
import com.nospam.nospam.core.data.DraftRepository
import com.nospam.nospam.core.data.ExportRepository
import com.nospam.nospam.core.data.MessageNotificationRepository
import com.nospam.nospam.core.data.SettingsRepository
import com.nospam.nospam.core.data.SmsIngressUseCase
import com.nospam.nospam.core.data.SpamBackfillUseCase
import com.nospam.nospam.core.data.SpamRepository
import com.nospam.nospam.core.data.SenderKeyRepair
import com.nospam.nospam.core.data.SpamStateWriter
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.designsystem.component.ContactPhotoLoader
import com.nospam.nospam.core.ml.DeferredSpamClassifier
import com.nospam.nospam.core.ml.NativeCore
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

    /**
     * For work that must outlive the screen that started it, and for
     * [conversationsRepository]'s shared flows.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Stops [scope]'s work and waits for it. Tests only: the app's container
     * lives as long as the process. Without it, a test's repository kept loading
     * from SQLite after the test, from a Robolectric sandbox no longer bound to
     * its native library (UnsatisfiedLinkError in CI, 2026-10-15).
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun stop() = scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()

    val database: NoSpamDatabase by lazy { NoSpamDatabase.persistent(appContext) }

    val telephony: TelephonyDataSource by lazy { RealTelephonyDataSource(appContext) }

    /** In-app message sounds (sent, and received in the conversation on screen). */
    val messageSounds: MessageSoundPlayer by lazy { BundledMessageSoundPlayer(appContext) }

    /** Conversations filtered into Spam & blocked since the user last opened it. */
    val newSpamCount: Flow<Int> by lazy {
        conversationsRepository.observeNewSpamCount(settingsRepository.spamSeenAt)
    }

    /** The user is looking at Spam & blocked: what is there now is no longer new. */
    fun markSpamSeen() {
        scope.launch { settingsRepository.setSpamSeenAt(System.currentTimeMillis()) }
    }

    /** The conversation on screen, if any; set by the thread screen while resumed. */
    val visibleThread = MutableStateFlow<Long?>(null)

    /** Contact photos for every avatar in the app, through `LocalContactPhotoLoader`. */
    val contactPhotos: ContactPhotoLoader by lazy { ContactPhotoCache(telephony) }

    /** Opening it touches no disk; each file is read on first collection. */
    val preferences: PreferencesDataSource by lazy { DataStorePreferencesDataSource(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(preferences) }
    val draftRepository: DraftRepository by lazy { DraftRepository(preferences) }

    /**
     * Lazily loaded off the main thread: parses the 2.7 MB `spam_model.json`
     * and loads the Rust core. Callers must invoke
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

    /** Moves stored sender data to the current sender key, once per key scheme. */
    val senderKeyRepair: SenderKeyRepair by lazy {
        SenderKeyRepair(
            db = database,
            spamStateWriter = spamStateWriter,
            keyFor = { PhoneNumberNormalizer.normalize(appContext, it) },
            scheme = PhoneNumberNormalizer.KEY_SCHEME,
            storedScheme = settingsRepository::senderKeyScheme,
            saveScheme = settingsRepository::setSenderKeyScheme,
        )
    }

    val spamRepository: SpamRepository by lazy { SpamRepository(database, deferredClassifier, appContext, spamStateWriter) }
    val blocklistRepository: BlocklistRepository by lazy { BlocklistRepository(database, appContext, telephony = telephony) }
    val conversationsRepository: ConversationsRepository by lazy {
        // Normalize in the same way SmsIngressUseCase/BlocklistRepository key
        // their rows, so inbox/spam-section lookups agree (§15 single key).
        ConversationsRepository(
            telephony,
            database,
            // The container's scope rather than one of its own (the same IO
            // scope), so stop() can end the repository's flows too.
            externalScope = scope,
            normalizer = { PhoneNumberNormalizer.normalize(appContext, it) },
            drafts = draftRepository.observeDrafts(),
        )
    }

    /**
     * Read-only corpus access for the debug export tool, so `feature:export`
     * goes through the repository layer like every other feature (CLAUDE.md §4).
     */
    val exportRepository: ExportRepository by lazy {
        ExportRepository(telephony, database, mask = { NativeCore.pipelineStages(it).anonymized })
    }

    /** Temporary counters of incoming-SMS spam checks (TODO.md, "Late spam checks"). */
    val classifyStats: ClassifyStatsRepository by lazy { ClassifyStatsRepository(preferences) }
    val messageNotifications: MessageNotificationRepository by lazy {
        MessageNotificationRepository(telephony, spamRepository, preferences)
    }

    val smsIngress: SmsIngressUseCase by lazy {
        SmsIngressUseCase(
            telephony, deferredClassifier, database, appContext,
            isSpamProtectionEnabled = { settingsRepository.isSpamProtectionEnabled() },
            spamStateWriter = spamStateWriter,
            classifyStats = classifyStats,
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
     * Gives a verdict to recent messages ingress left without one
     * ([SmsIngressUseCase.classifyMissed]), in the background. Skipped while the
     * history scan runs: it covers the same messages, and both would count them.
     */
    fun classifyMissedMessages() {
        scope.launch {
            if (spamBackfill.status.value is com.nospam.nospam.core.data.BackfillStatus.Running) return@launch
            runCatching { smsIngress.classifyMissed() }
                .onFailure { android.util.Log.w("AppContainer", "Classifying missed messages failed", it) }
        }
    }

    /**
     * Onboarding is done: every required permission and the SMS role are held.
     * Runs here rather than in the onboarding screen's coroutine scope, which is
     * canceled as the screen leaves, and could stop the scan from starting.
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
