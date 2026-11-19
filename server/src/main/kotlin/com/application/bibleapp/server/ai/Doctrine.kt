package com.application.bibleapp.server.ai

import com.application.bibleapp.server.search.EmbeddingKind
import com.application.bibleapp.server.search.EmbeddingProvider

/** One numbered belief of `doctrine.md` (#121), with its Markdown exactly as written there. */
data class DoctrineItem(val number: Int, val title: String, val text: String) {

    /**
     * The texts whose vectors stand for this item (#297): the whole item, then each sentence with
     * the item's title in front. A question matches the item by its closest one, so "Can women be
     * pastors?" finds item 6 by its sentence about the office of pastor, even though most of
     * item 6 is about how a church is governed.
     */
    fun chunks(): List<String> {
        val plain = text.replace("**", "").replace(Regex("^\\d+\\.\\s+"), "").replace(Regex("\\s*\\n\\s*(- )?"), " ").trim()
        val body = plain.removePrefix("$title.").trim()
        val sentences = body.split(Regex("(?<=[.!?])\\s+(?=[A-Z])")).map { it.trim() }.filter { it.isNotEmpty() }
        return (listOf(plain) + sentences.map { "$title. $it" }).distinct()
    }
}

/**
 * `doctrine.md` split into its header and numbered items, so a question can be sent only the
 * items that fit it (#297). [text] of every item is the file exactly as before, which is what v5
 * and v16 send.
 */
class Doctrine(val header: String, val items: List<DoctrineItem>) {

    val numbers: List<Int> get() = items.map { it.number }

    /**
     * The summary to send: the whole of it for null, or the header, a line saying only some
     * beliefs are listed, and the items in [selected] in their own order and numbers.
     */
    fun text(selected: Collection<Int>? = null): String {
        if (selected == null) return "$header\n\n" + items.joinToString("\n") { it.text }
        val chosen = items.filter { it.number in selected }
        return "$header $PARTIAL_NOTE\n\n" + chosen.joinToString("\n") { it.text }
    }

    companion object {
        /**
         * Tells the model the list is a selection, so it doesn't read a missing item as the
         * confession having nothing to say.
         */
        const val PARTIAL_NOTE = "Only the beliefs that bear on this question are listed here."

        private val itemStart = Regex("""^(\d+)\. \*\*(.+?)\.\*\*""")

        /** [cleaned] is `doctrine.md` with its comments removed, as [SystemPrompt] reads it. */
        fun parse(cleaned: String): Doctrine {
            val lines = cleaned.replace("\r\n", "\n").trim().lines()
            val first = lines.indexOfFirst { itemStart.containsMatchIn(it) }
            require(first > 0) { "doctrine.md has no numbered items" }
            val header = lines.take(first).joinToString("\n").trim()
            val items = mutableListOf<DoctrineItem>()
            var current = mutableListOf<String>()
            fun close() {
                if (current.isEmpty()) return
                val match = itemStart.find(current.first())!!
                items += DoctrineItem(match.groupValues[1].toInt(), match.groupValues[2], current.joinToString("\n").trimEnd())
                current = mutableListOf()
            }
            for (line in lines.drop(first)) {
                if (itemStart.containsMatchIn(line)) close()
                else require(line.isBlank() || line.startsWith(" ")) { "doctrine.md: a line outside any item: $line" }
                current += line
            }
            close()
            require(items.map { it.number } == (1..items.size).toList()) { "doctrine.md's items aren't numbered 1 to ${items.size}" }
            return Doctrine(header, items)
        }
    }
}

/**
 * Picks the doctrine items a Bible Helper question needs (#297), from the same bge-m3 vector of the
 * question as the topic guides ([TopicGuideMatcher]), so it costs no extra neurons: [ALWAYS] (the
 * Bible, God, humanity and salvation, which nearly every answer rests on), and the [extra] other
 * items whose closest chunk ([DoctrineItem.chunks]) is closest to the question.
 */
class DoctrineRouter(
    private val chunks: List<Pair<Int, FloatArray>>,
    private val extra: Int = EXTRA_ITEMS
) {
    /** Every item outside [ALWAYS] with its score (cosine similarity, 0–1), best first. */
    fun scoresFor(vector: FloatArray): List<Pair<Int, Double>> {
        val question = TopicRouter.normalized(vector)
        return chunks.filter { it.first !in ALWAYS }
            .groupBy({ it.first }, { TopicRouter.dot(it.second, question) })
            .map { (number, scores) -> number to scores.max() }
            .sortedByDescending { it.second }
    }

    /** The item numbers to send with a question whose vector is [vector], in order. */
    fun pick(vector: FloatArray): List<Int> = (ALWAYS + scoresFor(vector).take(extra).map { it.first }).sorted()

    companion object {
        /** Sent with every question: items 1–4, as #297 proposed. */
        val ALWAYS = setOf(1, 2, 3, 4)

        /** #297 proposed 3–5 more; see [ALWAYS]. */
        const val EXTRA_ITEMS = 4

        const val PATH = "ai/doctrine_vectors.tsv"

        /** Each chunk of [doctrine] keyed as `doctrine_vectors.tsv` keys it: the item number and the text. */
        fun wanted(doctrine: Doctrine): List<Pair<String, String>> =
            doctrine.items.flatMap { item -> item.chunks().map { item.number.toString() to it } }

        /** The chunks of [doctrine] with no vector in [file], and the vectors no chunk uses any more. */
        fun differences(doctrine: Doctrine, file: TopicVectors.File): Pair<List<String>, List<String>> {
            val wanted = wanted(doctrine).map { (number, text) -> "$number: $text" }.toSet()
            val stored = file.phrases.map { "${it.guideId}: ${it.text}" }.toSet()
            return (wanted - stored).sorted() to (stored - wanted).sorted()
        }

        /**
         * The live router: [SystemPrompt.doctrine] and the vectors made at build time by
         * `./gradlew :server:embedDoctrine`. Fails when an item changed without new vectors, or
         * they're another model's, so a bad build doesn't start instead of picking wrongly.
         */
        fun fromResources(model: String, doctrine: Doctrine = SystemPrompt.doctrine): DoctrineRouter {
            val file = TopicVectors.load(PATH)
            require(file.model == model) { "$PATH holds ${file.model} vectors, not $model" }
            val (missing, stale) = differences(doctrine, file)
            require(missing.isEmpty() && stale.isEmpty()) {
                "$PATH doesn't match doctrine.md; run ./gradlew :server:embedDoctrine (missing ${missing.size}, stale ${stale.size})"
            }
            return DoctrineRouter(file.phrases.map { it.guideId.toInt() to TopicRouter.normalized(it.vector) })
        }

        /** A router whose chunks [embeddings] embeds now: for tests, with a fake provider. */
        suspend fun embedding(doctrine: Doctrine, embeddings: EmbeddingProvider, extra: Int = EXTRA_ITEMS): DoctrineRouter {
            val pairs = wanted(doctrine)
            val chunks = pairs.chunked(embeddings.maxBatch).flatMap { batch ->
                batch.zip(embeddings.embed(batch.map { it.second }, EmbeddingKind.DOCUMENT).vectors) { (number, _), vector ->
                    number.toInt() to TopicRouter.normalized(vector)
                }
            }
            return DoctrineRouter(chunks, extra)
        }
    }
}
