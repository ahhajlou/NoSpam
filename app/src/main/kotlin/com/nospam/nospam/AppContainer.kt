package com.nospam.nospam

import android.content.Context
import com.nospam.nospam.core.data.BlocklistRepository
import com.nospam.nospam.core.data.ConversationsRepository
import com.nospam.nospam.core.data.SmsIngressUseCase
import com.nospam.nospam.core.data.SpamRepository
import com.nospam.nospam.core.database.NoSpamDatabase
import com.nospam.nospam.core.ml.SpamClassifier
import com.nospam.nospam.core.ml.TfidfSpamClassifier
import com.nospam.nospam.core.telephony.RealTelephonyDataSource
import com.nospam.nospam.core.telephony.TelephonyDataSource

/**
 * Manual service locator. No Hilt/Koin: the graph is four singletons and a
 * BroadcastReceiver cannot use constructor injection anyway. Repositories and
 * the ingress use-case take their collaborators as constructor params, so
 * tests can still substitute fakes without this container.
 */
class AppContainer(private val context: Context) {
    private val appContext: Context = context.applicationContext

    val database: NoSpamDatabase by lazy { NoSpamDatabase.persistent(appContext) }

    val telephony: TelephonyDataSource by lazy { RealTelephonyDataSource(appContext) }

    /**
     * Lazily loaded off the main thread (1.2 MB JSON). Callers must invoke
     * from a background coroutine — [sms.AppSmsReceiver] uses goAsync + IO.
     */
    val classifier: SpamClassifier by lazy {
        TfidfSpamClassifier.fromAsset(appContext)
    }

    val spamRepository: SpamRepository by lazy { SpamRepository(database, classifier) }
    val blocklistRepository: BlocklistRepository by lazy { BlocklistRepository(database, appContext) }
    val conversationsRepository: ConversationsRepository by lazy {
        ConversationsRepository(telephony, database)
    }

    val smsIngress: SmsIngressUseCase by lazy {
        SmsIngressUseCase(telephony, classifier, database, appContext)
    }
}
