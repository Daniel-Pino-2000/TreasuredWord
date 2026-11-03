package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.AiConversation
import com.application.bibleapp.data.model.AiMessage
import com.application.bibleapp.data.model.AiRole
import com.application.bibleapp.data.remote.AiAskResult
import com.application.bibleapp.data.remote.AiHelperApi
import com.application.bibleapp.data.remote.HttpClientProvider
import com.application.bibleapp.data.repository.AiConversationStore
import com.application.bibleapp.data.repository.AiHelperRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Ask tab's state (#128), with an in-memory store and MockEngine. */
@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {

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

    private class FakeSettings : AskSettings {
        override var noticeAccepted = false
    }

    private val store = FakeStore()
    private val settings = FakeSettings()
    private var status = HttpStatusCode.OK
    private var body = """{"answer":"Grace [[EPH 2:8]]","crisis":false,"remainingToday":3,"promptVersion":"1"}"""

    private lateinit var viewModel: AskViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val api = AiHelperApi(
            client = {
                HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }) {
                    install(ContentNegotiation) { json(HttpClientProvider.json) }
                }
            },
            baseUrl = "https://backend.test/api/v1",
            now = { 0L },
            log = {}
        )
        viewModel = AskViewModel(AiHelperRepository(store, api), settings)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Sends [question] and waits for the answer, which MockEngine gives on another thread. */
    private suspend fun ask(question: String) {
        viewModel.onDraftChange(question)
        viewModel.send("en")
        awaitAnswer()
    }

    private suspend fun awaitAnswer() {
        viewModel.state.first { !it.isSending }
    }

    @Test
    fun `a question and its answer are shown and saved, and the draft clears`() = runTest {
        ask("How can I be saved?")

        val state = viewModel.state.value
        assertEquals(1L, state.conversationId)
        assertEquals(listOf(AiRole.USER, AiRole.ASSISTANT), state.messages.map { it.role })
        assertEquals("Grace [[EPH 2:8]]", state.messages.last().content)
        assertFalse(state.isSending)
        assertNull(state.failure)
        assertEquals(3, state.remainingToday)
        assertEquals("", viewModel.draft.value)
        assertEquals(listOf("How can I be saved?"), viewModel.conversations.value.map { it.title })
    }

    @Test
    fun `a blank draft isn't sent`() = runTest {
        ask("   ")

        assertTrue(viewModel.state.value.messages.isEmpty())
        assertTrue(store.messages.isEmpty())
    }

    @Test
    fun `the draft stops at the contract's 1,000 characters`() {
        viewModel.onDraftChange("a".repeat(1_200))

        assertEquals(1_000, viewModel.draft.value.length)
    }

    @Test
    fun `the daily limit keeps the question and says when it resets`() = runTest {
        status = HttpStatusCode.TooManyRequests
        body = """{"code":"AI_DAILY_LIMIT","message":"x"}"""

        ask("What is the Trinity?")

        val state = viewModel.state.value
        assertEquals(AiAskResult.DailyLimit(24 * 60 * 60 * 1000L), state.failure)
        assertEquals(listOf("What is the Trinity?"), state.messages.map { it.content })
        assertEquals(0, state.remainingToday)
    }

    @Test
    fun `retry answers the question left without one`() = runTest {
        status = HttpStatusCode.ServiceUnavailable
        body = """{"code":"AI_UNAVAILABLE","message":"x"}"""
        ask("How should I pray?")
        assertEquals(AiAskResult.Unavailable, viewModel.state.value.failure)

        status = HttpStatusCode.OK
        body = """{"answer":"Like this","crisis":false,"remainingToday":10}"""
        viewModel.retry("en")
        awaitAnswer()

        val state = viewModel.state.value
        assertNull(state.failure)
        assertEquals(listOf("How should I pray?", "Like this"), state.messages.map { it.content })
        assertEquals(2, store.messages.size) // the question wasn't saved twice
    }

    @Test
    fun `an opened conversation ending in a question offers retry`() = runTest {
        status = HttpStatusCode.ServiceUnavailable
        ask("Where should I start?")
        viewModel.newConversation()
        assertTrue(viewModel.state.value.messages.isEmpty())

        viewModel.openConversation(1L)

        assertEquals(AiAskResult.Unavailable, viewModel.state.value.failure)
        assertEquals(1L, viewModel.state.value.conversationId)
    }

    @Test
    fun `a follow-up question goes into the same conversation`() = runTest {
        ask("Who was Moses?")
        ask("And Aaron?")

        assertEquals(1, store.conversations.size)
        assertEquals(4, viewModel.state.value.messages.size)
    }

    @Test
    fun `deleting the open conversation starts a new one`() = runTest {
        ask("Who was Ruth?")

        viewModel.deleteConversation(1L)

        assertNull(viewModel.state.value.conversationId)
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertTrue(viewModel.conversations.value.isEmpty())
    }

    @Test
    fun `signing out forgets the open conversation and the draft`() = runTest {
        ask("Who was David?")
        viewModel.onDraftChange("Half a question")

        viewModel.onSignedOut()

        assertEquals(AskUiState(), viewModel.state.value)
        assertEquals("", viewModel.draft.value)
    }

    @Test
    fun `got it on the notice is remembered`() {
        assertFalse(viewModel.noticeAccepted.value)

        viewModel.acceptNotice()

        assertTrue(viewModel.noticeAccepted.value)
        assertTrue(settings.noticeAccepted)
    }
}
