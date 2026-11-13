package com.application.bibleapp.server.routes

import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.SystemPrompt
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.db.tables.AiDailyUsage
import com.application.bibleapp.server.db.tables.AiStarterAnswers
import com.application.bibleapp.server.models.AiAskRequest
import com.application.bibleapp.server.models.AiAskResponse
import com.application.bibleapp.server.models.AiMessageDto
import com.application.bibleapp.server.models.AiStreamText
import com.application.bibleapp.server.models.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** POST /api/v1/ai/ask/stream (#285): the same question as /ai/ask, answered as it's written. */
class AiStreamRoutesTest {

    private val question = AiAskRequest(listOf(AiMessageDto("user", "What is grace?")), language = "en")

    private suspend fun HttpClient.askStream(user: TestUser?, request: AiAskRequest = question): HttpResponse =
        post("/api/v1/ai/ask/stream") {
            contentType(ContentType.Application.Json)
            user?.let { bearerAuth(it.auth.accessToken) }
            setBody(request)
        }

    private data class Event(val name: String, val data: String)

    private suspend fun HttpResponse.events(): List<Event> =
        bodyAsText().split("\n\n").filter { it.isNotBlank() }.map { block ->
            val lines = block.lines()
            Event(
                name = lines.single { it.startsWith("event: ") }.removePrefix("event: "),
                data = lines.single { it.startsWith("data: ") }.removePrefix("data: ")
            )
        }

    private fun List<Event>.texts() = filter { it.name == "text" }.map { Json.decodeFromString<AiStreamText>(it.data).text }
    private fun List<Event>.done() = Json.decodeFromString<AiAskResponse>(single { it.name == "done" }.data)

    private fun usedToday(user: TestUser, clock: MutableClock): Int? = transaction {
        val today = LocalDate.ofInstant(clock.now, ZoneOffset.UTC)
        AiDailyUsage.selectAll()
            .where { (AiDailyUsage.userId eq UUID.fromString(user.auth.userId)) and (AiDailyUsage.day eq today) }
            .singleOrNull()?.get(AiDailyUsage.count)
    }

    @Test
    fun withoutATokenTheAnswerIs401() = testApplication {
        assertEquals(HttpStatusCode.Unauthorized, startWithFakeAi().askStream(user = null).status)
    }

    @Test
    fun theAnswerComesInPiecesThenWholeAndCountsOnce() = testApplication {
        val ai = FakeAiProvider().apply { pieces = listOf("Grace is ", "God's favor ", "[[EPH 2:8-9]]") }
        val clock = MutableClock()
        val client = startWithFakeAi(ai, clock)
        val user = client.registerTestUser()

        val response = client.askStream(user)

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"]!!.startsWith("text/event-stream"))
        val events = response.events()
        assertEquals(listOf("Grace is ", "God's favor ", "[[EPH 2:8-9]]"), events.texts())
        assertEquals("done", events.last().name)
        val done = events.done()
        assertEquals("Grace is God's favor [[EPH 2:8-9]]", done.answer)
        assertFalse(done.crisis)
        assertEquals(14, done.remainingToday)
        assertEquals(SystemPrompt.PROMPT_VERSION, done.promptVersion)
        assertEquals(1, usedToday(user, clock))
    }

    @Test
    fun aCrisisAnswerSaysSoBeforeItsTextAndNeverShowsTheMarker() = testApplication {
        val ai = FakeAiProvider().apply { pieces = listOf("[[CRI", "SIS]]\n", "You matter to God. ", "[[PSA 34:18]]") }
        val client = startWithFakeAi(ai)
        val user = client.registerTestUser()

        val events = client.askStream(user).events()

        assertEquals("crisis", events.first().name)
        assertEquals("You matter to God. [[PSA 34:18]]", events.texts().joinToString(""))
        assertTrue(events.done().crisis)
        assertEquals("You matter to God. [[PSA 34:18]]", events.done().answer)
    }

    @Test
    fun aFailureBeforeAnyTextIsThePlainErrorAndCountsNothing() = testApplication {
        val ai = FakeAiProvider().apply { pieces = listOf("unused"); failure = QuotaExhausted("used up") }
        val clock = MutableClock()
        val client = startWithFakeAi(ai, clock)
        val user = client.registerTestUser()

        val response = client.askStream(user)

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("AI_RESTING", Json.decodeFromString<ErrorResponse>(response.bodyAsText()).code)
        assertEquals(0, usedToday(user, clock))
    }

    @Test
    fun aStreamThatBreaksPartwayEndsWithAnErrorAndCountsNothing() = testApplication {
        val ai = FakeAiProvider().apply {
            pieces = listOf("Grace is ")
            failureAfterPieces = Unavailable("dropped")
        }
        val clock = MutableClock()
        val client = startWithFakeAi(ai, clock)
        val user = client.registerTestUser()

        val response = client.askStream(user)

        assertEquals(HttpStatusCode.OK, response.status)
        val events = response.events()
        assertEquals(listOf("Grace is "), events.texts())
        assertEquals("error", events.last().name)
        assertEquals("AI_UNAVAILABLE", Json.decodeFromString<ErrorResponse>(events.last().data).code)
        assertTrue(events.none { it.name == "done" })
        assertEquals(0, usedToday(user, clock))
    }

    @Test
    fun theDailyLimitIsThePlainError() = testApplication {
        val ai = FakeAiProvider().apply { pieces = listOf("Unused") }
        val clock = MutableClock()
        val client = startWithFakeAi(ai, clock)
        val user = client.registerTestUser()
        transaction {
            AiDailyUsage.insert {
                it[userId] = UUID.fromString(user.auth.userId)
                it[day] = LocalDate.ofInstant(clock.now, ZoneOffset.UTC)
                it[count] = AI_DAILY_LIMIT
            }
        }

        val response = client.askStream(user)

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("AI_DAILY_LIMIT", Json.decodeFromString<ErrorResponse>(response.bodyAsText()).code)
        assertTrue(ai.calls.isEmpty())
    }

    @Test
    fun aReadyStarterAnswerIsOnlyItsDoneEvent() = testApplication {
        transaction { AiStarterAnswers.deleteAll() }
        val ai = FakeAiProvider().apply { pieces = listOf("Pray ", "to the Father. ", "[[MAT 6:9]]") }
        val clock = MutableClock()
        val client = startWithFakeAi(ai, clock)
        val user = client.registerTestUser()
        val starter = AiAskRequest(listOf(AiMessageDto("user", "How should I pray?")), language = "en", starter = "prayer")

        val fresh = client.askStream(user, starter).events()
        assertEquals(3, fresh.texts().size)
        assertEquals("Pray to the Father. [[MAT 6:9]]", fresh.done().answer)

        val ready = client.askStream(user, starter).events()
        assertEquals(listOf("done"), ready.map { it.name })
        assertEquals("Pray to the Father. [[MAT 6:9]]", ready.done().answer)
        assertEquals(1, ai.calls.size)
        // Neither used a daily question.
        assertEquals(null, usedToday(user, clock))
    }

    @Test
    fun anInvalidQuestionAnswers400() = testApplication {
        val client = startWithFakeAi()
        val user = client.registerTestUser()

        val response = client.askStream(user, AiAskRequest(emptyList(), "en"))

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
