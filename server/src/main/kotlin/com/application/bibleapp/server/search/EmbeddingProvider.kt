package com.application.bibleapp.server.search

/**
 * What a text is, for models that embed the two differently (#287). Qwen3's embedding model puts
 * an instruction in front of a query ("retrieve relevant passages…") but not in front of a
 * document; bge-m3 treats both the same.
 */
enum class EmbeddingKind { DOCUMENT, QUERY }

/**
 * One request's vectors, in the order the texts were sent, and the neurons Cloudflare counted
 * against the free daily 10,000. [neuronsEstimated] means the answer didn't say how many tokens it
 * used, so [neurons] comes from the length of the texts instead.
 */
class Embeddings(val vectors: List<FloatArray>, val neurons: Double, val neuronsEstimated: Boolean)

/**
 * Turns texts into vectors, for search by meaning (#287). An interface so the evaluation (and
 * later the loader and the search route) don't care which company answers, and tests use a fake
 * that never touches the network or spends quota, like the Bible Helper's [com.application.bibleapp.server.ai.AiProvider].
 */
interface EmbeddingProvider {
    /** The model's name, like `@cf/baai/bge-m3`; the vector cache is kept per model. */
    val model: String

    /** The most texts one [embed] call takes. */
    val maxBatch: Int

    /**
     * @throws com.application.bibleapp.server.ai.QuotaExhausted when the free daily allowance is
     *   used up, or [com.application.bibleapp.server.ai.Unavailable] for anything else.
     */
    suspend fun embed(texts: List<String>, kind: EmbeddingKind): Embeddings
}
