package com.application.bibleapp.server.ai

import com.application.bibleapp.server.search.EmbeddingKind
import com.application.bibleapp.server.search.EmbeddingProvider
import kotlin.math.sqrt

/**
 * A hand-checked guide for one topic (`resources/ai/topics.md`, pilot for #287/#297): the points a
 * good answer makes and the passages that support them, each with what it supports.
 */
data class TopicGuide(
    val id: String,
    val title: String,
    val asks: List<String>,
    val points: List<String>,
    val passages: List<Pair<String, String>>
)

/** A guide a question matched, and how closely (cosine similarity of bge-m3 vectors, 0–1). */
data class RoutedGuide(val guide: TopicGuide, val score: Double)

object TopicGuides {

    private val htmlComment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val passageLine = Regex("""^([1-3]?[A-Z]{2,3} \d+(?::\d+(?:-\d+)?)?): (.+)$""")

    fun load(path: String = "ai/topics.md"): List<TopicGuide> {
        val stream = TopicGuides::class.java.classLoader.getResourceAsStream(path) ?: error("Missing resource $path")
        return parse(stream.use { it.readBytes().decodeToString() })
    }

    fun parse(text: String): List<TopicGuide> =
        text.replace("\r\n", "\n").replace(htmlComment, "").split(Regex("(?m)^## ")).drop(1).map { block ->
            val lines = block.lines()
            val id = lines.first().trim()
            fun field(name: String) = lines.firstOrNull { it.startsWith("- **$name:** ") }?.substringAfter(":** ")?.trim()
            fun list(name: String): List<String> {
                val start = lines.indexOfFirst { it.trim() == "- **$name:**" }
                require(start >= 0) { "Topic $id has no $name" }
                return lines.drop(start + 1).takeWhile { it.startsWith("  - ") }.map { it.removePrefix("  - ").trim() }
            }
            val passages = list("Passages").map { line ->
                val match = requireNotNull(passageLine.matchEntire(line)) { "Topic $id: not \"CODE C:V: note\": $line" }
                match.groupValues[1] to match.groupValues[2]
            }
            TopicGuide(
                id = id,
                title = requireNotNull(field("Title")) { "Topic $id has no Title" },
                asks = requireNotNull(field("Asks")) { "Topic $id has no Asks" }.split(" | ").map { it.trim() },
                points = list("Points"),
                passages = passages
            )
        }

    /** The instructions' section for [routed] guides (after v5's instructions), or null for none. */
    fun section(routed: List<RoutedGuide>): String? {
        if (routed.isEmpty()) return null
        val guides = routed.joinToString("\n\n") { (guide, _) ->
            buildString {
                appendLine("## ${guide.title}")
                appendLine("Points:")
                guide.points.forEach { appendLine("- $it") }
                appendLine("Passages:")
                guide.passages.forEach { (reference, note) -> appendLine("- [[$reference]]: $note") }
            }.trimEnd()
        }
        return """
            |# 9. A guide for this question
            |
            |These notes were written and checked for questions like this one. Make each of their
            |points that fits the question, and cite each passage for the point its note gives. Cite
            |other passages only when you are sure of the book, chapter and verse. Every rule above
            |still applies, and the summary of beliefs above comes first if a note seems to differ.
            |Don't mention the notes.
            |
            |$guides
        """.trimMargin()
    }
}

/**
 * Matches a question to [guides] by meaning: each guide's "Asks" phrases and the question are
 * embedded with bge-m3 (multilingual, so a Korean question matches an English phrase), and a
 * guide scores its closest phrase. One embedding per question (~0.01 neurons), after [prepare]
 * embeds the phrases once (~0.5 neurons for ~200 phrases).
 */
class TopicRouter(
    private val guides: List<TopicGuide>,
    private val embeddings: EmbeddingProvider,
    /** A guide is used only at this score or above. */
    private val threshold: Double,
    /** A second guide is added when it's above [threshold] and within this of the first. */
    private val secondMargin: Double = 0.03
) {
    private val phraseVectors = mutableListOf<Pair<TopicGuide, FloatArray>>()

    suspend fun prepare() {
        val phrases = guides.flatMap { guide -> guide.asks.map { guide to it } }
        for (batch in phrases.chunked(embeddings.maxBatch)) {
            val vectors = embeddings.embed(batch.map { it.second }, EmbeddingKind.QUERY).vectors
            batch.zip(vectors).forEach { (pair, vector) -> phraseVectors += pair.first to normalized(vector) }
        }
    }

    /** Every guide with its score for [question], best first. */
    suspend fun scores(question: String): List<RoutedGuide> {
        check(phraseVectors.isNotEmpty()) { "Call prepare() first" }
        val vector = normalized(embeddings.embed(listOf(question), EmbeddingKind.QUERY).vectors.single())
        return scoresFor(vector)
    }

    fun scoresFor(normalizedVector: FloatArray): List<RoutedGuide> =
        phraseVectors.groupBy({ it.first }, { dot(it.second, normalizedVector) })
            .map { (guide, scores) -> RoutedGuide(guide, scores.max()) }
            .sortedByDescending { it.score }

    /** The guides to send with a question: the best above [threshold], and a close second. */
    fun pick(scored: List<RoutedGuide>): List<RoutedGuide> {
        val first = scored.firstOrNull()?.takeIf { it.score >= threshold } ?: return emptyList()
        val second = scored.getOrNull(1)?.takeIf { it.score >= threshold && first.score - it.score <= secondMargin }
        return listOfNotNull(first, second)
    }

    companion object {
        fun normalized(vector: FloatArray): FloatArray {
            val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
            return FloatArray(vector.size) { vector[it] / norm }
        }

        fun dot(a: FloatArray, b: FloatArray): Double {
            var sum = 0.0
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }
    }
}
