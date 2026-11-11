package com.application.bibleapp.server.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Asks an AI through the OpenAI-compatible `POST <baseUrl>/chat/completions` (#123). Cloudflare
 * Workers AI's free plan offers it at `https://api.cloudflare.com/client/v4/accounts/<account
 * id>/ai/v1`; Groq and others use the same shape, so another provider is only another base URL,
 * token and model.
 *
 * [disableThinking] sends `chat_template_kwargs: {enable_thinking: false}`. Gemma 4 and Qwen3
 * think before answering unless told not to, which in #122 nearly doubled the neurons, took
 * 20–40 s, and sometimes used up every token before any answer. A provider that rejects the field
 * can turn it off with AI_DISABLE_THINKING=false.
 *
 * Only the HTTP status, time taken and neurons are logged, never a message or the token (#123).
 * That's also why Ktor's Logging plugin isn't installed: it prints the Authorization header.
 *
 * [engine] is only changed by tests, which pass Ktor's MockEngine.
 */
class OpenAiCompatibleProvider(
    baseUrl: String,
    private val apiToken: String,
    private val model: String,
    private val disableThinking: Boolean = true,
    engine: HttpClientEngine = CIO.create(),
    timeoutMillis: Long = TIMEOUT_MILLIS,
    private val retryPauseMillis: Long = RETRY_PAUSE_MILLIS
) : AiProvider {

    private val url = "${baseUrl.trimEnd('/')}/chat/completions"

    private val client = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = timeoutMillis }
    }

    @Serializable
    private data class WireMessage(val role: String, val content: String)

    @Serializable
    private data class ChatTemplateKwargs(@SerialName("enable_thinking") val enableThinking: Boolean)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<WireMessage>,
        @SerialName("max_tokens") val maxTokens: Int,
        val temperature: Double,
        // Left out of the JSON when null (encodeDefaults is off).
        @SerialName("chat_template_kwargs") val chatTemplateKwargs: ChatTemplateKwargs? = null
    )

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList(), val usage: Usage? = null)

    @Serializable
    private data class Choice(val message: ChoiceMessage? = null, @SerialName("finish_reason") val finishReason: String? = null)

    @Serializable
    private data class ChoiceMessage(
        val content: String? = null,
        // Qwen3 with thinking off answers here and leaves content null (#122).
        @SerialName("reasoning_content") val reasoningContent: String? = null
    )

    @Serializable
    private data class Usage(val neurons: Double? = null)

    override suspend fun complete(messages: List<AiMessage>, maxTokens: Int): AiCompletion {
        val request = ChatRequest(
            model = model,
            messages = messages.map { WireMessage(it.role.wireName, it.content) },
            maxTokens = maxTokens,
            temperature = TEMPERATURE,
            chatTemplateKwargs = if (disableThinking) ChatTemplateKwargs(enableThinking = false) else null
        )
        val started = System.nanoTime()
        return try {
            attempt(request)
        } catch (e: Unavailable) {
            // One more try after a hiccup that failed fast (#280), never after a slow failure:
            // the person is already waiting, and the app gives up after 90 s.
            if (!e.transient || millisSince(started) > RETRY_IF_FAILED_WITHIN_MILLIS) throw e
            delay(retryPauseMillis)
            log.info("AI provider: trying once more")
            attempt(request)
        }
    }

    private suspend fun attempt(request: ChatRequest): AiCompletion {
        val started = System.nanoTime()
        val response = try {
            client.post(url) {
                bearerAuth(apiToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        } catch (e: CancellationException) {
            // The caller gave up (the app disconnected): not the provider's fault.
            throw e
        } catch (e: Exception) {
            // Timeouts, refused connections, DNS. The exception's class says which, without any text of ours.
            log.warn("AI provider unreachable after {} ms: {}", millisSince(started), e::class.simpleName)
            // Waiting the whole timeout again would be too long; a refused or dropped connection isn't.
            throw Unavailable("Couldn't reach the AI provider", e, transient = e !is HttpRequestTimeoutException)
        }
        return read(response, millisSince(started))
    }

    private suspend fun read(response: HttpResponse, millis: Long): AiCompletion {
        val status = response.status
        val body = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("AI provider: HTTP {} in {} ms, body unreadable", status.value, millis)
            throw Unavailable("Couldn't read the AI provider's answer", e)
        }
        if (!status.isSuccess()) {
            // The error body is never logged: an "invalid input" error could repeat part of the question.
            val codes = errorCodes(body)
            log.warn("AI provider: HTTP {} in {} ms, error codes {}", status.value, millis, codes)
            // Only 3036 is the daily allowance. Cloudflare also answers 429 with 3040 ("capacity
            // temporarily exceeded, please try again"), which is over in moments, not at midnight.
            if (QUOTA_EXHAUSTED_CODE in codes) {
                throw QuotaExhausted("The AI's free daily allowance is used up")
            }
            // A 5xx, or a 429 that isn't the daily allowance (3040, "capacity temporarily exceeded").
            val transient = status.value >= 500 || status == HttpStatusCode.TooManyRequests
            throw Unavailable("The AI provider answered HTTP ${status.value}", transient = transient)
        }
        val parsed = try {
            json.decodeFromString(ChatResponse.serializer(), body)
        } catch (e: Exception) {
            log.warn("AI provider: HTTP {} in {} ms, answer not in the expected shape", status.value, millis)
            throw Unavailable("The AI provider's answer wasn't in the expected shape", e)
        }
        val choice = parsed.choices.firstOrNull()
        val finishReason = choice?.finishReason
        val text = choice?.message?.content?.takeIf { it.isNotBlank() }
            // Only a finished reply: a model that thought until it ran out of tokens has its
            // unfinished reasoning there, never meant for the person asking.
            ?: choice?.message?.reasoningContent?.takeIf { it.isNotBlank() && finishReason != "length" }
        val neurons = parsed.usage?.neurons
        log.info("AI provider: HTTP {} in {} ms, {} neurons, finish {}", status.value, millis, neurons, finishReason)
        if (text == null) throw Unavailable("The AI provider returned no answer")
        return AiCompletion(text = text.trim(), cutOff = finishReason == "length", neurons = neurons)
    }

    companion object {
        /** The slowest answers seen on Render took 26 s (#280), too close to the old 30 s. */
        private const val TIMEOUT_MILLIS = 45_000L
        /** Only a failure this quick is tried again, so the worst case stays well under the app's 90 s. */
        private const val RETRY_IF_FAILED_WITHIN_MILLIS = 10_000L
        private const val RETRY_PAUSE_MILLIS = 1_000L
        /**
         * Low, for steady answers: the same question shouldn't get a different doctrine each time.
         * 0.2 since #206: at 0.3, each evaluation run had a different verse or two cited loosely.
         * Changing it changes the answers, so bump SystemPrompt.PROMPT_VERSION and run the evaluation.
         */
        internal const val TEMPERATURE = 0.2
        /** Cloudflare: "you have used up your daily free allocation of 10,000 neurons". */
        private const val QUOTA_EXHAUSTED_CODE = 3036
        private val log = LoggerFactory.getLogger(OpenAiCompatibleProvider::class.java)
        private val json = Json { ignoreUnknownKeys = true }

        private fun millisSince(started: Long) = (System.nanoTime() - started) / 1_000_000

        /**
         * The numeric error codes in an error body: Cloudflare's own `{"errors":[{"code":3036,…}]}`
         * or OpenAI's `{"error":{"code":…}}`. Numbers only, so they're safe to log.
         */
        internal fun errorCodes(body: String): List<Int> =
            Regex("\"code\"\\s*:\\s*\"?(\\d+)").findAll(body).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()

        /**
         * Built from the `ai` block of application.conf, or null when AI_BASE_URL, AI_API_TOKEN or
         * AI_MODEL isn't set (a local server, CI): the AI routes then answer AI_UNAVAILABLE instead
         * of the server failing to start, like email without the GMAIL_* settings.
         */
        fun fromConfig(config: ApplicationConfig): OpenAiCompatibleProvider? {
            fun setting(name: String) = config.propertyOrNull("ai.$name")?.getString()?.takeIf { it.isNotBlank() }
            return OpenAiCompatibleProvider(
                baseUrl = setting("baseUrl") ?: return null,
                apiToken = setting("apiToken") ?: return null,
                model = setting("model") ?: return null,
                disableThinking = setting("disableThinking")?.toBooleanStrictOrNull() ?: true
            )
        }
    }
}
