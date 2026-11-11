package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.AiMessage
import com.application.bibleapp.data.model.AiRole
import com.application.bibleapp.data.remote.AiAskResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the Bible Helper chat scrolls by itself, and when it stays where the person left it (#278). */
class AskChatScrollTest {

    private val scroll = AskChatScroll()

    private fun message(id: Long, role: AiRole) = AiMessage(id, 1, role, "m$id", createdAt = "")

    private val answered = AskUiState(
        conversationId = 1,
        messages = listOf(message(1, AiRole.USER), message(2, AiRole.ASSISTANT), message(3, AiRole.USER), message(4, AiRole.ASSISTANT))
    )

    @Test
    fun `an answer shows from its question, so from its first line`() {
        assertEquals(2, scroll.autoScrollTarget(answered, waitingLong = false))
    }

    @Test
    fun `the same state again, as when the screen comes back, doesn't move the chat`() {
        scroll.autoScrollTarget(answered, waitingLong = false)

        assertNull(scroll.autoScrollTarget(answered, waitingLong = false))
    }

    @Test
    fun `a new question goes down to the thinking row, after the messages`() {
        scroll.autoScrollTarget(answered, waitingLong = false)
        val sending = answered.copy(messages = answered.messages + message(-1, AiRole.USER), isSending = true)

        assertEquals(5, scroll.autoScrollTarget(sending, waitingLong = false))
    }

    @Test
    fun `the server-waking hint scrolls again (#213)`() {
        val sending = answered.copy(messages = answered.messages + message(-1, AiRole.USER), isSending = true)
        scroll.autoScrollTarget(sending, waitingLong = false)

        assertEquals(5, scroll.autoScrollTarget(sending, waitingLong = true))
    }

    @Test
    fun `a failure shows its card, after the messages`() {
        val failed = answered.copy(messages = answered.messages + message(5, AiRole.USER), failure = AiAskResult.Unavailable)

        assertEquals(5, scroll.autoScrollTarget(failed, waitingLong = false))
    }

    @Test
    fun `a new conversation starts at the top`() {
        scroll.autoScrollTarget(answered, waitingLong = false)

        assertEquals(0, scroll.autoScrollTarget(AskUiState(), waitingLong = false))
    }

    @Test
    fun `another conversation with as many messages still scrolls`() {
        scroll.autoScrollTarget(answered, waitingLong = false)

        assertEquals(2, scroll.autoScrollTarget(answered.copy(conversationId = 2), waitingLong = false))
    }

    @Test
    fun `the saved position is what the person left`() {
        scroll.savePosition(item = 3, offset = 120)

        assertEquals(3, scroll.firstVisibleItem)
        assertEquals(120, scroll.firstVisibleItemOffset)
    }
}
