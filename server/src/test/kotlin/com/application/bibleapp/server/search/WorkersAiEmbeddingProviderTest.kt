package com.application.bibleapp.server.search

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.Unavailable
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [WorkersAiEmbeddingProvider] against Ktor's MockEngine (#287): the real request and response handling, no network or neurons. */
class WorkersAiEmbeddingProviderTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun provider(
        model: String = "@cf/baai/bge-m3",
        reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ) = WorkersAiEmbeddingProvider(
        accountAiUrl = WorkersAiEmbeddingProvider.accountAiUrl("https://ai.example.test/accounts/test-account/ai/v1/"),
        apiToken = TOKEN,
        model = model,
        engine = MockEngine { request -> requests += request; reply(request) },
        timeoutMillis = 5_000
    )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private suspend fun sentBody(): JsonObject =
        Json.parseToJsonElement(requests.single().body.toByteArray().decodeToString()).jsonObject

    private val twoVectors = """{"result":{"shape":[2,3],"data":[[0.1,0.2,0.3],[0.4,0.5,0.6]],"pooling":"cls"},"success":true,"errors":[],"messages":[]}"""

    private val logger = LoggerFactory.getLogger(WorkersAiEmbeddingProvider::class.java) as Logger
    private val logged = ListAppender<ILoggingEvent>().apply { start(); logger.addAppender(this) }
    private val logText get() = logged.list.joinToString("\n") { it.formattedMessage }

    @AfterTest
    fun stopCapturingLogs() = logger.detachAppender(logged).let { }

    @Test
    fun bgeM3SendsTextAndReadsTheVectors() = runBlocking<Unit> {
        val provider = provider { json(twoVectors) }

        val result = provider.embed(listOf(VERSE, QUERY), EmbeddingKind.DOCUMENT)

        val request = requests.single()
        assertEquals("https://ai.example.test/accounts/test-account/ai/run/@cf/baai/bge-m3", request.url.toString())
        assertEquals("Bearer $TOKEN", request.headers[HttpHeaders.Authorization])
        assertEquals(listOf(VERSE, QUERY), sentBody().getValue("text").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, result.vectors.size)
        assertContentEquals(floatArrayOf(0.4f, 0.5f, 0.6f), result.vectors[1])
        // No token count in the answer, so the neurons come from the texts' length.
        assertTrue(result.neuronsEstimated)
        assertTrue(result.neurons > 0)
    }

    @Test
    fun qwenTellsQueriesFromDocuments() = runBlocking<Unit> {
        val provider = provider(model = "@cf/qwen/qwen3-embedding-0.6b") { json("""{"result":{"data":[[1,0]],"shape":[1,2]}}""") }

        provider.embed(listOf(QUERY), EmbeddingKind.QUERY)
        assertEquals(setOf("queries"), sentBody().keys)
        requests.clear()
        provider.embed(listOf(VERSE), EmbeddingKind.DOCUMENT)
        assertEquals(setOf("documents"), sentBody().keys)
    }

    @Test
    fun aTokenCountInTheAnswerGivesTheRealNeurons() = runBlocking<Unit> {
        val provider = provider {
            json("""{"result":{"data":[[1,0]],"shape":[1,2],"usage":{"prompt_tokens":1000000}},"success":true}""")
        }

        val result = provider.embed(listOf(VERSE), EmbeddingKind.DOCUMENT)

        assertFalse(result.neuronsEstimated)
        assertEquals(WorkersAiEmbeddingProvider.NEURONS_PER_MILLION_TOKENS, result.neurons)
    }

    @Test
    fun theDailyLimitIsQuotaExhausted() = runBlocking<Unit> {
        val provider = provider {
            json("""{"errors":[{"message":"you have used up your daily free allocation of 10,000 neurons","code":3036}],"success":false}""", HttpStatusCode.TooManyRequests)
        }

        assertFailsWith<QuotaExhausted> { provider.embed(listOf(VERSE), EmbeddingKind.DOCUMENT) }
    }

    @Test
    fun capacityAndServerErrorsArePassingButABadRequestIsNot() = runBlocking<Unit> {
        val busy = provider { json("""{"errors":[{"code":3040}]}""", HttpStatusCode.TooManyRequests) }
        assertTrue(assertFailsWith<Unavailable> { busy.embed(listOf(VERSE), EmbeddingKind.DOCUMENT) }.transient)

        val down = provider { json("{}", HttpStatusCode.BadGateway) }
        assertTrue(assertFailsWith<Unavailable> { down.embed(listOf(VERSE), EmbeddingKind.DOCUMENT) }.transient)

        val bad = provider { json("""{"errors":[{"code":5006,"message":"$VERSE is too long"}]}""", HttpStatusCode.BadRequest) }
        assertFalse(assertFailsWith<Unavailable> { bad.embed(listOf(VERSE), EmbeddingKind.DOCUMENT) }.transient)
    }

    @Test
    fun aWrongNumberOfVectorsIsAnError() = runBlocking<Unit> {
        val provider = provider { json(twoVectors) }

        assertFailsWith<Unavailable> { provider.embed(listOf(VERSE), EmbeddingKind.DOCUMENT) }
    }

    @Test
    fun neverLogsTheTextsOrTheToken() = runBlocking<Unit> {
        provider { json(twoVectors) }.embed(listOf(VERSE, QUERY), EmbeddingKind.DOCUMENT)
        runCatching {
            provider { json("""{"errors":[{"code":5006,"message":"$QUERY"}]}""", HttpStatusCode.BadRequest) }
                .embed(listOf(QUERY), EmbeddingKind.QUERY)
        }

        assertTrue(logText.isNotEmpty())
        assertFalse(VERSE in logText || QUERY in logText || TOKEN in logText, logText)
    }

    @Test
    fun theRunEndpointSitsNextToTheBibleHelpersBaseUrl() {
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/abc/ai",
            WorkersAiEmbeddingProvider.accountAiUrl("https://api.cloudflare.com/client/v4/accounts/abc/ai/v1")
        )
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/abc/ai",
            WorkersAiEmbeddingProvider.accountAiUrl("https://api.cloudflare.com/client/v4/accounts/abc/ai/v1/")
        )
    }

    @Test
    fun theWholeKjvIsEstimatedAtAboutTwelveHundredNeurons() {
        // 4.1 million characters, as in bible_default.db: the issue's ~1,200 neurons.
        val neurons = WorkersAiEmbeddingProvider.neurons(WorkersAiEmbeddingProvider.estimateTokens(listOf("x".repeat(4_109_855))))
        assertTrue(neurons in 1_000.0..1_300.0, "$neurons")
    }

    private companion object {
        const val TOKEN = "secret-test-token"
        const val VERSE = "Be careful for nothing"
        const val QUERY = "something private about my worry"
    }
}
