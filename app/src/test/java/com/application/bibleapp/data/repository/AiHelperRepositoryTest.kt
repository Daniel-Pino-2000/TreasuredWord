package com.application.bibleapp.data.repository

import com.application.bibleapp.data.model.AiConversation
import com.application.bibleapp.data.model.AiMessage
import com.application.bibleapp.data.model.AiRole
import com.application.bibleapp.data.model.conversationTitle
import com.application.bibleapp.data.remote.AiAskRequestDto
import com.application.bibleapp.data.remote.AiAskResult
import com.application.bibleapp.data.remote.AiHelperApi
import com.application.bibleapp.data.remote.AiMessageDto
import com.application.bibleapp.data.remote.HttpClientProvider
import com.application.bibleapp.data.repository.AiHelperRepository.Companion.MAX_ANSWER_CHARS
import com.application.bibleapp.data.repository.AiHelperRepository.Companion.MAX_HISTORY_CHARS
import com.application.bibleapp.data.repository.AiHelperRepository.Companion.MAX_HISTORY_MESSAGES
import com.application.bibleapp.data.repository.AiHelperRepository.Companion.historyToSend
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saving a conversation and trimming what's sent (#127), with an in-memory store and MockEngine. */
class AiHelperRepositoryTest {

    private class FakeStore : AiConversationStore {
        val conversations = mutableListOf<AiConversation>()
        val messages = mutableListOf<AiMessage>()

        override suspend fun createConversation(title: String) =
            AiConversation(conversations.size + 1L, title, "t", "t").also { conversations += it }

        override suspend fun addMessage(conversationId: Long, role: AiRole, content: String, crisis: Boolean, promptVersion: String?) =
            AiMessage(messages.size + 1L, conversationId, role, content, crisis, promptVersion, "t").also { messages += it }

        override suspend fun conversations() = conversations.toList()
        override suspend fun messages(conversationId: Long) = messages.filter { it.conversationId == conversationId }
        override suspend fun deleteConversation(conversationId: Long) {
            conversations.removeAll { it.id == conversationId }
            messages.removeAll { it.conversationId == conversationId }
        }
    }

    private val store = FakeStore()
    private val sentBodies = mutableListOf<AiAskRequestDto>()
    private var status = HttpStatusCode.OK

    private val repository = AiHelperRepository(
        store,
        AiHelperApi(
            client = {
                HttpClient(MockEngine { request ->
                    sentBodies += HttpClientProvider.json.decodeFromString(AiAskRequestDto.serializer(), (request.body as TextContent).text)
                    respond(
                        """{"answer":"Answer ${sentBodies.size}","crisis":false,"remainingToday":14,"promptVersion":"1"}""",
                        status,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }) { install(ContentNegotiation) { json(HttpClientProvider.json) } }
            },
            baseUrl = "https://backend.test/api/v1",
            log = {}
        )
    )

    @Test
    fun `a first question starts a conversation titled by it and saves both sides`() = runTest {
        val exchange = repository.ask(null, "  What does it mean to be born again? ", "en")

        assertEquals(AiAskResult.Answer("Answer 1", false, 14, "1"), exchange.result)
        assertEquals("What does it mean to be born again?", store.conversations.single().title)
        assertEquals(
            listOf(AiRole.USER to "What does it mean to be born again?", AiRole.ASSISTANT to "Answer 1"),
            store.messages.map { it.role to it.content }
        )
        assertEquals("1", store.messages.last().promptVersion)
    }

    @Test
    fun `a follow-up sends the conversation so far`() = runTest {
        val id = repository.ask(null, "First?", "es").conversationId
        repository.ask(id, "Second?", "es")

        assertEquals(
            listOf(AiMessageDto("user", "First?"), AiMessageDto("assistant", "Answer 1"), AiMessageDto("user", "Second?")),
            sentBodies.last().messages
        )
        assertEquals("es", sentBodies.last().language)
    }

    @Test
    fun `a failed question stays saved and retry sends it once more without saving it twice`() = runTest {
        status = HttpStatusCode.ServiceUnavailable
        val exchange = repository.ask(null, "Why?", "en")
        assertEquals(AiAskResult.Unavailable, exchange.result)
        assertEquals(listOf(AiRole.USER), store.messages.map { it.role })

        status = HttpStatusCode.OK
        val retried = repository.retry(exchange.conversationId, "en")

        assertTrue(retried is AiAskResult.Answer)
        assertEquals(listOf(AiRole.USER, AiRole.ASSISTANT), store.messages.map { it.role })
        assertEquals(listOf(AiMessageDto("user", "Why?")), sentBodies.last().messages)
    }

    @Test
    fun `deleting a conversation removes its messages`() = runTest {
        val id = repository.ask(null, "Q?", "en").conversationId
        repository.deleteConversation(id)

        assertTrue(repository.conversations().isEmpty())
        assertTrue(repository.messages(id).isEmpty())
    }

    // ---- historyToSend ----

    private fun conversation(vararg contents: String): List<AiMessage> = contents.mapIndexed { i, content ->
        AiMessage(i + 1L, 1, if (i % 2 == 0) AiRole.USER else AiRole.ASSISTANT, content, createdAt = "t")
    }

    @Test
    fun `a long conversation sends only its last 10 messages, ending with the new question`() {
        val messages = conversation(*Array(25) { "m$it" })

        val sent = historyToSend(messages)

        assertEquals(MAX_HISTORY_MESSAGES, sent.size)
        assertEquals((15..24).map { "m$it" }, sent.map { it.content })
        assertEquals("user", sent.last().role)
    }

    @Test
    fun `long answers drop the oldest messages to stay within 8,000 characters`() {
        val answer = "a".repeat(MAX_ANSWER_CHARS)
        val messages = conversation("q1", answer, "q2", answer, "q3", answer, "q4")

        val sent = historyToSend(messages)

        assertEquals(listOf("q3", answer, "q4"), sent.map { it.content })
        assertTrue(sent.sumOf { it.content.length } <= MAX_HISTORY_CHARS)
    }

    @Test
    fun `the new question is always sent, however long the history`() {
        val messages = conversation("q1", "a".repeat(MAX_ANSWER_CHARS), "q".repeat(1_000))

        val sent = historyToSend(messages)

        assertEquals("q".repeat(1_000), sent.last().content)
        assertTrue(sent.sumOf { it.content.length } <= MAX_HISTORY_CHARS)
    }

    @Test
    fun `an answer longer than the contract allows is cut`() {
        val sent = historyToSend(conversation("q1", "a".repeat(MAX_ANSWER_CHARS + 500), "q2"))

        assertEquals(MAX_ANSWER_CHARS, sent[1].content.length)
    }

    @Test
    fun `an unanswered question gives way to the next one so roles alternate`() {
        val messages = listOf(
            AiMessage(1, 1, AiRole.USER, "q1", createdAt = "t"),
            AiMessage(2, 1, AiRole.ASSISTANT, "a1", createdAt = "t"),
            AiMessage(3, 1, AiRole.USER, "failed", createdAt = "t"),
            AiMessage(4, 1, AiRole.USER, "q2", createdAt = "t")
        )

        assertEquals(listOf("q1", "a1", "q2"), historyToSend(messages).map { it.content })
    }

    // ---- conversationTitle ----

    @Test
    fun `a short question is its own title, on one line`() {
        assertEquals("Who was Paul?", conversationTitle("  Who was\nPaul? "))
    }

    @Test
    fun `a long question is cut at a word`() {
        val title = conversationTitle(
            "What does the Bible say about how a Christian should pray when they feel far from God?"
        )

        assertEquals("What does the Bible say about how a Christian should pray…", title)
    }

    @Test
    fun `a long question without spaces is cut at the length`() {
        val title = conversationTitle("耶".repeat(100))

        assertEquals("耶".repeat(60) + "…", title)
    }
}
