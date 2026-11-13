package com.application.bibleapp.server.search

import com.application.bibleapp.server.ai.OpenAiCompatibleProvider
import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.Unavailable
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import kotlin.math.ceil

/**
 * Embeds texts with Cloudflare Workers AI's own `POST <account>/ai/run/<model>` (#287), on the
 * same account and free 10,000 neurons a day as the Bible Helper.
 *
 * Not the OpenAI-compatible `/v1/embeddings`: Qwen3's embedding model needs to be told which
 * texts are queries (`queries`) and which are documents (`documents`), which that endpoint can't
 * say. bge-m3 takes both as `text`. Both models take at most 32 texts per request here (Qwen3's
 * documented limit; bge-m3 documents none, so it gets the same).
 *
 * Like [OpenAiCompatibleProvider], only the HTTP status, time taken, text count and neurons are
 * logged, never a text or the token.
 *
 * [engine] is only changed by tests, which pass Ktor's MockEngine.
 */
class WorkersAiEmbeddingProvider(
    accountAiUrl: String,
    private val apiToken: String,
    override val model: String,
    engine: HttpClientEngine = CIO.create(),
    timeoutMillis: Long = TIMEOUT_MILLIS
) : EmbeddingProvider {

    private val url = "${accountAiUrl.trimEnd('/')}/run/$model"
    private val isQwen = model.startsWith("@cf/qwen/")

    override val maxBatch: Int = MAX_BATCH

    private val client = HttpClient(engine) {
        install(HttpTimeout) { requestTimeoutMillis = timeoutMillis }
    }

    /** The request body this model expects. */
    internal fun body(texts: List<String>, kind: EmbeddingKind): JsonObject = buildJsonObject {
        val field = when {
            !isQwen -> "text"
            kind == EmbeddingKind.QUERY -> "queries"
            else -> "documents"
        }
        putJsonArray(field) { texts.forEach { add(it) } }
    }

    override suspend fun embed(texts: List<String>, kind: EmbeddingKind): Embeddings {
        require(texts.isNotEmpty() && texts.size <= maxBatch) { "Between 1 and $maxBatch texts per request, not ${texts.size}" }
        val started = System.nanoTime()
        val response = try {
            client.post(url) {
                bearerAuth(apiToken)
                contentType(ContentType.Application.Json)
                setBody(body(texts, kind).toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Embeddings: unreachable after {} ms: {}", millisSince(started), e::class.simpleName)
            throw Unavailable("Couldn't reach Workers AI", e, transient = e !is HttpRequestTimeoutException)
        }
        val millis = millisSince(started)
        val status = response.status
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Embeddings: HTTP {} in {} ms, body unreadable", status.value, millis)
            throw Unavailable("Couldn't read Workers AI's answer", e, transient = true)
        }
        if (!status.isSuccess()) {
            // The body is never logged: an "invalid input" error could repeat a text.
            val codes = OpenAiCompatibleProvider.errorCodes(text)
            log.warn("Embeddings: HTTP {} in {} ms, error codes {}", status.value, millis, codes)
            if (QUOTA_EXHAUSTED_CODE in codes) throw QuotaExhausted("The AI's free daily allowance is used up")
            val transient = status.value >= 500 || status == HttpStatusCode.TooManyRequests
            throw Unavailable("Workers AI answered HTTP ${status.value}", transient = transient)
        }
        val parsed = try {
            parse(text, texts)
        } catch (e: Exception) {
            log.warn("Embeddings: HTTP {} in {} ms, answer not in the expected shape", status.value, millis)
            throw Unavailable("Workers AI's answer wasn't in the expected shape", e)
        }
        log.info("Embeddings: HTTP {} in {} ms, {} texts, {} neurons{}", status.value, millis, texts.size,
            "%.2f".format(parsed.neurons), if (parsed.neuronsEstimated) " (estimated)" else "")
        return parsed
    }

    /**
     * Reads `{"result": {"data": [[…], …], "shape": [n, 1024]}}`. Some Workers AI embedding models
     * answer `response` instead of `data`, so either is accepted. Neurons come from a `usage`
     * token count when there is one, otherwise from the texts' length.
     */
    internal fun parse(body: String, texts: List<String>): Embeddings {
        val root = json.parseToJsonElement(body) as JsonObject
        val result = root["result"] as? JsonObject ?: root
        val rows = (result["data"] ?: result["response"]) as JsonArray
        val vectors = rows.map { row -> row.jsonArray.let { values -> FloatArray(values.size) { values[it].jsonPrimitive.float } } }
        check(vectors.size == texts.size) { "Asked for ${texts.size} vectors, got ${vectors.size}" }
        check(vectors.all { it.size == vectors.first().size && it.isNotEmpty() }) { "Vectors of different sizes" }
        val tokens = (result["usage"] ?: root["usage"])?.let(::tokenCount)
        return Embeddings(
            vectors = vectors,
            neurons = neurons(tokens ?: estimateTokens(texts)),
            neuronsEstimated = tokens == null
        )
    }

    private fun tokenCount(usage: JsonElement): Double? {
        val fields = usage as? JsonObject ?: return null
        return listOf("prompt_tokens", "total_tokens", "input_tokens")
            .firstNotNullOfOrNull { fields[it]?.jsonPrimitive?.doubleOrNull }
    }

    companion object {
        /** Qwen3's documented limit per request; bge-m3 gets the same. */
        const val MAX_BATCH = 32
        /** Embedding requests are quick; a minute covers a cold Cloudflare region. */
        private const val TIMEOUT_MILLIS = 60_000L
        /**
         * Both models cost $0.0118 per million input tokens, which Cloudflare's price of $0.011
         * per 1,000 neurons makes about 1,075 neurons per million tokens (#287's limits table).
         */
        const val NEURONS_PER_MILLION_TOKENS = 1_075.0
        /** Cloudflare: "you have used up your daily free allocation of 10,000 neurons". */
        private const val QUOTA_EXHAUSTED_CODE = 3036
        private val log = LoggerFactory.getLogger(WorkersAiEmbeddingProvider::class.java)
        private val json = Json { ignoreUnknownKeys = true }

        private fun millisSince(started: Long) = (System.nanoTime() - started) / 1_000_000

        fun neurons(tokens: Double) = tokens * NEURONS_PER_MILLION_TOKENS / 1_000_000

        /**
         * About four characters a token for English; the whole KJV (4.1 million characters) comes
         * out at ~1 million tokens, as the issue's estimate says.
         */
        fun estimateTokens(texts: List<String>): Double = texts.sumOf { ceil(it.length / 4.0) }

        /**
         * The Bible Helper's AI_BASE_URL is `…/accounts/<id>/ai/v1` (OpenAI-compatible); the
         * models' own endpoint is next to it at `…/accounts/<id>/ai/run/<model>`.
         */
        fun accountAiUrl(aiBaseUrl: String): String = aiBaseUrl.trimEnd('/').removeSuffix("/v1")
    }
}
