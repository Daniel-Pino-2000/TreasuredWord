package com.application.bibleapp.viewmodel

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.application.bibleapp.data.model.AiConversation
import com.application.bibleapp.data.model.AiMessage
import com.application.bibleapp.data.model.AiRole
import com.application.bibleapp.data.remote.AiAskResult
import com.application.bibleapp.data.repository.AiHelperRepository
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
 * last answer.
 */
data class AskUiState(
    val conversationId: Long? = null,
    val messages: List<AiMessage> = emptyList(),
    val isSending: Boolean = false,
    val failure: AiAskResult.Failure? = null,
    val remainingToday: Int? = null
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
        viewModelScope.launch {
            val exchange = repository.ask(conversationId, question, language)
            finish(exchange.conversationId, exchange.result)
        }
    }

    /** Sends the conversation's unanswered last question again. */
    fun retry(language: String) {
        val id = _state.value.conversationId ?: return
        if (_state.value.isSending) return
        _state.update { it.copy(isSending = true, failure = null) }
        viewModelScope.launch { finish(id, repository.retry(id, language)) }
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

    /** Signing out removes the conversations from the phone (#127); forget the open one too. */
    fun onSignedOut() {
        _state.value = AskUiState()
        _draft.value = ""
        _conversations.value = emptyList()
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
