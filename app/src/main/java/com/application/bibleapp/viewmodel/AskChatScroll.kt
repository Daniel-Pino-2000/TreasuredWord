package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.AiRole
import com.application.bibleapp.data.remote.AiAskResult

/**
 * Where the Bible Helper chat is scrolled, kept by [AskViewModel] so that opening a cited verse and
 * coming back finds the chat where it was (#278). The tabs push a new Ask screen each time, whose
 * list would otherwise start at the top, and the screen's automatic scroll runs again whenever it
 * comes back. So this also remembers what the last automatic scroll was for, and [autoScrollTarget]
 * only moves the list when something has changed since.
 */
class AskChatScroll {
    /** The list's first visible item and how far it's scrolled into it, as the person left them. */
    var firstVisibleItem: Int = 0
        private set
    var firstVisibleItemOffset: Int = 0
        private set

    private var scrolledFor: Reason? = null

    fun savePosition(item: Int, offset: Int) {
        firstVisibleItem = item
        firstVisibleItemOffset = offset
    }

    /**
     * The item to scroll the chat to for [state], or null to leave it where it is: a new question
     * goes down to the "Thinking…" row, or the failure card, right after the messages; an answer
     * shows from its question, so from its first line; the server-waking hint ([waitingLong]) makes
     * that row taller, so it scrolls again then (#213); a new conversation starts at the top.
     * Indexes come from the state, not the list's layout, which hasn't caught up yet when this runs.
     * With messages there's no intro item, so the "Thinking…" row or failure card is right after them.
     */
    fun autoScrollTarget(state: AskUiState, waitingLong: Boolean): Int? {
        val reason = Reason(state.conversationId, state.messages.size, state.isSending, state.failure, waitingLong)
        if (reason == scrolledFor) return null
        scrolledFor = reason
        val lastQuestion = state.messages.indexOfLast { it.role == AiRole.USER }
        return when {
            state.messages.isEmpty() -> 0
            state.isSending || state.failure != null -> state.messages.size
            lastQuestion >= 0 -> lastQuestion
            else -> null
        }
    }

    private data class Reason(
        val conversationId: Long?,
        val messages: Int,
        val isSending: Boolean,
        val failure: AiAskResult.Failure?,
        val waitingLong: Boolean
    )
}
