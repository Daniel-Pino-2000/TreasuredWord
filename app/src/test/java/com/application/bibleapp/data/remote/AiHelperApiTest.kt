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

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): AiHelperApi {
        val client = HttpClient(MockEngine { request ->
            sent += request
            handler(request)
        }) {
            install(ContentNegotiation) { json(HttpClientProvider.json) }
        }
        return AiHelperApi(client = { client }, baseUrl = "https://backend.test/api/v1", now = { NOW }, log = {})
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

    private companion object {
        val QUESTION = AiAskRequestDto(listOf(AiMessageDto("user", "What does it mean to be born again?")), "es")

        // 2026-10-30T15:20:00Z, and the following midnight UTC
        const val NOW = 1_793_373_600_000L
        const val NEXT_MIDNIGHT_UTC = 1_793_404_800_000L
    }
}
