package com.application.bibleapp.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
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
import org.junit.Assert.assertNull
import org.junit.Test
import com.application.bibleapp.utils.VerseReference
import java.io.IOException

/** Every server answer and network failure maps to one [SemanticSearchResult] (#287), on Ktor's MockEngine. */
class SemanticSearchApiTest {

    private val sent = mutableListOf<HttpRequestData>()

    /** What the phone says about its connection; null: the app doesn't know. */
    private var phoneOnline: Boolean? = null

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SemanticSearchApi {
        val client = HttpClient(MockEngine { request ->
            sent += request
            handler(request)
        }) {
            install(ContentNegotiation) { json(HttpClientProvider.json) }
            // As the app's shared client: signed in, the token goes to every backend request…
            install(Auth) {
                backendBearer(backendHost = "backend.test", storedTokens = { BearerTokens("token", "refresh") }, refresh = { null })
            }
        }
        val online = phoneOnline
        return SemanticSearchApi(
            client = { client }, baseUrl = "https://backend.test/api/v1", log = {},
            phoneIsOnline = online?.let { value -> { value } }
        )
    }

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun `results come back as references, best first`() = runTest {
        val result = api {
            json(
                HttpStatusCode.OK,
                """{"results":[{"bookId":40,"chapter":6,"verse":34,"score":0.03},{"bookId":20,"chapter":27,"verse":1,"score":0.02}]}"""
            )
        }.search("worry about tomorrow")

        assertEquals(SemanticSearchResult.Found(listOf(VerseRef(40, 6, 34), VerseRef(20, 27, 1))), result)
    }

    @Test
    fun `a matching topic guide's key passages come with them, the unreadable left out`() = runTest {
        val result = api {
            json(
                HttpStatusCode.OK,
                """{"results":[{"bookId":19,"chapter":42,"verse":5,"score":0.03}],"keyPassages":["MAT 11:28-30","PSA 73","XYZ 1:1","PSA 147:3","MAT 11:28-30"]}"""
            )
        }.search("burned out and exhausted")

        assertEquals(
            SemanticSearchResult.Found(
                listOf(VerseRef(19, 42, 5)),
                listOf(VerseReference(40, 11, 28, 30), VerseReference(19, 73), VerseReference(19, 147, 3))
            ),
            result
        )
    }

    @Test
    fun `the query goes trimmed and cut to the contract's size, without the account's token`() = runTest {
        api { json(HttpStatusCode.OK, """{"results":[]}""") }.search("  " + "a".repeat(250) + "  ")

        val request = sent.single()
        assertEquals("https://backend.test/api/v1/search/semantic", request.url.toString())
        assertEquals("""{"q":"${"a".repeat(SemanticSearchApi.MAX_QUERY_CHARS)}"}""", (request.body as TextContent).text)
        // …except this one: what's searched is never linked to who searched it.
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `out of neurons is unavailable`() = runTest {
        val result = api {
            json(HttpStatusCode.ServiceUnavailable, """{"code":"SEARCH_UNAVAILABLE","message":"Search by meaning isn't available right now"}""")
        }.search("peace")

        assertEquals(SemanticSearchResult.Unavailable, result)
    }

    @Test
    fun `too many searches a minute is unavailable`() = runTest {
        assertEquals(SemanticSearchResult.Unavailable, api { respond("", HttpStatusCode.TooManyRequests) }.search("peace"))
    }

    @Test
    fun `a rejected query is unavailable`() = runTest {
        val result = api { json(HttpStatusCode.BadRequest, """{"code":"VALIDATION_ERROR","message":"…"}""") }.search("peace")

        assertEquals(SemanticSearchResult.Unavailable, result)
    }

    @Test
    fun `no connection is offline`() = runTest {
        assertEquals(SemanticSearchResult.Offline, api { throw IOException("Unable to resolve host") }.search("peace"))
    }

    @Test
    fun `a connection that drops while the phone is online is unavailable, not offline`() = runTest {
        phoneOnline = true

        assertEquals(SemanticSearchResult.Unavailable, api { throw IOException("unexpected end of stream") }.search("peace"))
    }

    @Test
    fun `a server that never answers is unavailable`() = runTest {
        val result = api { throw HttpRequestTimeoutException("https://backend.test/api/v1/search/semantic", 90_000) }.search("peace")

        assertEquals(SemanticSearchResult.Unavailable, result)
    }

    @Test
    fun `an answer that isn't results is unavailable`() = runTest {
        assertEquals(SemanticSearchResult.Unavailable, api { json(HttpStatusCode.OK, "<html>") }.search("peace"))
    }
}
