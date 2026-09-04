package com.example.nospam

import android.content.Context
import com.example.nospam.core.data.BlocklistRepository
import com.example.nospam.core.data.ConversationsRepository
import com.example.nospam.core.data.SmsIngressUseCase
import com.example.nospam.core.data.SpamRepository
import com.example.nospam.core.database.NoSpamDatabase
import com.example.nospam.core.ml.SpamClassifier
import com.example.nospam.core.ml.TfidfSpamClassifier
import com.example.nospam.core.telephony.RealTelephonyDataSource
import com.example.nospam.core.telephony.TelephonyDataSource

/**
 * Manual service locator. No Hilt/Koin: the graph is four singletons and a
 * BroadcastReceiver cannot use constructor injection anyway. Repositories and
 * the ingress use-case take their collaborators as constructor params, so
 * tests can still substitute fakes without this container.
 */
class AppContainer(private val context: Context) {
    private val appContext: Context = context.applicationContext

    val database: NoSpamDatabase by lazy { NoSpamDatabase.inMemory() }

    val telephony: TelephonyDataSource by lazy { RealTelephonyDataSource(appContext) }

    /**
     * Lazily loaded off the main thread (1.2 MB JSON). Callers must invoke
     * from a background coroutine — [sms.AppSmsReceiver] uses goAsync + IO.
     */
    val classifier: SpamClassifier by lazy {
        TfidfSpamClassifier.fromAsset(appContext)
    }

    val spamRepository: SpamRepository by lazy { SpamRepository(database, classifier) }
    val blocklistRepository: BlocklistRepository by lazy { BlocklistRepository(database) }
    val conversationsRepository: ConversationsRepository by lazy {
        ConversationsRepository(telephony, database)
    }

    val smsIngress: SmsIngressUseCase by lazy {
        SmsIngressUseCase(telephony, classifier, database)
    }
}
