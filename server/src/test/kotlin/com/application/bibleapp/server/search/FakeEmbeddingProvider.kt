package com.application.bibleapp.server.search

import com.application.bibleapp.server.ai.AiProviderException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Stands in for Workers AI in the tests (#287): turns each text into a small bag-of-words vector,
 * so texts sharing words are close, and records what it was asked, so no test touches the network
 * or spends neurons. [failures] are thrown, one per call, before it answers normally again.
 */
class FakeEmbeddingProvider(
    override val maxBatch: Int = 4,
    private val dimensions: Int = 64
) : EmbeddingProvider {
    class Call(val texts: List<String>, val kind: EmbeddingKind)

    override val model = "@cf/test/fake-embedding"
    val calls = CopyOnWriteArrayList<Call>()
    val failures = ArrayDeque<AiProviderException>()
    /** Fails every call from this many successful ones on, like a day's neurons running out. */
    var quotaAfterCalls: Int? = null
    private var answered = 0

    override suspend fun embed(texts: List<String>, kind: EmbeddingKind): Embeddings {
        require(texts.size <= maxBatch)
        calls += Call(texts, kind)
        failures.removeFirstOrNull()?.let { throw it }
        quotaAfterCalls?.let { if (answered >= it) throw com.application.bibleapp.server.ai.QuotaExhausted("used up") }
        answered++
        return Embeddings(texts.map(::vector), neurons = texts.size * 0.5, neuronsEstimated = true)
    }

    fun vector(text: String): FloatArray = FloatArray(dimensions).also { vector ->
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.forEach { word ->
            vector[abs(word.hashCode()) % dimensions] += 1f
        }
    }
}
