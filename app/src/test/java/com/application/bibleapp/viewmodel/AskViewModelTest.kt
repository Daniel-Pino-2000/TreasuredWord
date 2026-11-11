package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.AiConversation
import com.application.bibleapp.data.model.AiMessage
import com.application.bibleapp.data.model.AiRole
import com.application.bibleapp.data.remote.AiAskResult
import com.application.bibleapp.data.model.AiQuestionPassage
import com.application.bibleapp.data.remote.AiAskRequestDto
import com.application.bibleapp.data.remote.AiHelperApi
import com.application.bibleapp.data.remote.AiPassageDto
import com.application.bibleapp.data.remote.AiReportReason
import com.application.bibleapp.data.remote.AiReportRequestDto
import com.application.bibleapp.data.remote.AiReportResult
import com.application.bibleapp.data.remote.HttpClientProvider
import com.application.bibleapp.data.remote.ServerWake
import com.application.bibleapp.data.repository.AiConversationStore
import com.application.bibleapp.data.repository.AiHelperRepository
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

        override suspend fun addMessage(
            conversationId: Long, role: AiRole, content: String, crisis: Boolean, promptVersion: String?, passage: AiQuestionPassage?
        ) = AiMessage(messages.size + 1L, conversationId, role, content, crisis, promptVersion, "t", passage).also { messages += it }

        override suspend fun conversations() = conversations.toList()
        override suspend fun messages(conversationId: Long) = messages.filter { it.conversationId == conversationId }
        override suspend fun deleteConversation(conversationId: Long) {
            conversations.removeAll { it.id == conversationId }
            messages.removeAll { it.conversationId == conversationId }
        }

        val failures = mutableMapOf<Long, AiAskResult.Failure>()
        override suspend fun setFailure(conversationId: Long, failure: AiAskResult.Failure?) {
            if (failure == null) failures.remove(conversationId) else failures[conversationId] = failure
        }
        override suspend fun failure(conversationId: Long) = failures[conversationId]
    }

    private class FakeSettings : AskSettings {
        override var noticeAccepted = false
        override var limit: AiAskResult.Failure? = null
        override var remainingToday: RemainingToday? = null
    }

    private val store = FakeStore()
    private val settings = FakeSettings()
    private var status = HttpStatusCode.OK
    private var body = """{"answer":"Grace [[EPH 2:8]]","crisis":false,"remainingToday":3,"promptVersion":"1"}"""

    private val sent = mutableListOf<Pair<String, String>>()

    /** The phone's clock, the same for the API's reset times and the view model (#271). */
    private var clock = 0L
    private lateinit var viewModel: AskViewModel
    private lateinit var api: AiHelperApi

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api = AiHelperApi(
            client = {
                HttpClient(MockEngine { request ->
                    sent += request.url.encodedPath to (request.body as TextContent).text
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }) {
                    install(ContentNegotiation) { json(HttpClientProvider.json) }
                }
            },
            baseUrl = "https://backend.test/api/v1",
            now = { clock },
            log = {}
        )
        viewModel = AskViewModel(AiHelperRepository(store, api), settings, now = { clock })
    }

    @After
    fun tearDown() {
        // A coroutine still running when the test ends (a MockEngine answer on its way, a limit's
        // reset timer) would resume after resetMain, with no Main dispatcher, and fail whichever
        // test runs next with UncaughtExceptionsBeforeTest (#280). Stop them first.
        viewModel.viewModelScope.cancel()
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

    /** [awaitAnswer] without suspending, for tests about the limit's reset timer: while the test
     *  is suspended, runTest moves virtual time on by itself and can fire the timer early. */
    private fun awaitAnswerBlocking() {
        val until = System.currentTimeMillis() + 5_000
        while (viewModel.state.value.isSending && System.currentTimeMillis() < until) Thread.sleep(10)
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

    /** A server that's [awake], or asleep until [wake] is called (#280); [log] records the order. */
    private inner class FakeServerWake(var awake: Boolean) : ServerWake {
        override fun isAwake() = awake
        override suspend fun wake() {
            log += "wake"
            wakingSeen = viewModel.state.value.isWaking
            awake = true
        }
    }

    private val log = mutableListOf<String>()
    private var wakingSeen = false

    private fun viewModelWith(wake: ServerWake) {
        viewModel = AskViewModel(AiHelperRepository(store, api), settings, now = { clock }, serverWake = wake)
    }

    @Test
    fun `a sleeping server is woken before the question goes, and the screen says so (#280)`() = runTest {
        viewModelWith(FakeServerWake(awake = false))

        ask("How should I pray?")

        assertEquals(listOf("wake"), log)
        assertTrue(wakingSeen)
        assertFalse(viewModel.state.value.isWaking)
        // Saved before waking, so a question waiting for the server isn't lost; sent after it.
        assertEquals(listOf(AiRole.USER, AiRole.ASSISTANT), store.messages.map { it.role })
        assertEquals("/api/v1/ai/ask", sent.single().first)
    }

    @Test
    fun `an awake server isn't woken (#280)`() = runTest {
        viewModelWith(FakeServerWake(awake = true))

        ask("How should I pray?")

        assertTrue(log.isEmpty())
        assertEquals(1, sent.size)
    }

    @Test
    fun `retry wakes a server that fell asleep (#280)`() = runTest {
        val wake = FakeServerWake(awake = true)
        viewModelWith(wake)
        status = HttpStatusCode.ServiceUnavailable
        body = """{"code":"AI_UNAVAILABLE","message":"…"}"""
        ask("Who was Ruth?")
        wake.awake = false
        status = HttpStatusCode.OK
        body = """{"answer":"Grace [[EPH 2:8]]","crisis":false,"remainingToday":3,"promptVersion":"1"}"""

        viewModel.retry("en")
        awaitAnswer()

        assertEquals(listOf("wake"), log)
        assertNull(viewModel.state.value.failure)
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

    private val day = 24 * 60 * 60 * 1000L

    private fun hitDailyLimit() {
        status = HttpStatusCode.TooManyRequests
        body = """{"code":"AI_DAILY_LIMIT","message":"x"}"""
    }

    /** The app restarting: a new view model over the same phone storage. */
    private fun restart() {
        viewModel = AskViewModel(AiHelperRepository(store, api), settings, now = { clock })
    }

    @Test
    fun `a reopened conversation still says the daily limit, not couldn't answer (#271)`() = runTest {
        hitDailyLimit()
        ask("What is the Trinity?")
        viewModel.newConversation()

        viewModel.openConversation(1L)

        assertEquals(AiAskResult.DailyLimit(day), viewModel.state.value.failure)
    }

    @Test
    fun `after a restart the conversation and the tab still know the limit (#271)`() = runTest {
        hitDailyLimit()
        ask("What is the Trinity?")

        restart()
        assertEquals(AiAskResult.DailyLimit(day), viewModel.state.value.limit)
        viewModel.openConversation(1L)
        assertEquals(AiAskResult.DailyLimit(day), viewModel.state.value.failure)
    }

    @Test
    fun `while the limit lasts a new conversation can't send, and the draft stays (#271)`() = runTest {
        hitDailyLimit()
        ask("What is the Trinity?")
        viewModel.newConversation()
        assertEquals(AiAskResult.DailyLimit(day), viewModel.state.value.limit)
        val requests = sent.size

        viewModel.onDraftChange("What is grace?")
        viewModel.send("en")

        assertEquals(requests, sent.size)
        assertEquals("What is grace?", viewModel.draft.value)
        assertTrue(viewModel.state.value.messages.isEmpty())
    }

    @Test
    fun `resting is remembered the same way (#271)`() = runTest {
        status = HttpStatusCode.ServiceUnavailable
        body = """{"code":"AI_RESTING","message":"x"}"""
        ask("What is the Trinity?")
        viewModel.newConversation()

        assertEquals(AiAskResult.Resting(day), viewModel.state.value.limit)
        viewModel.openConversation(1L)
        assertEquals(AiAskResult.Resting(day), viewModel.state.value.failure)
    }

    @Test
    fun `the stored limit is gone once it resets, and the question offers retry (#271)`() = runTest {
        hitDailyLimit()
        ask("What is the Trinity?")

        clock = day
        restart()

        assertNull(viewModel.state.value.limit)
        assertNull(settings.limit)
        viewModel.openConversation(1L)
        assertEquals(AiAskResult.Unavailable, viewModel.state.value.failure)
    }

    @Test
    fun `the limit clears itself at the reset while the app is open (#271)`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        restart()
        hitDailyLimit()
        viewModel.onDraftChange("What is the Trinity?")
        viewModel.send("en")
        awaitAnswerBlocking()
        assertEquals(AiAskResult.DailyLimit(day), viewModel.state.value.limit)
        // The app opened again, and left open past the reset. Its timer starts here, on the test's
        // thread: started on MockEngine's thread, the scheduler missed it now and then (#280).
        viewModel.viewModelScope.cancel()
        restart()
        viewModel.openConversation(1L)
        assertEquals(AiAskResult.DailyLimit(day), viewModel.state.value.limit)
        assertEquals(AiAskResult.DailyLimit(day), viewModel.state.value.failure)

        clock = day
        advanceTimeBy(day + 1)

        val state = viewModel.state.value
        assertNull(state.limit)
        assertNull(settings.limit)
        // The card under the question can be retried now.
        assertEquals(AiAskResult.Unavailable, state.failure)
    }

    @Test
    fun `the questions left survive a restart until the day ends (#271)`() = runTest {
        ask("How can I be saved?")
        assertEquals(RemainingToday(3, day), settings.remainingToday)

        restart()
        assertEquals(3, viewModel.state.value.remainingToday)

        clock = day
        restart()
        assertNull(viewModel.state.value.remainingToday)
    }

    @Test
    fun `signing out forgets the limit (#271)`() = runTest {
        viewModel.onSignInChanged(true)
        hitDailyLimit()
        ask("What is the Trinity?")

        viewModel.onSignInChanged(false)

        assertNull(viewModel.state.value.limit)
        assertNull(settings.limit)
        assertNull(settings.remainingToday)
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
        viewModel.onSignInChanged(true)
        ask("Who was David?")
        viewModel.onDraftChange("Half a question")

        viewModel.onSignInChanged(false)

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

    @Test
    fun `a report carries the answer and the question it answered`() = runTest {
        ask("Who was Moses?")
        body = """{"answer":"A prophet [[EXO 3:10]]","crisis":false,"remainingToday":2,"promptVersion":"2"}"""
        ask("And Aaron?")
        val answer = viewModel.state.value.messages.last()

        val result = viewModel.report(answer.id, AiReportReason.WRONG_VERSE, "Not this verse", "es")

        assertEquals(AiReportResult.Sent, result)
        val (path, json) = sent.last()
        assertEquals("/api/v1/ai/reports", path)
        assertEquals(
            AiReportRequestDto("And Aaron?", "A prophet [[EXO 3:10]]", AiReportReason.WRONG_VERSE, "Not this verse", "es", "2"),
            HttpClientProvider.json.decodeFromString(AiReportRequestDto.serializer(), json)
        )
    }

    @Test
    fun `a question can't be reported as an answer`() = runTest {
        ask("Who was Moses?")
        val question = viewModel.state.value.messages.first()

        assertEquals(AiAskResult.Unavailable, viewModel.report(question.id, AiReportReason.OTHER, null, "en"))
    }

    private val passage = AiQuestionPassage("John 3:16 (KJV)", AiPassageDto("JHN 3:16", "For God so loved the world"))

    @Test
    fun `ask about this verse opens a new conversation with the passage and an editable question`() = runTest {
        ask("Who was Moses?")

        viewModel.startWithPassage(passage, "What does this passage mean?")

        assertNull(viewModel.state.value.conversationId)
        assertEquals(passage, viewModel.state.value.passage)
        assertEquals("What does this passage mean?", viewModel.draft.value)
    }

    @Test
    fun `the passage goes with the question and its follow-ups, until it's removed`() = runTest {
        viewModel.startWithPassage(passage, "What does this passage mean?")
        viewModel.send("en")
        viewModel.state.first { !it.isSending }
        ask("And verse 17?")
        viewModel.removePassage()
        ask("Who wrote John?")

        val passages = sent.filter { it.first.endsWith("/ai/ask") }
            .map { HttpClientProvider.json.decodeFromString(AiAskRequestDto.serializer(), it.second).passage }
        assertEquals(listOf(passage.dto, passage.dto, null), passages)
    }

    @Test
    fun `a new conversation leaves the passage behind`() = runTest {
        viewModel.startWithPassage(passage, "What does this passage mean?")

        viewModel.newConversation()

        assertNull(viewModel.state.value.passage)
    }

    @Test
    fun `leaving the passage behind clears its untouched question`() = runTest {
        viewModel.startWithPassage(passage, "What does this passage mean?")
        viewModel.newConversation()
        assertEquals("", viewModel.draft.value)

        ask("Who was Moses?")
        val id = viewModel.state.value.conversationId!!
        viewModel.startWithPassage(passage, "What does this passage mean?")
        viewModel.openConversation(id)
        assertEquals("", viewModel.draft.value)
    }

    @Test
    fun `leaving the passage behind keeps a question the person wrote`() = runTest {
        viewModel.startWithPassage(passage, "What does this passage mean?")
        viewModel.onDraftChange("Who is speaking here?")

        viewModel.newConversation()

        assertEquals("Who is speaking here?", viewModel.draft.value)
    }

    @Test
    fun `a passage attached while signed out waits for the sign-in`() = runTest {
        viewModel.startWithPassage(passage, "What does this passage mean?")

        viewModel.onSignInChanged(false)
        viewModel.onSignInChanged(true)

        assertEquals(passage, viewModel.state.value.passage)
    }

    private fun sentPassages() = sent.filter { it.first.endsWith("/ai/ask") }
        .map { HttpClientProvider.json.decodeFromString(AiAskRequestDto.serializer(), it.second).passage }

    @Test
    fun `reopening a conversation asked about a passage shows it and keeps sending it (#281)`() = runTest {
        viewModel.startWithPassage(passage, "What does this passage mean?")
        ask("What does this passage mean?")
        viewModel.newConversation()
        assertNull(viewModel.state.value.passage)

        viewModel.openConversation(1L)

        assertEquals(passage, viewModel.state.value.passage)
        assertEquals(passage, viewModel.state.value.messages.first().passage)
        ask("Who said it?")
        assertEquals(listOf(passage.dto, passage.dto), sentPassages())
    }

    @Test
    fun `a reopened question asked about a passage is retried with it (#281)`() = runTest {
        status = HttpStatusCode.ServiceUnavailable
        body = """{"code":"AI_UNAVAILABLE","message":"…"}"""
        viewModel.startWithPassage(passage, "What does this passage mean?")
        ask("What does this passage mean?")
        viewModel.newConversation()
        viewModel.openConversation(1L)
        status = HttpStatusCode.OK
        body = """{"answer":"Grace [[EPH 2:8]]","crisis":false,"remainingToday":3,"promptVersion":"1"}"""

        viewModel.retry("en")
        awaitAnswer()

        assertEquals(listOf(passage.dto, passage.dto), sentPassages())
    }

    @Test
    fun `a passage removed partway stays removed, and earlier questions keep theirs (#281)`() = runTest {
        viewModel.startWithPassage(passage, "What does this passage mean?")
        ask("What does this passage mean?")
        viewModel.removePassage()
        ask("And what about grace?")
        viewModel.newConversation()

        viewModel.openConversation(1L)

        assertNull(viewModel.state.value.passage)
        val questions = viewModel.state.value.messages.filter { it.role == AiRole.USER }
        assertEquals(listOf(passage, null), questions.map { it.passage })
    }

    /** What the chat scrolls to as the Ask screen shows [viewModel]'s state now (null: stays put). */
    private fun autoScroll() = viewModel.chatScroll.autoScrollTarget(viewModel.state.value, waitingLong = false)

    @Test
    fun `coming back from a cited verse leaves the chat where it was (#278)`() = runTest {
        ask("Who was Moses?")
        ask("And Aaron?")
        assertEquals(2, autoScroll()) // the last answer, from its question
        viewModel.chatScroll.savePosition(item = 1, offset = 240) // the person scrolled up to read

        // The reader opens; tapping the Ask tab brings a new Ask screen with the same state.
        assertNull(autoScroll())
        assertEquals(1, viewModel.chatScroll.firstVisibleItem)
        assertEquals(240, viewModel.chatScroll.firstVisibleItemOffset)
    }

    @Test
    fun `an answer that came while reading shows from its question on the way back (#278)`() = runTest {
        ask("Who was Ruth?")
        autoScroll()

        ask("And Boaz?") // answered while the reader was open

        assertEquals(2, autoScroll())
    }

    @Test
    fun `another conversation or a new one doesn't keep the old place (#278)`() = runTest {
        ask("Who was David?")
        ask("And Jonathan?")
        autoScroll()
        viewModel.newConversation()
        assertEquals(0, autoScroll())
        ask("Who was Esther?")
        autoScroll()

        viewModel.openConversation(1L)

        assertEquals(2, autoScroll())
    }
}
