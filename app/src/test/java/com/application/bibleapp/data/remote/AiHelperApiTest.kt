package com.application.bibleapp.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Every server answer and network failure maps to one [AiAskResult] (#127), on Ktor's MockEngine. */
class AiHelperApiTest {

    private val sent = mutableListOf<HttpRequestData>()

    /** What the phone says about its connection; null: the app doesn't know (#280). */
    private var phoneOnline: Boolean? = null

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): AiHelperApi {
        val client = HttpClient(MockEngine { request ->
            sent += request
            handler(request)
        }) {
            install(ContentNegotiation) { json(HttpClientProvider.json) }
        }
        val online = phoneOnline
        return AiHelperApi(
            client = { client }, baseUrl = "https://backend.test/api/v1", now = { NOW }, log = {},
            phoneIsOnline = online?.let { value -> { value } }
        )
    }

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private suspend fun ask(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        api(handler).ask(QUESTION)

    @Test
    fun `an answer comes back with its crisis flag, count and prompt version`() = runTest {
        val result = ask {
            json(HttpStatusCode.OK, """{"answer":"Yes. [[JHN 3:16]]","crisis":true,"remainingToday":12,"promptVersion":"1"}""")
        }

        assertEquals(AiAskResult.Answer("Yes. [[JHN 3:16]]", crisis = true, remainingToday = 12, promptVersion = "1"), result)
    }

    @Test
    fun `the question goes to the ask endpoint as the contract's JSON`() = runTest {
        ask { json(HttpStatusCode.OK, """{"answer":"…","crisis":false,"remainingToday":14,"promptVersion":"1"}""") }

        val request = sent.single()
        assertEquals("https://backend.test/api/v1/ai/ask", request.url.toString())
        assertEquals(
            """{"messages":[{"role":"user","content":"What does it mean to be born again?"}],"language":"es"}""",
            (request.body as TextContent).text
        )
    }

    @Test
    fun `401 after the token refresh means signed out`() = runTest {
        assertEquals(AiAskResult.SignedOut, ask { respond("", HttpStatusCode.Unauthorized) })
    }

    @Test
    fun `the daily limit resets at the next midnight UTC`() = runTest {
        val result = ask { json(HttpStatusCode.TooManyRequests, """{"code":"AI_DAILY_LIMIT","message":"…"}""") }

        assertEquals(AiAskResult.DailyLimit(NEXT_MIDNIGHT_UTC), result)
    }

    @Test
    fun `429 with no body is the per-minute rate limiter`() = runTest {
        assertEquals(AiAskResult.RateLimited, ask { respond("", HttpStatusCode.TooManyRequests) })
    }

    @Test
    fun `Cloudflare's quota used up means resting until midnight UTC`() = runTest {
        val result = ask { json(HttpStatusCode.ServiceUnavailable, """{"code":"AI_RESTING","message":"…"}""") }

        assertEquals(AiAskResult.Resting(NEXT_MIDNIGHT_UTC), result)
    }

    @Test
    fun `the AI failing is unavailable`() = runTest {
        assertEquals(
            AiAskResult.Unavailable,
            ask { json(HttpStatusCode.ServiceUnavailable, """{"code":"AI_UNAVAILABLE","message":"…"}""") }
        )
        // Render's own 503 while the server restarts has no JSON body.
        assertEquals(AiAskResult.Unavailable, ask { respond("<html>", HttpStatusCode.ServiceUnavailable) })
    }

    @Test
    fun `a validation error or a server error is unavailable`() = runTest {
        assertEquals(
            AiAskResult.Unavailable,
            ask { json(HttpStatusCode.BadRequest, """{"code":"VALIDATION_ERROR","message":"…","fieldErrors":{"language":"…"}}""") }
        )
        assertEquals(AiAskResult.Unavailable, ask { respond("", HttpStatusCode.InternalServerError) })
    }

    @Test
    fun `a 200 that isn't an answer is unavailable`() = runTest {
        assertEquals(AiAskResult.Unavailable, ask { json(HttpStatusCode.OK, """{"unexpected":true}""") })
    }

    @Test
    fun `no connection is offline`() = runTest {
        assertEquals(AiAskResult.Offline, ask { throw IOException("Unable to resolve host") })
    }

    @Test
    fun `no connection while the phone is offline is offline`() = runTest {
        phoneOnline = false
        assertEquals(AiAskResult.Offline, ask { throw IOException("Unable to resolve host") })
    }

    @Test
    fun `a dropped connection while the phone is online is unavailable, not offline (#280)`() = runTest {
        phoneOnline = true
        assertEquals(AiAskResult.Unavailable, ask { throw IOException("unexpected end of stream") })
    }

    @Test
    fun `the server not answering in time is unavailable, not offline`() = runTest {
        assertEquals(
            AiAskResult.Unavailable,
            ask { request -> throw HttpRequestTimeoutException(request.url.toString(), 90_000) }
        )
    }

    @Test
    fun `a report is sent to the reports endpoint`() = runTest {
        val result = api { respond("", HttpStatusCode.NoContent) }.report(
            AiReportRequestDto("Q", "A", AiReportReason.WRONG_VERSE, comment = null, language = "en", promptVersion = "1")
        )

        assertEquals(AiReportResult.Sent, result)
        assertEquals("https://backend.test/api/v1/ai/reports", sent.single().url.toString())
        assertTrue((sent.single().body as TextContent).text.contains(""""reason":"WRONG_VERSE""""))
    }

    @Test
    fun `a report that fails gets the same failures as a question`() = runTest {
        val api = api { respond("", HttpStatusCode.TooManyRequests) }

        assertEquals(AiAskResult.RateLimited, api.report(AiReportRequestDto("Q", "A", AiReportReason.OTHER, language = "en")))
    }

    // ---- Streaming (#285) ----

    private fun MockRequestHandleScope.events(vararg events: Pair<String, String>) = respond(
        events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" },
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "text/event-stream")
    )

    private val partials = mutableListOf<AiPartialAnswer>()

    private suspend fun askStreaming(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        api(handler).askStreaming(QUESTION) { partials += it }

    @Test
    fun `a streamed answer reports each step and ends with the whole answer`() = runTest {
        val result = askStreaming {
            events(
                "text" to """{"text":"Grace is "}""",
                "text" to """{"text":"a gift [[EPH 2:8]]"}""",
                "done" to """{"answer":"Grace is a gift [[EPH 2:8]]","crisis":false,"remainingToday":9,"promptVersion":"13"}"""
            )
        }

        assertEquals("https://backend.test/api/v1/ai/ask/stream", sent.single().url.toString())
        assertEquals(
            listOf(AiPartialAnswer("Grace is ", false), AiPartialAnswer("Grace is a gift [[EPH 2:8]]", false)),
            partials
        )
        assertEquals(AiAskResult.Answer("Grace is a gift [[EPH 2:8]]", crisis = false, remainingToday = 9, promptVersion = "13"), result)
    }

    @Test
    fun `the crisis event comes before the text`() = runTest {
        askStreaming {
            events(
                "crisis" to "{}",
                "text" to """{"text":"You matter."}""",
                "done" to """{"answer":"You matter.","crisis":true,"remainingToday":9,"promptVersion":"13"}"""
            )
        }

        assertEquals(listOf(AiPartialAnswer("", true), AiPartialAnswer("You matter.", true)), partials)
    }

    @Test
    fun `a stream that stops partway is unavailable`() = runTest {
        val result = askStreaming {
            events("text" to """{"text":"Grace is "}""", "error" to """{"code":"AI_UNAVAILABLE","message":"…"}""")
        }

        assertEquals(AiAskResult.Unavailable, result)
    }

    @Test
    fun `the free allowance running out partway is resting`() = runTest {
        val result = askStreaming { events("error" to """{"code":"AI_RESTING","message":"…"}""") }

        assertEquals(AiAskResult.Resting(NEXT_MIDNIGHT_UTC), result)
    }

    @Test
    fun `a stream that closes without its end is unavailable`() = runTest {
        assertEquals(AiAskResult.Unavailable, askStreaming { events("text" to """{"text":"Grace is "}""") })
    }

    @Test
    fun `a failure before the stream is the usual error`() = runTest {
        val result = askStreaming { json(HttpStatusCode.TooManyRequests, """{"code":"AI_DAILY_LIMIT","message":"…"}""") }

        assertEquals(AiAskResult.DailyLimit(NEXT_MIDNIGHT_UTC), result)
        assertTrue(partials.isEmpty())
    }

    @Test
    fun `a server without the stream asks the usual way`() = runTest {
        val result = askStreaming { request ->
            if (request.url.encodedPath.endsWith("/stream")) {
                json(HttpStatusCode.NotFound, "")
            } else {
                json(HttpStatusCode.OK, """{"answer":"Yes.","crisis":false,"remainingToday":3,"promptVersion":"5"}""")
            }
        }

        assertEquals(listOf("/api/v1/ai/ask/stream", "/api/v1/ai/ask"), sent.map { it.url.encodedPath })
        assertEquals(AiAskResult.Answer("Yes.", crisis = false, remainingToday = 3, promptVersion = "5"), result)
    }

    @Test
    fun `an answer sent all at once is read as one`() = runTest {
        val result = askStreaming { json(HttpStatusCode.OK, """{"answer":"Yes.","crisis":false,"remainingToday":3,"promptVersion":"5"}""") }

        assertEquals(AiAskResult.Answer("Yes.", crisis = false, remainingToday = 3, promptVersion = "5"), result)
    }

    private companion object {
        val QUESTION = AiAskRequestDto(listOf(AiMessageDto("user", "What does it mean to be born again?")), "es")

        // 2026-10-30T15:20:00Z, and the following midnight UTC
        const val NOW = 1_793_373_600_000L
        const val NEXT_MIDNIGHT_UTC = 1_793_404_800_000L
    }
}
