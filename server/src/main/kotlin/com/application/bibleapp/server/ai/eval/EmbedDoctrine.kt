package com.application.bibleapp.server.ai.eval

import com.application.bibleapp.server.ai.DevAiBudget
import com.application.bibleapp.server.ai.DoctrineRouter
import com.application.bibleapp.server.ai.SystemPrompt
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
 * `./gradlew :server:embedDoctrine` (#297): writes the bge-m3 vector of every chunk of
 * `doctrine.md` (each item, and each of its sentences) to `doctrine_vectors.tsv`, which the server
 * reads instead of embedding them. A chunk already in the file keeps its vector, so only changed
 * items are embedded (all ~80 chunks cost well under a neuron). Needs AI_BASE_URL and AI_API_TOKEN.
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: EmbedDoctrine <doctrine_vectors.tsv>" }
    val config = MapApplicationConfig().apply {
        for ((setting, variable) in listOf("baseUrl" to "AI_BASE_URL", "apiToken" to "AI_API_TOKEN")) {
            System.getenv(variable)?.let { put("ai.$setting", it) }
        }
    }
    val output = File(args[0])
    val model = VerseSearch.MODEL
    val existing = output.takeIf { it.exists() }?.let { TopicVectors.parse(it.readText()) }?.takeIf { it.model == model }
    val known = existing?.phrases.orEmpty().associate { it.text to it.vector }

    // SystemPrompt reads doctrine.md as the server does, comments removed (#297).
    val wanted = DoctrineRouter.wanted(SystemPrompt.doctrine)
    val missing = wanted.map { it.second }.distinct().filter { it !in known }
    val embedded = mutableMapOf<String, FloatArray>()
    if (missing.isNotEmpty()) {
        val live = WorkersAiEmbeddingProvider.fromConfig(config, model)
        if (live == null) {
            System.err.println("${missing.size} doctrine chunks need vectors: set AI_BASE_URL and AI_API_TOKEN first.")
            exitProcess(1)
        }
        // The app shares this Cloudflare account (#343).
        val embeddings = DevAiBudget.fromEnvironment().also { println(it.summary()) }.wrap(live)
        runBlocking {
            for (batch in missing.chunked(embeddings.maxBatch)) {
                embeddings.embed(batch, EmbeddingKind.DOCUMENT).vectors.forEachIndexed { i, vector ->
                    embedded[batch[i]] = TopicRouter.normalized(vector)
                }
            }
        }
    }
    val phrases = wanted.map { (number, text) -> TopicVectors.Phrase(number, text, known[text] ?: embedded.getValue(text)) }
    output.writeText(
        TopicVectors.format(
            model,
            phrases,
            listOf(
                "The bge-m3 vector of every item of doctrine.md and each of its sentences (#297). Made by",
                "./gradlew :server:embedDoctrine; don't edit by hand. Columns: item number, text, vector."
            )
        )
    )
    println("${phrases.size} chunks, ${missing.size} embedded now: ${output.absolutePath}")
    exitProcess(0)
}
