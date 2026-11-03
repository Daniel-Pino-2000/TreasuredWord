package com.application.bibleapp.viewmodel

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.application.bibleapp.data.model.AiConversation
import com.application.bibleapp.data.model.AiMessage
import com.application.bibleapp.data.model.AiQuestionPassage
import com.application.bibleapp.data.model.AiRole
import com.application.bibleapp.data.remote.AiAskResult
import com.application.bibleapp.data.remote.AiReportReason
import com.application.bibleapp.data.remote.AiReportRequestDto
import com.application.bibleapp.data.remote.AiReportResult
import com.application.bibleapp.data.repository.AiHelperRepository
import com.application.bibleapp.data.repository.AiHelperRepository.Companion.MAX_ANSWER_CHARS
import com.application.bibleapp.data.repository.AiHelperRepository.Companion.MAX_QUESTION_CHARS
import com.application.bibleapp.data.repository.SqliteAiConversationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The open Bible Helper conversation (#128). [conversationId] is null for a new one, which the
 * first question creates. [failure] is why the last question has no answer, shown under it with
 * Retry where trying again can help. [remainingToday] is the day's questions left, from the
 * last answer. [passage] is the one "Ask about this verse" attached (#132): it goes with every
 * question while this conversation is open, so follow-ups keep it, and isn't saved with it.
 */
data class AskUiState(
    val conversationId: Long? = null,
    val messages: List<AiMessage> = emptyList(),
    val isSending: Boolean = false,
    val failure: AiAskResult.Failure? = null,
    val remainingToday: Int? = null,
    val passage: AiQuestionPassage? = null
)

/** Whether "Got it" was tapped on the one-time notice (#128); [SharedPreferencesAskSettings] in the app. */
interface AskSettings {
    var noticeAccepted: Boolean
}

class SharedPreferencesAskSettings(context: Context) : AskSettings {
    private val prefs = context.applicationContext.getSharedPreferences("ai_helper", Context.MODE_PRIVATE)

    override var noticeAccepted: Boolean
        get() = prefs.getBoolean(KEY_NOTICE_ACCEPTED, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTICE_ACCEPTED, value) }

    private companion object {
        const val KEY_NOTICE_ACCEPTED = "notice_accepted"
    }
}

/**
 * Backs the Ask tab and its conversation list (#128). Activity-scoped like [BibleViewModel], so a
 * question keeps waiting for its answer while the person reads another tab, and the draft stays.
 */
class AskViewModel(
    private val repository: AiHelperRepository,
    private val settings: AskSettings
) : ViewModel() {

    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _conversations = MutableStateFlow<List<AiConversation>>(emptyList())
    val conversations: StateFlow<List<AiConversation>> = _conversations.asStateFlow()

    private val _noticeAccepted = MutableStateFlow(settings.noticeAccepted)
    val noticeAccepted: StateFlow<Boolean> = _noticeAccepted.asStateFlow()

    fun acceptNotice() {
        settings.noticeAccepted = true
        _noticeAccepted.value = true
    }

    /** The field's text, cut at the contract's [MAX_QUESTION_CHARS]. */
    fun onDraftChange(text: String) {
        _draft.value = text.take(MAX_QUESTION_CHARS)
    }

    /** Sends the draft in [language] (an [com.application.bibleapp.utils.AppLanguage] tag). */
    fun send(language: String) {
        val question = _draft.value.trim()
        if (question.isEmpty() || _state.value.isSending) return
        _draft.value = ""
        val conversationId = _state.value.conversationId
        // Shown straight away; the saved copy replaces it once the repository has stored it.
        val pending = AiMessage(PENDING_ID, conversationId ?: PENDING_ID, AiRole.USER, question, createdAt = "")
        _state.update { it.copy(messages = it.messages + pending, isSending = true, failure = null) }
        val passage = _state.value.passage?.dto
        viewModelScope.launch {
            val exchange = repository.ask(conversationId, question, language, passage)
            finish(exchange.conversationId, exchange.result)
        }
    }

    /**
     * "Ask about this verse" (#132): a new conversation with [passage] attached and [question]
     * ready to edit in the field. Ignored while a question is waiting for its answer.
     */
    fun startWithPassage(passage: AiQuestionPassage, question: String) {
        if (_state.value.isSending) return
        _state.value = AskUiState(remainingToday = _state.value.remainingToday, passage = passage)
        _draft.value = question.take(MAX_QUESTION_CHARS)
    }

    /** Takes the attached passage off, so the next questions go without it. */
    fun removePassage() {
        _state.update { it.copy(passage = null) }
    }

    /** Sends the conversation's unanswered last question again. */
    fun retry(language: String) {
        val id = _state.value.conversationId ?: return
        if (_state.value.isSending) return
        _state.update { it.copy(isSending = true, failure = null) }
        val passage = _state.value.passage?.dto
        viewModelScope.launch { finish(id, repository.retry(id, language, passage)) }
    }

    fun newConversation() {
        if (_state.value.isSending) return
        _state.value = AskUiState(remainingToday = _state.value.remainingToday)
    }

    fun openConversation(id: Long) {
        if (_state.value.isSending) return
        viewModelScope.launch {
            val messages = repository.messages(id)
            _state.value = AskUiState(
                conversationId = id,
                messages = messages,
                // A question left without an answer (the app closed, or it failed): offer Retry.
                failure = if (messages.lastOrNull()?.role == AiRole.USER) AiAskResult.Unavailable else null,
                remainingToday = _state.value.remainingToday
            )
        }
    }

    /**
     * Reports the answer [answerId] in the open conversation (#130), with the question it answered
     * and the instructions' version. The server stores it without the user's ID.
     */
    suspend fun report(answerId: Long, reason: AiReportReason, comment: String?, language: String): AiReportResult {
        val messages = _state.value.messages
        val index = messages.indexOfFirst { it.id == answerId && it.role == AiRole.ASSISTANT }
        val answer = messages.getOrNull(index) ?: return AiAskResult.Unavailable
        val question = messages.take(index).lastOrNull { it.role == AiRole.USER } ?: return AiAskResult.Unavailable
        return repository.report(
            AiReportRequestDto(
                question = question.content.take(MAX_QUESTION_CHARS),
                answer = answer.content.take(MAX_ANSWER_CHARS),
                reason = reason,
                comment = comment,
                language = language,
                promptVersion = answer.promptVersion
            )
        )
    }

    private var signedIn: Boolean? = null

    /**
     * The Ask tab tells this whether the person is signed in each time it shows. Signing out
     * removes the conversations from the phone (#127), so the open one is forgotten too; but a
     * passage attached while signed out waits for the sign-in.
     */
    fun onSignInChanged(isSignedIn: Boolean) {
        if (signedIn == true && !isSignedIn) {
            _state.value = AskUiState()
            _draft.value = ""
            _conversations.value = emptyList()
        }
        signedIn = isSignedIn
    }

    fun refreshConversations() {
        viewModelScope.launch { _conversations.value = repository.conversations() }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            repository.deleteConversation(id)
            if (_state.value.conversationId == id && !_state.value.isSending) {
                _state.value = AskUiState(remainingToday = _state.value.remainingToday)
            }
            _conversations.value = repository.conversations()
        }
    }

    private suspend fun finish(conversationId: Long, result: AiAskResult) {
        val messages = repository.messages(conversationId)
        _conversations.value = repository.conversations()
        _state.update { current ->
            // The person may have opened another conversation meanwhile; this one is saved anyway.
            if (current.conversationId != null && current.conversationId != conversationId) {
                return@update current.copy(isSending = false)
            }
            current.copy(
                conversationId = conversationId,
                messages = messages,
                isSending = false,
                failure = result as? AiAskResult.Failure,
                remainingToday = when (result) {
                    is AiAskResult.Answer -> result.remainingToday ?: current.remainingToday
                    is AiAskResult.DailyLimit -> 0
                    else -> current.remainingToday
                }
            )
        }
    }

    private companion object {
        const val PENDING_ID = -1L
    }
}

class AskViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AskViewModel::class.java)) { "Unknown ViewModel class" }
        @Suppress("UNCHECKED_CAST")
        return AskViewModel(
            AiHelperRepository(SqliteAiConversationStore(context)),
            SharedPreferencesAskSettings(context)
        ) as T
    }
}
