package com.example.nospam.core.testing

import com.example.nospam.core.common.PermissionChecker
import com.example.nospam.core.model.*

class FakePermissionChecker(
    private val granted: Set<String> = emptySet()
) : PermissionChecker {
    override fun hasPermission(permission: String): Boolean = permission in granted
    fun grant(permission: String) = FakePermissionChecker(granted + permission)
    fun revoke(permission: String) = FakePermissionChecker(granted - permission)
}

class FakeSpamClassifier(
    private val verdictFor: (RawMessage) -> SpamVerdict = { SpamVerdict(SpamLabel.HAM, -1.0) }
) {
    var lastMessage: RawMessage? = null
        private set
    var callCount: Int = 0
        private set

    fun classify(message: RawMessage): SpamVerdict {
        lastMessage = message
        callCount++
        return verdictFor(message)
    }

    fun returnsSpam(score: Double = 1.0) = FakeSpamClassifier { SpamVerdict(SpamLabel.SPAM, score) }
    fun returnsHam(score: Double = -1.0) = FakeSpamClassifier { SpamVerdict(SpamLabel.HAM, score) }
}

class FakeTelephonyDataSource {
    val insertedMessages = mutableListOf<Message>()
    val conversations = mutableListOf<Conversation>()

    fun seed(conversations: List<Conversation>) {
        this.conversations.clear()
        this.conversations.addAll(conversations)
    }

    fun fakeConversations(filter: ConversationFilter = ConversationFilter.ALL): List<Conversation> {
        return when (filter) {
            ConversationFilter.ALL -> conversations
            ConversationFilter.UNREAD -> conversations.filter { !it.read }
            ConversationFilter.STARRED -> conversations.filter { it.isStarred }
            else -> conversations
        }
    }
}

object TestData {
    val sampleConversation = Conversation(
        threadId = ThreadId(1),
        participants = listOf(Participant(address = "+989121234567", displayName = "Ali")),
        snippet = "سلام چطوری؟",
        date = System.currentTimeMillis(),
        messageCount = 3,
        read = false,
        isStarred = false,
        isPinned = true,
    )
    val sampleRawHam = RawMessage(sender = "+989121234567", body = "سلام، فردا میبینمت", timestamp = System.currentTimeMillis())
    val sampleRawSpam = RawMessage(sender = "1000", body = "You won! Click URLTOKEN to claim prize", timestamp = System.currentTimeMillis())
}
