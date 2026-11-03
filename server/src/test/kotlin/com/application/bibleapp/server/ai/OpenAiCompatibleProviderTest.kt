package com.application.bibleapp.server.ai

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [OpenAiCompatibleProvider] against Ktor's MockEngine (#123): the real request and response
 * handling, with answers shaped like the ones Cloudflare returned in #122, and no network or quota.
 */
class OpenAiCompatibleProviderTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun provider(
        disableThinking: Boolean = true,
        timeoutMillis: Long = 5_000,
        reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ) = OpenAiCompatibleProvider(
        baseUrl = "https://ai.example.test/accounts/test-account/ai/v1/",
        apiToken = TOKEN,
        model = "@cf/google/gemma-4-26b-a4b-it",
        disableThinking = disableThinking,
        engine = MockEngine { request -> requests += request; reply(request) },
        timeoutMillis = timeoutMillis
    )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private val conversation = listOf(
        AiMessage(AiRole.SYSTEM, "You are the Bible Helper."),
        AiMessage(AiRole.USER, QUESTION)
    )

    private suspend fun sentBody(): JsonObject =
        Json.parseToJsonElement(requests.single().body.toByteArray().decodeToString()).jsonObject

    // Captures what the provider logs, to check that no message text or token is ever in it.
    private val logger = LoggerFactory.getLogger(OpenAiCompatibleProvider::class.java) as Logger
    private val logged = ListAppender<ILoggingEvent>().apply { start(); logger.addAppender(this) }
    private val logText get() = logged.list.joinToString("\n") { it.formattedMessage }

    @AfterTest
    fun stopCapturingLogs() = logger.detachAppender(logged).let { }

    @Test
    fun returnsTheAnswerAndSendsTheExpectedRequest() = runBlocking {
        val completion = provider { json(answer(content = "Grace is God's undeserved favor. [[EPH 2:8-9]]")) }
            .complete(conversation, maxTokens = 700)

        assertEquals("Grace is God's undeserved favor. [[EPH 2:8-9]]", completion.text)
        assertFalse(completion.cutOff)
        assertEquals(31.4, completion.neurons)

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        // The trailing slash on the base URL doesn't double up.
        assertEquals("https://ai.example.test/accounts/test-account/ai/v1/chat/completions", request.url.toString())
        assertEquals("Bearer $TOKEN", request.headers[HttpHeaders.Authorization])

        val body = sentBody()
        assertEquals("@cf/google/gemma-4-26b-a4b-it", body["model"]!!.jsonPrimitive.content)
        assertEquals(700, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(0.3, body["temperature"]!!.jsonPrimitive.double)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(QUESTION, messages[1]["content"]!!.jsonPrimitive.content)
        // Thinking off, or Gemma 4 spends about twice the neurons and sometimes never answers (#122).
        assertFalse(body["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun withThinkingAllowedTheTemplateSettingIsLeftOut() = runBlocking {
        provider(disableThinking = false) { json(answer(content = "Yes.")) }.complete(conversation, maxTokens = 700)

        assertNull(sentBody()["chat_template_kwargs"])
    }

    @Test
    fun anEmptyContentFallsBackToReasoningContent() = runBlocking {
        // How Qwen3 answers with thinking off (#122).
        val completion = provider {
            json("""{"choices":[{"index":0,"message":{"role":"assistant","content":null,"reasoning_content":"Jesus is Lord."},"finish_reason":"stop"}],"usage":{"neurons":17.0}}""")
        }.complete(conversation, maxTokens = 700)

        assertEquals("Jesus is Lord.", completion.text)
    }

    @Test
    fun runningOutOfTokensMarksTheAnswerCutOff() = runBlocking {
        val completion = provider { json(answer(content = "The first point is", finishReason = "length")) }
            .complete(conversation, maxTokens = 700)

        assertTrue(completion.cutOff)
    }

    @Test
    fun cloudflaresDailyLimitThrowsQuotaExhausted() {
        val provider = provider {
            json(
                """{"errors":[{"message":"AiError: You have used up your daily free allocation of 10,000 neurons, please upgrade to Cloudflare's Workers Paid plan if you would like to continue usage.","code":3036}],"success":false,"result":null,"messages":[]}""",
                HttpStatusCode.TooManyRequests
            )
        }

        assertFailsWith<QuotaExhausted> { runBlocking { provider.complete(conversation, maxTokens = 700) } }
    }

    @Test
    fun aServerErrorThrowsUnavailable() {
        val provider = provider { json("""{"errors":[{"message":"Internal server error","code":7010}],"success":false}""", HttpStatusCode.InternalServerError) }

        assertFailsWith<Unavailable> { runBlocking { provider.complete(conversation, maxTokens = 700) } }
    }

    @Test
    fun aTimeoutThrowsUnavailable() {
        val provider = provider(timeoutMillis = 100) {
            delay(5_000)
            json(answer(content = "Too late."))
        }

        assertFailsWith<Unavailable> { runBlocking { provider.complete(conversation, maxTokens = 700) } }
    }

    @Test
    fun anAnswerInAnUnexpectedShapeThrowsUnavailable() {
        for (body in listOf("""<html>Bad gateway</html>""", """{"choices":[]}""", answer(content = "   "))) {
            val provider = provider { json(body) }
            assertFailsWith<Unavailable>(body) { runBlocking { provider.complete(conversation, maxTokens = 700) } }
        }
    }

    @Test
    fun neitherTheMessagesNorTheTokenAreLogged() = runBlocking {
        provider { json(answer(content = ANSWER)) }.complete(conversation, maxTokens = 700)
        runCatching { provider { json("""{"errors":[{"message":"bad input: $QUESTION","code":5006}]}""", HttpStatusCode.BadRequest) }.complete(conversation, 700) }

        assertTrue(logged.list.isNotEmpty())
        assertTrue("31.4 neurons" in logText)
        for (secret in listOf(TOKEN, QUESTION, ANSWER, "You are the Bible Helper")) {
            assertFalse(secret in logText, "the log contains \"$secret\"")
        }
    }

    @Test
    fun errorCodesAreReadFromBothErrorShapes() {
        assertEquals(listOf(3036), OpenAiCompatibleProvider.errorCodes("""{"errors":[{"message":"…","code":3036}]}"""))
        assertEquals(listOf(429), OpenAiCompatibleProvider.errorCodes("""{"error":{"message":"…","code":"429"}}"""))
        assertEquals(emptyList(), OpenAiCompatibleProvider.errorCodes("not json"))
    }

    @Test
    fun withoutEverySettingThereIsNoProvider() {
        val complete = mapOf(
            "ai.baseUrl" to "https://api.cloudflare.com/client/v4/accounts/test/ai/v1",
            "ai.apiToken" to "token",
            "ai.model" to "@cf/google/gemma-4-26b-a4b-it"
        )
        assertNotNull(OpenAiCompatibleProvider.fromConfig(MapApplicationConfig(*complete.toList().toTypedArray())))
        for (missing in complete.keys) {
            val config = complete + (missing to "")
            assertNull(OpenAiCompatibleProvider.fromConfig(MapApplicationConfig(*config.toList().toTypedArray())), "without $missing")
        }
    }

    private companion object {
        const val TOKEN = "test-token-not-real"
        const val QUESTION = "What does the Bible say about grace?"
        const val ANSWER = "Grace is a gift you can't earn."

        /** An answer shaped like Cloudflare's in #122. */
        fun answer(content: String, finishReason: String = "stop") =
            """{"id":"chatcmpl-1","object":"chat.completion","model":"@cf/google/gemma-4-26b-a4b-it","choices":[{"index":0,"message":{"role":"assistant","content":${JsonPrimitive(content)}},"finish_reason":"$finishReason"}],"usage":{"prompt_tokens":2650,"completion_tokens":260,"total_tokens":2910,"neurons":31.4}}"""
    }
}
