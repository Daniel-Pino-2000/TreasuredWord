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
     *  with the conversation so far in [language], and saves the answer. */
    suspend fun ask(conversationId: Long?, question: String, language: String): Exchange {
        val text = question.trim()
        require(text.isNotEmpty() && text.length <= MAX_QUESTION_CHARS) { "Question must be 1–$MAX_QUESTION_CHARS characters" }
        val id = conversationId ?: store.createConversation(conversationTitle(text)).id
        store.addMessage(id, AiRole.USER, text)
        return Exchange(id, send(id, language))
    }

    /** Sends a conversation's last question again, after a failure, without saving it twice. */
    suspend fun retry(conversationId: Long, language: String): AiAskResult = send(conversationId, language)

    suspend fun conversations(): List<AiConversation> = store.conversations()

    suspend fun messages(conversationId: Long): List<AiMessage> = store.messages(conversationId)

    suspend fun deleteConversation(conversationId: Long) = store.deleteConversation(conversationId)

    /** Reports an answer (#130); stored on the server without the user's ID. */
    suspend fun report(request: AiReportRequestDto): AiReportResult = api.report(request)

    private suspend fun send(conversationId: Long, language: String): AiAskResult {
        val history = historyToSend(store.messages(conversationId))
        require(history.lastOrNull()?.role == AiRole.USER.stored) { "Conversation $conversationId has no question to send" }
        val result = api.ask(AiAskRequestDto(history, language))
        if (result is AiAskResult.Answer) {
            store.addMessage(conversationId, AiRole.ASSISTANT, result.answer, result.crisis, result.promptVersion)
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
