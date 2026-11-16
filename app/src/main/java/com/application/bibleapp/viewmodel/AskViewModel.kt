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
import com.application.bibleapp.data.remote.AiHelperApi
import com.application.bibleapp.data.remote.RenderServerWake
import com.application.bibleapp.data.remote.ServerWake
import com.application.bibleapp.data.repository.AiHelperRepository
import com.application.bibleapp.data.repository.AiHelperRepository.Companion.MAX_ANSWER_CHARS
import com.application.bibleapp.data.repository.AiHelperRepository.Companion.MAX_QUESTION_CHARS
import com.application.bibleapp.data.repository.SqliteAiConversationStore
import com.application.bibleapp.data.repository.failureFromStored
import com.application.bibleapp.data.repository.resetsAtMillis
import com.application.bibleapp.data.repository.storedKind
import com.application.bibleapp.data.remote.nextUtcMidnight
import com.application.bibleapp.utils.NetworkUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * question while this conversation is open, so follow-ups keep it, and is saved with each one, so
 * reopening the conversation brings it back (#281).
 * [limit] is the daily limit or the resting service while it lasts, whichever conversation is
 * open (#271): nothing can be sent until it resets. [isWaking] is the part of [isSending] spent
 * waiting for a sleeping server to start, before the question goes (#280). [starter] is the starter
 * question this conversation began with (#284), for retrying it the same way.
 */
data class AskUiState(
    val conversationId: Long? = null,
    val messages: List<AiMessage> = emptyList(),
    val isSending: Boolean = false,
    val isWaking: Boolean = false,
    val failure: AiAskResult.Failure? = null,
    val remainingToday: Int? = null,
    val passage: AiQuestionPassage? = null,
    val limit: AiAskResult.Failure? = null,
    val starter: String? = null
)

/** The day's questions left, and when that count resets (00:00 UTC). */
data class RemainingToday(val count: Int, val resetsAtMillis: Long)

/**
 * The Bible Helper's settings on the phone ([SharedPreferencesAskSettings] in the app): whether
 * "Got it" was tapped on the one-time notice (#128), and the limit the last question hit and the
 * questions left, so a restart or a new conversation still knows them until they reset (#271).
 */
interface AskSettings {
    var noticeAccepted: Boolean
    /** An [AiAskResult.DailyLimit] or [AiAskResult.Resting], or null. */
    var limit: AiAskResult.Failure?
    var remainingToday: RemainingToday?
}

class SharedPreferencesAskSettings(context: Context) : AskSettings {
    private val prefs = context.applicationContext.getSharedPreferences("ai_helper", Context.MODE_PRIVATE)

    override var noticeAccepted: Boolean
        get() = prefs.getBoolean(KEY_NOTICE_ACCEPTED, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTICE_ACCEPTED, value) }

    override var limit: AiAskResult.Failure?
        get() {
            val kind = prefs.getString(KEY_LIMIT, null) ?: return null
            return failureFromStored(kind, prefs.getLong(KEY_LIMIT_RESETS_AT, 0L))
                .takeIf { it is AiAskResult.DailyLimit || it is AiAskResult.Resting }
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_LIMIT)
                remove(KEY_LIMIT_RESETS_AT)
            } else {
                putString(KEY_LIMIT, value.storedKind)
                putLong(KEY_LIMIT_RESETS_AT, value.resetsAtMillis ?: 0L)
            }
        }

    override var remainingToday: RemainingToday?
        get() = if (prefs.contains(KEY_REMAINING)) {
            RemainingToday(prefs.getInt(KEY_REMAINING, 0), prefs.getLong(KEY_REMAINING_RESETS_AT, 0L))
        } else {
            null
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_REMAINING)
                remove(KEY_REMAINING_RESETS_AT)
            } else {
                putInt(KEY_REMAINING, value.count)
                putLong(KEY_REMAINING_RESETS_AT, value.resetsAtMillis)
            }
        }

    private companion object {
        const val KEY_NOTICE_ACCEPTED = "notice_accepted"
        const val KEY_LIMIT = "limit"
        const val KEY_LIMIT_RESETS_AT = "limit_resets_at"
        const val KEY_REMAINING = "remaining_today"
        const val KEY_REMAINING_RESETS_AT = "remaining_resets_at"
    }
}

/**
 * Backs the Ask tab and its conversation list (#128). Activity-scoped like [BibleViewModel], so a
 * question keeps waiting for its answer while the person reads another tab, and the draft stays.
 */
class AskViewModel(
    private val repository: AiHelperRepository,
    private val settings: AskSettings,
    private val now: () -> Long = System::currentTimeMillis,
    /** Wakes the server before a question when it may be asleep (#280); null (tests) never waits. */
    private val serverWake: ServerWake? = null
) : ViewModel() {

    private val _state = MutableStateFlow(
        AskUiState(remainingToday = settings.remainingToday?.takeIf { now() < it.resetsAtMillis }?.count)
    )
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    /** Clears [AskUiState.limit] when it resets, if the app is still open then. */
    private var limitReset: Job? = null

    init {
        settings.limit?.let(::applyLimit)
    }

    /** Where the chat is scrolled, so it's still there after reading a cited verse (#278). */
    val chatScroll = AskChatScroll()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** The question [startWithPassage] wrote in the field, to clear if it goes unsent with its passage (#212). */
    private var passageQuestion: String? = null

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

    /**
     * Sends the draft in [language] (an [com.application.bibleapp.utils.AppLanguage] tag). [starter]
     * is the id of the starter question it came from (#284): a new conversation's first question,
     * without a passage, gets the server's ready answer, which doesn't use a daily question, so it
     * goes even while the day's limit lasts.
     */
    fun send(language: String, starter: String? = null) {
        val question = _draft.value.trim()
        val current = _state.value
        val asStarter = starter?.takeIf { current.conversationId == null && current.passage == null }
        // While the limit lasts the question stays in the field, to send after the reset (#271).
        if (question.isEmpty() || current.isSending || (current.limit != null && asStarter == null)) return
        _draft.value = ""
        passageQuestion = null
        val conversationId = _state.value.conversationId
        // Shown straight away; the saved copy replaces it once the repository has stored it.
        val passage = _state.value.passage
        val pending = AiMessage(PENDING_ID, conversationId ?: PENDING_ID, AiRole.USER, question, createdAt = "", passage = passage)
        _state.update { it.copy(messages = it.messages + pending, isSending = true, failure = null, starter = asStarter ?: it.starter) }
        viewModelScope.launch {
            val exchange = repository.ask(conversationId, question, language, passage, beforeSend = ::wakeServerIfAsleep, starter = asStarter)
            finish(exchange.conversationId, exchange.result, fromStarter = asStarter != null)
        }
    }

    /**
     * "Ask about this verse" (#132): a new conversation with [passage] attached and [question]
     * ready to edit in the field. Ignored while a question is waiting for its answer.
     */
    fun startWithPassage(passage: AiQuestionPassage, question: String) {
        if (_state.value.isSending) return
        _state.value = AskUiState(remainingToday = _state.value.remainingToday, passage = passage, limit = _state.value.limit)
        _draft.value = question.take(MAX_QUESTION_CHARS)
        passageQuestion = _draft.value
    }

    /**
     * Leaving a conversation leaves its passage behind, so "What does this passage mean?" would
     * no longer say which one (#212): cleared if still untouched. A draft the person wrote stays.
     */
    private fun dropPassageQuestion() {
        if (_draft.value == passageQuestion) _draft.value = ""
        passageQuestion = null
    }

    /** Takes the attached passage off, so the next questions go without it. */
    fun removePassage() {
        _state.update { it.copy(passage = null) }
    }

    /** Sends the conversation's unanswered last question again. */
    fun retry(language: String) {
        val current = _state.value
        val id = current.conversationId ?: return
        // A starter question that's still the only one goes the same way again (#284).
        val starter = current.starter?.takeIf { current.messages.size == 1 }
        if (current.isSending || (current.limit != null && starter == null)) return
        _state.update { it.copy(isSending = true, failure = null) }
        viewModelScope.launch {
            finish(id, repository.retry(id, language, beforeSend = ::wakeServerIfAsleep, starter = starter), fromStarter = starter != null)
        }
    }

    /**
     * Waits for a server that may be asleep before the question goes (#280), so the screen can
     * say so and the question keeps its whole timeout for the answer.
     */
    private suspend fun wakeServerIfAsleep() {
        val wake = serverWake ?: return
        if (wake.isAwake()) return
        _state.update { it.copy(isWaking = true) }
        try {
            wake.wake()
        } finally {
            _state.update { it.copy(isWaking = false) }
        }
    }

    fun newConversation() {
        if (_state.value.isSending) return
        _state.value = AskUiState(remainingToday = _state.value.remainingToday, limit = _state.value.limit)
        dropPassageQuestion()
    }

    fun openConversation(id: Long) {
        if (_state.value.isSending) return
        dropPassageQuestion()
        viewModelScope.launch {
            val messages = repository.messages(id)
            _state.value = AskUiState(
                conversationId = id,
                messages = messages,
                // The passage the last question was asked about stays on for follow-ups, as it was
                // before the conversation was closed (#281). Removed partway, it stays removed.
                passage = messages.lastOrNull { it.role == AiRole.USER }?.passage,
                // A question left without an answer says why again (#271): the daily limit until
                // it resets, otherwise Retry.
                failure = repository.unansweredFailure(id, now()),
                remainingToday = _state.value.remainingToday,
                limit = _state.value.limit
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
            // The limit and the count were the account's: another one may sign in next.
            limitReset?.cancel()
            settings.limit = null
            settings.remainingToday = null
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
                _state.value = AskUiState(remainingToday = _state.value.remainingToday, limit = _state.value.limit)
            }
            _conversations.value = repository.conversations()
        }
    }

    /** Shows [limit] until it resets, then clears it, here and on the phone (#271). */
    private fun applyLimit(limit: AiAskResult.Failure) {
        val resetsAt = limit.resetsAtMillis ?: return
        limitReset?.cancel()
        if (now() >= resetsAt) {
            clearLimit()
            return
        }
        _state.update { it.copy(limit = limit) }
        limitReset = viewModelScope.launch {
            delay(resetsAt - now())
            clearLimit()
        }
    }

    private fun clearLimit() {
        limitReset?.cancel()
        limitReset = null
        settings.limit = null
        _state.update { current ->
            current.copy(
                limit = null,
                // The day's count reset with it; the next answer brings the new one.
                remainingToday = if (current.limit is AiAskResult.DailyLimit) null else current.remainingToday,
                // A daily-limit or resting card under the open question can now be retried.
                failure = when (current.failure) {
                    is AiAskResult.DailyLimit, is AiAskResult.Resting -> AiAskResult.Unavailable
                    else -> current.failure
                }
            )
        }
    }

    /** Remembers what [result] says about the day's questions: the limit it hit, or what's left. A
     *  starter's ready answer ([fromStarter], #284) says nothing about the limit or the AI's rest:
     *  only its count of what's left is kept. */
    private fun rememberLimits(result: AiAskResult, fromStarter: Boolean) {
        if (fromStarter && result is AiAskResult.Answer) {
            result.remainingToday?.let { settings.remainingToday = RemainingToday(it, nextUtcMidnight(now())) }
            return
        }
        when (result) {
            is AiAskResult.DailyLimit, is AiAskResult.Resting -> {
                settings.limit = result
                settings.remainingToday = null
                applyLimit(result)
            }
            is AiAskResult.Answer -> {
                if (_state.value.limit != null || settings.limit != null) clearLimit()
                result.remainingToday?.let { settings.remainingToday = RemainingToday(it, nextUtcMidnight(now())) }
            }
            else -> Unit
        }
    }

    private suspend fun finish(conversationId: Long, result: AiAskResult, fromStarter: Boolean = false) {
        rememberLimits(result, fromStarter)
        val messages = repository.messages(conversationId)
        _conversations.value = repository.conversations()
        _state.update { current ->
            // The person may have opened another conversation meanwhile; this one is saved anyway.
            if (current.conversationId != null && current.conversationId != conversationId) {
                return@update current.copy(isSending = false, isWaking = false)
            }
            current.copy(
                conversationId = conversationId,
                messages = messages,
                isSending = false,
                isWaking = false,
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
        val appContext = context.applicationContext
        return AskViewModel(
            AiHelperRepository(
                SqliteAiConversationStore(appContext),
                AiHelperApi(phoneIsOnline = { NetworkUtils.isOnline(appContext) })
            ),
            SharedPreferencesAskSettings(appContext),
            serverWake = RenderServerWake
        ) as T
    }
}
