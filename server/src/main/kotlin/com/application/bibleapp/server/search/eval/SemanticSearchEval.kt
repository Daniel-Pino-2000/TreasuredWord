package com.application.bibleapp.server.search.eval

import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.search.EmbeddingKind
import com.application.bibleapp.server.search.EmbeddingProvider
import com.application.bibleapp.server.search.Embeddings
import com.application.bibleapp.server.search.WorkersAiEmbeddingProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sqrt
import kotlin.system.exitProcess

/** How many results a query is judged on: a search page's worth (#287). */
const val TOP_K = 10

/** A verse and how close it is to the query (cosine similarity, 1 = same direction). */
data class Scored(val ref: VerseRef, val score: Float)

/**
 * Every verse's vector, scaled to length 1 and packed into one array, so a query is compared with
 * all 31,102 in a few milliseconds by plain dot products (#287). Phase 3 does this in Postgres
 * with pgvector instead; here it only has to be right.
 */
class VectorIndex(vectors: Map<VerseRef, FloatArray>) {
    val refs: List<VerseRef> = vectors.keys.sorted()
    val dimensions: Int = vectors.values.firstOrNull()?.size ?: 0
    private val data = FloatArray(refs.size * dimensions)

    init {
        refs.forEachIndexed { row, ref ->
            val vector = normalized(vectors.getValue(ref))
            require(vector.size == dimensions) { "$ref has ${vector.size} numbers, not $dimensions" }
            vector.copyInto(data, row * dimensions)
        }
    }

    val size: Int get() = refs.size

    /** Every verse's similarity to [query], in [refs] order. */
    fun scores(query: FloatArray): FloatArray {
        require(query.size == dimensions) { "The query has ${query.size} numbers, the verses $dimensions" }
        val q = normalized(query)
        return FloatArray(refs.size) { row ->
            var sum = 0f
            val offset = row * dimensions
            for (i in 0 until dimensions) sum += data[offset + i] * q[i]
            sum
        }
    }

    /** The [k] closest verses, closest first; ties go to the earlier verse, so runs repeat exactly. */
    fun top(scores: FloatArray, k: Int = TOP_K): List<Scored> =
        scores.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            .take(k)
            .map { Scored(refs[it], scores[it]) }

    companion object {
        fun normalized(vector: FloatArray): FloatArray {
            var sum = 0.0
            for (x in vector) sum += x.toDouble() * x
            val length = sqrt(sum).toFloat()
            return if (length == 0f) vector.copyOf() else FloatArray(vector.size) { vector[it] / length }
        }
    }
}

/** One query's outcome. [bestRank] is where its highest expected verse came (1 = first), or null if none is in the index. */
data class QueryResult(
    val query: SearchEvalQuery,
    val top: List<Scored>,
    val bestRank: Int?
) {
    val hit: Boolean get() = bestRank != null && bestRank <= TOP_K
    val expectedInTop: Int get() = top.count { it.ref in query.expected }
}

/** Ranks every verse for [vector] and notes where the expected ones came. */
fun score(query: SearchEvalQuery, vector: FloatArray, index: VectorIndex): QueryResult {
    val scores = index.scores(vector)
    val bestRank = index.refs.indices
        .filter { index.refs[it] in query.expected }
        .minOfOrNull { row -> 1 + scores.indices.count { other -> scores[other] > scores[row] || (scores[other] == scores[row] && other < row) } }
    return QueryResult(query, index.top(scores), bestRank)
}

/** What embedding the missing verses came to: how far it got, what it cost, and why it stopped, if it did. */
data class EmbedProgress(
    val embedded: Int,
    val neurons: Double,
    val neuronsEstimated: Boolean,
    val stoppedAt: VerseRef? = null,
    val stopReason: String? = null,
    val quotaExhausted: Boolean = false
) {
    val finished: Boolean get() = stoppedAt == null
}

/** Pauses before each new try after a passing failure (a dropped connection, a 5xx, "capacity exceeded"). */
val DEFAULT_RETRY_PAUSES_MILLIS = listOf(2_000L, 10_000L, 30_000L)

/**
 * Calls [provider], trying again after a passing failure. The daily limit (QuotaExhausted) and
 * failures that won't pass (a 400) go straight to the caller.
 */
suspend fun embedWithRetries(
    provider: EmbeddingProvider,
    texts: List<String>,
    kind: EmbeddingKind,
    pausesMillis: List<Long> = DEFAULT_RETRY_PAUSES_MILLIS
): Embeddings {
    var attempt = 0
    while (true) {
        try {
            return provider.embed(texts, kind)
        } catch (e: Unavailable) {
            if (!e.transient || attempt >= pausesMillis.size) throw e
            delay(pausesMillis[attempt++])
        }
    }
}

/**
 * Embeds every verse not in [vectors] yet, a batch at a time, saving each batch to [cache] as it
 * arrives (#287). At Cloudflare's daily limit it stops and says at which verse: the next run
 * (after 00:00 UTC) loads the cache and carries on from there.
 */
suspend fun embedMissingVerses(
    verses: List<Verse>,
    vectors: MutableMap<VerseRef, FloatArray>,
    cache: VectorCache,
    provider: EmbeddingProvider,
    retryPausesMillis: List<Long> = DEFAULT_RETRY_PAUSES_MILLIS,
    onBatch: (EmbedProgress) -> Unit = {}
): EmbedProgress {
    var progress = EmbedProgress(embedded = 0, neurons = 0.0, neuronsEstimated = false)
    val missing = verses.filter { it.ref !in vectors && it.text.isNotBlank() }
    for (batch in missing.chunked(provider.maxBatch)) {
        val result = try {
            embedWithRetries(provider, batch.map { it.text }, EmbeddingKind.DOCUMENT, retryPausesMillis)
        } catch (e: QuotaExhausted) {
            return progress.copy(stoppedAt = batch.first().ref, stopReason = "Cloudflare's free daily allowance ran out", quotaExhausted = true)
        } catch (e: Unavailable) {
            return progress.copy(stoppedAt = batch.first().ref, stopReason = "Workers AI kept failing (${e.message})")
        }
        val entries = batch.map { it.ref }.zip(result.vectors)
        cache.append(entries)
        vectors.putAll(entries)
        progress = progress.copy(
            embedded = progress.embedded + batch.size,
            neurons = progress.neurons + result.neurons,
            neuronsEstimated = progress.neuronsEstimated || result.neuronsEstimated
        )
        onBatch(progress)
    }
    return progress
}

/** The queries' vectors, by query id, and what they cost. */
class QueryVectors(val vectors: Map<String, FloatArray>, val neurons: Double, val neuronsEstimated: Boolean)

suspend fun embedQueries(
    queries: List<SearchEvalQuery>,
    provider: EmbeddingProvider,
    retryPausesMillis: List<Long> = DEFAULT_RETRY_PAUSES_MILLIS
): QueryVectors {
    val vectors = mutableMapOf<String, FloatArray>()
    var neurons = 0.0
    var estimated = false
    for (batch in queries.chunked(provider.maxBatch)) {
        val result = embedWithRetries(provider, batch.map { it.query }, EmbeddingKind.QUERY, retryPausesMillis)
        batch.zip(result.vectors).forEach { (query, vector) -> vectors[query.id] = vector }
        neurons += result.neurons
        estimated = estimated || result.neuronsEstimated
    }
    return QueryVectors(vectors, neurons, estimated)
}

/** Mean reciprocal rank: 1 when an expected verse always comes first, 0.1 when always tenth. Counted to rank 10. */
fun meanReciprocalRank(results: List<QueryResult>): Double =
    if (results.isEmpty()) 0.0 else results.sumOf { r -> r.bestRank?.takeIf { it <= TOP_K }?.let { 1.0 / it } ?: 0.0 } / results.size

private fun percent(part: Int, whole: Int) = if (whole == 0) "–" else "${Math.round(100.0 * part / whole)}%"

private fun neuronsText(neurons: Double, estimated: Boolean) =
    "${if (estimated) "~" else ""}${"%,.${if (neurons < 10) 2 else 0}f".format(neurons)}"

/** The report people read: the hit rate overall and per group, a table of every query, then each one's top 10. */
fun report(
    results: List<QueryResult>,
    model: String,
    translation: String,
    date: LocalDate,
    index: VectorIndex,
    verseText: Map<VerseRef, String>,
    bible: EmbedProgress,
    queries: QueryVectors
): String = buildString {
    val hits = results.count { it.hit }
    appendLine("# Search by meaning evaluation, $date")
    appendLine()
    appendLine("- **Model:** `$model`")
    appendLine("- **Translation:** $translation (${"%,d".format(index.size)} verses)")
    appendLine("- **Hit rate:** $hits of ${results.size} queries (${percent(hits, results.size)}) have an expected verse in their top $TOP_K")
    appendLine("- **Mean reciprocal rank:** ${"%.2f".format(meanReciprocalRank(results))} (1.00 = an expected verse first every time)")
    val estimated = bible.neuronsEstimated || queries.neuronsEstimated
    appendLine(
        "- **Neurons this run:** ${neuronsText(bible.neurons, bible.neuronsEstimated)} to embed ${"%,d".format(bible.embedded)} verses" +
            " (the rest came from the cache) + ${neuronsText(queries.neurons, queries.neuronsEstimated)} for ${results.size} queries," +
            " of the free 10,000 a day" + if (estimated) "; ~ means estimated from the text length, and Cloudflare's dashboard has the real count" else ""
    )
    appendLine()
    appendLine("| Group | Hits | Mean reciprocal rank |")
    appendLine("|---|---|---|")
    for ((group, inGroup) in results.groupBy { it.query.group }) {
        val groupHits = inGroup.count { it.hit }
        appendLine("| $group | $groupHits of ${inGroup.size} (${percent(groupHits, inGroup.size)}) | ${"%.2f".format(meanReciprocalRank(inGroup))} |")
    }
    appendLine()
    appendLine("| Query | Language | Hit | Best expected rank | Expected in top $TOP_K | Top 3 |")
    appendLine("|---|---|---|---|---|---|")
    for (result in results) {
        val top3 = result.top.take(3).joinToString(", ") { if (it.ref in result.query.expected) "**${it.ref}**" else it.ref.toString() }
        appendLine(
            "| ${result.query.id} | ${result.query.language} | ${if (result.hit) "yes" else "**no**"} | ${result.bestRank ?: "–"} " +
                "| ${result.expectedInTop} of ${result.query.expected.size} | $top3 |"
        )
    }
    for (result in results) {
        appendLine()
        appendLine("## ${result.query.id}")
        appendLine()
        appendLine("- **Query:** ${result.query.query}")
        appendLine("- **Language:** ${result.query.language}")
        appendLine("- **Expect:** ${result.query.expected.sorted().joinToString(", ")}")
        appendLine("- **Best expected rank:** ${result.bestRank ?: "none of them is in this translation"}")
        appendLine()
        result.top.forEachIndexed { i, scored ->
            val mark = if (scored.ref in result.query.expected) " ✓" else ""
            val text = verseText[scored.ref].orEmpty().let { if (it.length > 120) it.take(117) + "…" else it }
            appendLine("${i + 1}. **${scored.ref}**$mark (${"%.3f".format(scored.score)}) $text")
        }
    }
}

/**
 * `./gradlew :server:semanticSearchEval` (#287, phase 1). Arguments, all from build.gradle.kts:
 * the queries file, the report folder, the app's KJV database, the embedding model, a
 * comma-separated list of query IDs (empty: all), and a TSV translation to use instead of the KJV
 * (empty: KJV). Reads AI_BASE_URL and AI_API_TOKEN like the Bible Helper. Never part of
 * `:server:test` or CI: the first run per model and translation spends ~1,200 real free-plan
 * neurons, later ones read the vectors from the cache.
 *
 * Prints only counts, references, neurons and file paths, never the token. Query text is only in
 * the report file, which stays on this computer.
 */
fun main(args: Array<String>) {
    require(args.size == 6) { "Usage: SemanticSearchEval <queries.md> <report folder> <bible_default.db> <model> <ID,ID,…> <translation.tsv>" }
    val (queriesPath, reportPath, kjvPath, model, onlyArg) = args
    val biblePath = args[5]
    val baseUrl = System.getenv("AI_BASE_URL")?.takeIf { it.isNotBlank() }
    val token = System.getenv("AI_API_TOKEN")?.takeIf { it.isNotBlank() }
    if (baseUrl == null || token == null) {
        System.err.println("Set AI_BASE_URL and AI_API_TOKEN first (docs/semantic_search_eval.md, \"Running it\").")
        exitProcess(1)
    }
    val provider = WorkersAiEmbeddingProvider(WorkersAiEmbeddingProvider.accountAiUrl(baseUrl), token, model)

    val all = SearchEvalQueries.parse(File(queriesPath).readText())
    val only = onlyArg.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val unknown = only - all.map { it.id }.toSet()
    require(unknown.isEmpty()) { "No such queries: $unknown" }
    val queries = if (only.isEmpty()) all else all.filter { it.id in only }

    val translation: String
    val verses: List<Verse>
    if (biblePath.isBlank()) {
        translation = "kjv"
        verses = BibleText.readKjv(File(kjvPath))
    } else {
        translation = VectorCache.slug(File(biblePath).nameWithoutExtension)
        verses = BibleText.readTsv(File(biblePath))
    }
    val verseText = verses.associate { it.ref to it.text }
    val absent = queries.flatMap { it.expected }.filter { it !in verseText }.distinct()
    if (absent.isNotEmpty()) println("Not in $translation, so they can't be found: ${absent.sorted().joinToString(", ")}")

    val reportFolder = File(reportPath)
    val cache = VectorCache(File(reportFolder, "cache/${VectorCache.fileName(model, translation)}"), model, translation)
    val vectors = cache.load()
    val toEmbed = verses.count { it.ref !in vectors && it.text.isNotBlank() }
    println("$model, $translation: ${"%,d".format(vectors.size)} of ${"%,d".format(verses.size)} verses already embedded (${cache.file.path}).")

    val bible = runBlocking {
        if (toEmbed == 0) {
            EmbedProgress(0, 0.0, false)
        } else {
            println("Embedding ${"%,d".format(toEmbed)} verses, about ${"%,.0f".format(WorkersAiEmbeddingProvider.neurons(WorkersAiEmbeddingProvider.estimateTokens(verses.filter { it.ref !in vectors }.map { it.text })))} neurons...")
            var batches = 0
            embedMissingVerses(verses, vectors, cache, provider) { progress ->
                if (++batches % 50 == 0) println("  ${"%,d".format(progress.embedded)} of ${"%,d".format(toEmbed)} (${neuronsText(progress.neurons, progress.neuronsEstimated)} neurons)")
            }
        }
    }
    if (!bible.finished) {
        println(
            "Stopped at ${bible.stoppedAt}: ${bible.stopReason}. Embedded ${"%,d".format(bible.embedded)} verses this run " +
                "(${neuronsText(bible.neurons, bible.neuronsEstimated)} neurons); ${"%,d".format(vectors.size)} of ${"%,d".format(verses.size)} are saved in ${cache.file.path}."
        )
        println(
            if (bible.quotaExhausted) "Run the same command after 00:00 UTC to carry on from ${bible.stoppedAt}."
            else "Run the same command again to carry on from ${bible.stoppedAt}."
        )
        exitProcess(if (bible.quotaExhausted) 0 else 1)
    }

    val queryVectors = try {
        runBlocking { embedQueries(queries, provider) }
    } catch (e: QuotaExhausted) {
        println("Every verse is embedded and saved, but the allowance ran out before the queries. Run again after 00:00 UTC.")
        exitProcess(0)
    } catch (e: Unavailable) {
        println("Every verse is embedded and saved, but Workers AI kept failing on the queries (${e.message}). Run again.")
        exitProcess(1)
    }

    val index = VectorIndex(vectors.filterKeys { it in verseText })
    val results = queries.map { score(it, queryVectors.vectors.getValue(it.id), index) }
    val date = LocalDate.now(ZoneOffset.UTC)
    val name = "$date-${VectorCache.slug(model.substringAfterLast('/'))}-$translation${if (only.isEmpty()) "" else "-partial"}.md"
    val file = File(reportFolder.apply { mkdirs() }, name)
    file.writeText(report(results, model, translation, date, index, verseText, bible, queryVectors))

    val hits = results.count { it.hit }
    println("Hit rate: $hits of ${results.size} (${percent(hits, results.size)}); missed: ${results.filterNot { it.hit }.joinToString(", ") { it.query.id }.ifEmpty { "none" }}")
    println("Report: ${file.absolutePath}")
    exitProcess(0)
}
