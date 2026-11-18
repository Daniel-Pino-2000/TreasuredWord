package com.application.bibleapp.server.ai.eval

import com.application.bibleapp.server.ai.DevAiBudget
import com.application.bibleapp.server.ai.TopicGuides
import com.application.bibleapp.server.ai.TopicRouter
import com.application.bibleapp.server.ai.TopicVectors
import com.application.bibleapp.server.search.EmbeddingKind
import com.application.bibleapp.server.search.VerseSearch
import com.application.bibleapp.server.search.WorkersAiEmbeddingProvider
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * `./gradlew :server:embedTopicGuides` (#319): writes the bge-m3 vector of every "Asks" phrase in
 * `topics.md` to `topic_vectors.tsv`, which the server reads instead of embedding them. A phrase
 * already in the file keeps its vector, so only new or reworded phrases are embedded (189 phrases
 * cost ~0.01 neurons in all) and an unchanged phrase's line never changes. Needs AI_BASE_URL and
 * AI_API_TOKEN, like the server.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: EmbedTopicGuides <topics.md> <topic_vectors.tsv>" }
    val config = MapApplicationConfig().apply {
        for ((setting, variable) in listOf("baseUrl" to "AI_BASE_URL", "apiToken" to "AI_API_TOKEN")) {
            System.getenv(variable)?.let { put("ai.$setting", it) }
        }
    }
    val guides = TopicGuides.parse(File(args[0]).readText())
    val output = File(args[1])
    val model = VerseSearch.MODEL
    val existing = output.takeIf { it.exists() }?.let { TopicVectors.parse(it.readText()) }?.takeIf { it.model == model }
    val known = existing?.phrases.orEmpty().associate { it.text to it.vector }

    val wanted = guides.flatMap { guide -> guide.asks.map { guide.id to it } }
    val missing = wanted.map { it.second }.distinct().filter { it !in known }
    val embedded = mutableMapOf<String, FloatArray>()
    if (missing.isNotEmpty()) {
        val live = WorkersAiEmbeddingProvider.fromConfig(config, model)
        if (live == null) {
            System.err.println("${missing.size} phrases need vectors: set AI_BASE_URL and AI_API_TOKEN first.")
            exitProcess(1)
        }
        // The app shares this Cloudflare account (#343).
        val embeddings = DevAiBudget.fromEnvironment().also { println(it.summary()) }.wrap(live)
        runBlocking {
            for (batch in missing.chunked(embeddings.maxBatch)) {
                embeddings.embed(batch, EmbeddingKind.QUERY).vectors.forEachIndexed { i, vector ->
                    embedded[batch[i]] = TopicRouter.normalized(vector)
                }
            }
        }
    }
    val phrases = wanted.map { (id, text) -> TopicVectors.Phrase(id, text, known[text] ?: embedded.getValue(text)) }
    output.writeText(TopicVectors.format(model, phrases))
    println("${phrases.size} phrases, ${missing.size} embedded now: ${output.absolutePath}")
    exitProcess(0)
}
