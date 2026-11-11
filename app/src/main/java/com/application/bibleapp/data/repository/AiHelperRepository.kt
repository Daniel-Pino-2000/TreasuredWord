package com.application.bibleapp.data.repository

import android.content.Context
import com.application.bibleapp.data.local.BibleDatabaseManager
import com.application.bibleapp.data.model.AiConversation
import com.application.bibleapp.data.model.AiMessage
import com.application.bibleapp.data.model.AiRole
import com.application.bibleapp.data.model.conversationTitle
import com.application.bibleapp.data.remote.AiAskRequestDto
import com.application.bibleapp.data.remote.AiAskResult
import com.application.bibleapp.data.remote.AiHelperApi
import com.application.bibleapp.data.remote.AiMessageDto
import com.application.bibleapp.data.remote.AiPassageDto
import com.application.bibleapp.data.remote.AiReportRequestDto
import com.application.bibleapp.data.remote.AiReportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where conversations are kept: [SqliteAiConversationStore] in the app, a list in tests. */
interface AiConversationStore {
    suspend fun createConversation(title: String): AiConversation
    suspend fun addMessage(conversationId: Long, role: AiRole, content: String, crisis: Boolean = false, promptVersion: String? = null): AiMessage
    suspend fun conversations(): List<AiConversation>
    suspend fun messages(conversationId: Long): List<AiMessage>
    suspend fun deleteConversation(conversationId: Long)

    /** Stores why [conversationId]'s last question has no answer, or clears it with null (#271). */
    suspend fun setFailure(conversationId: Long, failure: AiAskResult.Failure?)

    /** What [setFailure] stored, as it was stored (a daily limit may have reset since). */
    suspend fun failure(conversationId: Long): AiAskResult.Failure?
}

class SqliteAiConversationStore(context: Context) : AiConversationStore {
    private val context = context.applicationContext

    override suspend fun createConversation(title: String) = withContext(Dispatchers.IO) {
        BibleDatabaseManager.insertAiConversation(context, title)
    }

    override suspend fun addMessage(conversationId: Long, role: AiRole, content: String, crisis: Boolean, promptVersion: String?) =
        withContext(Dispatchers.IO) {
            BibleDatabaseManager.insertAiMessage(context, conversationId, role, content, crisis, promptVersion)
        }

    override suspend fun conversations() = withContext(Dispatchers.IO) { BibleDatabaseManager.getAiConversations(context) }

    override suspend fun messages(conversationId: Long) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.getAiMessages(context, conversationId) }

    override suspend fun deleteConversation(conversationId: Long) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.deleteAiConversation(context, conversationId) }

    override suspend fun setFailure(conversationId: Long, failure: AiAskResult.Failure?) = withContext(Dispatchers.IO) {
        BibleDatabaseManager.setAiConversationFailure(context, conversationId, failure?.storedKind, failure?.resetsAtMillis)
    }

    override suspend fun failure(conversationId: Long) = withContext(Dispatchers.IO) {
        BibleDatabaseManager.getAiConversationFailure(context, conversationId)?.let { (kind, resetsAt) -> failureFromStored(kind, resetsAt) }
    }
}

/** How a failure is stored with its conversation (#271): its kind, and for the daily limit and
 *  the resting service, when it ends. */
internal val AiAskResult.Failure.storedKind: String
    get() = when (this) {
        is AiAskResult.DailyLimit -> "DAILY_LIMIT"
        is AiAskResult.Resting -> "RESTING"
        AiAskResult.RateLimited -> "RATE_LIMITED"
        AiAskResult.Offline -> "OFFLINE"
        AiAskResult.SignedOut -> "SIGNED_OUT"
        AiAskResult.Unavailable -> "UNAVAILABLE"
    }

internal val AiAskResult.Failure.resetsAtMillis: Long?
    get() = when (this) {
        is AiAskResult.DailyLimit -> resetsAtMillis
        is AiAskResult.Resting -> resetsAtMillis
        else -> null
    }

/** The failure [storedKind] wrote; an unknown kind reads as [AiAskResult.Unavailable]. */
internal fun failureFromStored(kind: String, resetsAtMillis: Long?): AiAskResult.Failure = when (kind) {
    "DAILY_LIMIT" -> resetsAtMillis?.let { AiAskResult.DailyLimit(it) } ?: AiAskResult.Unavailable
    "RESTING" -> resetsAtMillis?.let { AiAskResult.Resting(it) } ?: AiAskResult.Unavailable
    "RATE_LIMITED" -> AiAskResult.RateLimited
    "OFFLINE" -> AiAskResult.Offline
    "SIGNED_OUT" -> AiAskResult.SignedOut
    else -> AiAskResult.Unavailable
}

/**
 * The Bible Helper's conversations (#127): each question is saved on the phone before it's sent,
 * so it survives a failed request (the screen offers [retry]), and the answer is saved when it
 * comes back. The server keeps nothing, so every request carries the recent history itself,
 * trimmed by [historyToSend].
 */
class AiHelperRepository(
    private val store: AiConversationStore,
    private val api: AiHelperApi = AiHelperApi()
) {

    /** [conversationId] is the conversation the question went into — a new one when [ask] was
     *  given none — even when [result] is a failure. */
    data class Exchange(val conversationId: Long, val result: AiAskResult)

    /** Saves [question] (at most [MAX_QUESTION_CHARS]; the screen limits the field), sends it
     *  with the conversation so far in [language], and saves the answer. [passage] is the one
     *  "Ask about this verse" attached (#132); it goes with the request but isn't saved.
     *  [beforeSend] runs once the question is saved, just before it goes: the time to wake the
     *  server (#280). */
    suspend fun ask(
        conversationId: Long?,
        question: String,
        language: String,
        passage: AiPassageDto? = null,
        beforeSend: suspend () -> Unit = {}
    ): Exchange {
        val text = question.trim()
        require(text.isNotEmpty() && text.length <= MAX_QUESTION_CHARS) { "Question must be 1–$MAX_QUESTION_CHARS characters" }
        val id = conversationId ?: store.createConversation(conversationTitle(text)).id
        store.addMessage(id, AiRole.USER, text)
        beforeSend()
        return Exchange(id, send(id, language, passage))
    }

    /** Sends a conversation's last question again, after a failure, without saving it twice. */
    suspend fun retry(
        conversationId: Long,
        language: String,
        passage: AiPassageDto? = null,
        beforeSend: suspend () -> Unit = {}
    ): AiAskResult {
        beforeSend()
        return send(conversationId, language, passage)
    }

    suspend fun conversations(): List<AiConversation> = store.conversations()

    suspend fun messages(conversationId: Long): List<AiMessage> = store.messages(conversationId)

    suspend fun deleteConversation(conversationId: Long) = store.deleteConversation(conversationId)

    /**
     * Why [conversationId]'s unanswered last question has no answer, to show again when it's
     * reopened (#271), or null if its last message is an answer. A daily limit or rest that has
     * reset by [nowMillis], a short wait (too many at once) or a sign-out that's over, or a
     * reason that was never stored (the app closed while waiting) all read as
     * [AiAskResult.Unavailable], whose card offers Retry.
     */
    suspend fun unansweredFailure(conversationId: Long, nowMillis: Long): AiAskResult.Failure? {
        if (store.messages(conversationId).lastOrNull()?.role != AiRole.USER) return null
        return when (val stored = store.failure(conversationId)) {
            is AiAskResult.DailyLimit -> stored.takeIf { nowMillis < it.resetsAtMillis }
            is AiAskResult.Resting -> stored.takeIf { nowMillis < it.resetsAtMillis }
            AiAskResult.Offline -> stored
            else -> null
        } ?: AiAskResult.Unavailable
    }

    /** Reports an answer (#130); stored on the server without the user's ID. */
    suspend fun report(request: AiReportRequestDto): AiReportResult = api.report(request)

    private suspend fun send(conversationId: Long, language: String, passage: AiPassageDto?): AiAskResult {
        val history = historyToSend(store.messages(conversationId))
        require(history.lastOrNull()?.role == AiRole.USER.stored) { "Conversation $conversationId has no question to send" }
        val result = api.ask(AiAskRequestDto(history, language, passage))
        when (result) {
            is AiAskResult.Answer -> {
                store.addMessage(conversationId, AiRole.ASSISTANT, result.answer, result.crisis, result.promptVersion)
                store.setFailure(conversationId, null)
            }
            is AiAskResult.Failure -> store.setFailure(conversationId, result)
        }
        return result
    }

    companion object {
        /** The contract's limits (docs/ai_helper_plan.md section 5); the server answers 400 past them. */
        const val MAX_QUESTION_CHARS = 1_000
        const val MAX_ANSWER_CHARS = 4_000
        const val MAX_HISTORY_MESSAGES = 10
        const val MAX_HISTORY_CHARS = 8_000

        /**
         * What one request sends from [messages] (oldest first, the newest a question): at most
         * [MAX_HISTORY_MESSAGES] and [MAX_HISTORY_CHARS] in all, dropping the oldest and never
         * the newest. Roles must alternate, so a question that never got an answer (it failed,
         * and the person asked something else) gives way to the one after it.
         */
        internal fun historyToSend(messages: List<AiMessage>): List<AiMessageDto> {
            val alternating = mutableListOf<AiMessage>()
            for (message in messages) {
                if (alternating.lastOrNull()?.role == message.role) alternating.removeAt(alternating.lastIndex)
                alternating += message
            }

            val kept = ArrayDeque<AiMessageDto>()
            var chars = 0
            for (message in alternating.asReversed()) {
                val limit = if (message.role == AiRole.USER) MAX_QUESTION_CHARS else MAX_ANSWER_CHARS
                val content = message.content.take(limit)
                val isNewest = kept.isEmpty()
                if (!isNewest && (kept.size == MAX_HISTORY_MESSAGES || chars + content.length > MAX_HISTORY_CHARS)) break
                kept.addFirst(AiMessageDto(message.role.stored, content))
                chars += content.length
            }
            return kept.toList()
        }
    }
}
