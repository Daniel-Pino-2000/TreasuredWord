package com.application.bibleapp.server.ai

import com.application.bibleapp.server.search.EmbeddingKind
import com.application.bibleapp.server.search.EmbeddingProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt

/**
 * A hand-checked guide for one topic (`resources/ai/topics.md`, #319): the points a good answer
 * makes and the passages that support them, each with what it supports. An [alone] guide (the
 * crisis guide) is sent by itself whenever it matches, never next to another one.
 */
data class TopicGuide(
    val id: String,
    val title: String,
    val asks: List<String>,
    val points: List<String>,
    val passages: List<Pair<String, String>>,
    val alone: Boolean = false
)

/** A guide a question matched, and how closely (cosine similarity of bge-m3 vectors, 0–1). */
data class RoutedGuide(val guide: TopicGuide, val score: Double)

object TopicGuides {

    private val htmlComment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val passageLine = Regex("""^([1-3]?[A-Z]{2,3} \d+(?::\d+(?:-\d+)?)?): (.+)$""")

    fun load(path: String = "ai/topics.md"): List<TopicGuide> = parse(resourceText(path))

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
                passages = passages,
                alone = field("Alone") == "yes"
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

    internal fun resourceText(path: String): String {
        val stream = TopicGuides::class.java.classLoader.getResourceAsStream(path) ?: error("Missing resource $path")
        return stream.use { it.readBytes().decodeToString() }
    }
}

/**
 * The bge-m3 vector of every "Asks" phrase in `topics.md`, made once at build time by
 * `./gradlew :server:embedTopicGuides` (#319) and kept in `resources/ai/topic_vectors.tsv`, so the
 * server never embeds them and starts without Workers AI. `TopicVectorsTest` fails when
 * `topics.md`'s phrases and the file differ.
 *
 * The file: `#` comment lines, one `# model<TAB><model>` line, then one line per phrase:
 * `<guide id><TAB><phrase><TAB><Base64 of the vector's float32s, little-endian>`. Text, so a
 * changed phrase shows as one changed line in a diff.
 */
object TopicVectors {
    const val PATH = "ai/topic_vectors.tsv"

    /** One phrase's vector, normalized to length 1. */
    class Phrase(val guideId: String, val text: String, val vector: FloatArray)

    class File(val model: String, val phrases: List<Phrase>)

    fun load(path: String = PATH): File = parse(TopicGuides.resourceText(path))

    fun parse(text: String): File {
        var model: String? = null
        val phrases = mutableListOf<Phrase>()
        for (line in text.replace("\r\n", "\n").lines()) {
            when {
                line.isBlank() -> Unit
                line.startsWith("# model\t") -> model = line.substringAfter('\t').trim()
                line.startsWith("#") -> Unit
                else -> {
                    val fields = line.split('\t')
                    require(fields.size == 3) { "Not \"id<TAB>phrase<TAB>vector\": ${line.take(80)}" }
                    phrases += Phrase(fields[0], fields[1], decode(fields[2]))
                }
            }
        }
        return File(requireNotNull(model) { "No \"# model\" line" }, phrases)
    }

    fun format(model: String, phrases: List<Phrase>): String = buildString {
        appendLine("# The bge-m3 vector of every \"Asks\" phrase in topics.md (#319). Made by")
        appendLine("# ./gradlew :server:embedTopicGuides; don't edit by hand.")
        appendLine("# model\t$model")
        for (phrase in phrases) {
            require('\t' !in phrase.text && '\n' !in phrase.text) { "A phrase can't hold a tab or a line break: ${phrase.text}" }
            appendLine("${phrase.guideId}\t${phrase.text}\t${encode(phrase.vector)}")
        }
    }

    fun encode(vector: FloatArray): String {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    fun decode(text: String): FloatArray {
        val buffer = ByteBuffer.wrap(Base64.getDecoder().decode(text)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(buffer.remaining() / 4) { buffer.getFloat() }
    }

    /** The phrases of [guides] that have no vector in [file], and the vectors no phrase uses any more. */
    fun differences(guides: List<TopicGuide>, file: File): Pair<List<String>, List<String>> {
        val wanted = guides.flatMap { guide -> guide.asks.map { "${guide.id}: $it" } }.toSet()
        val stored = file.phrases.map { "${it.guideId}: ${it.text}" }.toSet()
        return (wanted - stored).sorted() to (stored - wanted).sorted()
    }
}

/**
 * Matches a question to guides by meaning (#319): the question's bge-m3 vector against every
 * "Asks" phrase's ([phrases], normalized), and a guide scores its closest phrase. bge-m3 is
 * multilingual, so a Korean question matches an English phrase.
 */
class TopicRouter(
    private val phrases: List<Pair<TopicGuide, FloatArray>>,
    /** A guide is used only at this score or above. */
    private val threshold: Double = THRESHOLD,
    /** A second guide is added when it's above [threshold] and within this of the first. */
    private val secondMargin: Double = SECOND_MARGIN
) {
    /** Every guide with its score for a question's vector, best first. */
    fun scoresFor(vector: FloatArray): List<RoutedGuide> {
        val question = normalized(vector)
        return phrases.groupBy({ it.first }, { dot(it.second, question) })
            .map { (guide, scores) -> RoutedGuide(guide, scores.max()) }
            .sortedByDescending { it.score }
    }

    /**
     * The guides to send with a question: the best at [threshold] or above, and a close second.
     * When one of those is an [TopicGuide.alone] guide, it goes by itself: in the pilot, "Will God
     * forgive me if I end it?" scored 0.79 on the guide about repeated sin and forgiveness and
     * 0.77 on the crisis guide, and both were sent. A crisis guide further behind isn't sent, so
     * "Does God still forgive me?" keeps its own guide.
     */
    fun pick(scored: List<RoutedGuide>): List<RoutedGuide> {
        val first = scored.firstOrNull()?.takeIf { it.score >= threshold } ?: return emptyList()
        val second = scored.getOrNull(1)?.takeIf { it.score >= threshold && first.score - it.score <= secondMargin }
        val picked = listOfNotNull(first, second)
        return picked.firstOrNull { it.guide.alone }?.let { listOf(it) } ?: picked
    }

    /** [pick] on [scoresFor]. */
    fun route(vector: FloatArray): List<RoutedGuide> = pick(scoresFor(vector))

    companion object {
        /** The pilot's threshold (`docs/ai_quality_audit.md`, section 2): no wrong guide on held-out questions. */
        const val THRESHOLD = 0.70
        const val SECOND_MARGIN = 0.03

        /**
         * The live router: `topics.md` and the vectors made at build time. Fails when a phrase
         * has no vector or the vectors are another model's, so a bad build stops the server from
         * starting instead of routing wrongly.
         */
        fun fromResources(model: String, threshold: Double = THRESHOLD): TopicRouter {
            val guides = TopicGuides.load()
            val file = TopicVectors.load()
            require(file.model == model) { "${TopicVectors.PATH} holds ${file.model} vectors, not $model" }
            val (missing, stale) = TopicVectors.differences(guides, file)
            require(missing.isEmpty() && stale.isEmpty()) {
                "${TopicVectors.PATH} doesn't match topics.md; run ./gradlew :server:embedTopicGuides (missing $missing, stale $stale)"
            }
            val byKey = file.phrases.associateBy { it.guideId to it.text }
            val phrases = guides.flatMap { guide -> guide.asks.map { guide to normalized(byKey.getValue(guide.id to it).vector) } }
            return TopicRouter(phrases, threshold)
        }

        /** A router whose phrases [embeddings] embeds now: for tests, with a fake provider. */
        suspend fun embedding(guides: List<TopicGuide>, embeddings: EmbeddingProvider, threshold: Double = THRESHOLD): TopicRouter {
            val pairs = guides.flatMap { guide -> guide.asks.map { guide to it } }
            val phrases = pairs.chunked(embeddings.maxBatch).flatMap { batch ->
                batch.zip(embeddings.embed(batch.map { it.second }, EmbeddingKind.QUERY).vectors) { (guide, _), vector -> guide to normalized(vector) }
            }
            return TopicRouter(phrases, threshold)
        }

        fun normalized(vector: FloatArray): FloatArray {
            val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
            if (norm == 0f) return vector.copyOf()
            return FloatArray(vector.size) { vector[it] / norm }
        }

        fun dot(a: FloatArray, b: FloatArray): Double {
            require(a.size == b.size) { "Vectors of ${a.size} and ${b.size} dimensions" }
            var sum = 0.0
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }
    }
}
