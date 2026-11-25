package com.application.bibleapp.server.search.eval

import com.application.bibleapp.server.ai.Busy
import com.application.bibleapp.server.ai.DevAiBudget
import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.TopicRouter
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.search.EmbeddingKind
import com.application.bibleapp.server.search.EmbeddingProvider
import com.application.bibleapp.server.search.Embeddings
import com.application.bibleapp.server.search.KeyPassages
import com.application.bibleapp.server.search.VerseSearch
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

/** The variant the live endpoint ranks with (vectors + full-text search, RRF; #287, phase 3). */
const val LIVE_VARIANT = "plain + keywords (RRF)"

/** A verse and how close it is to the query (cosine similarity, 1 = same direction). */
data class Scored(val ref: VerseRef, val score: Float)

/**
 * Every verse's vector, scaled to length 1 and packed into one array, so a query is compared with
 * all 31,102 in a few milliseconds by plain dot products (#287). Phase 3 does this in Postgres
 * with pgvector instead; here it only has to be right.
 */
class VectorIndex private constructor(
    val refs: List<VerseRef>,
    val dimensions: Int,
    /** Row r is verse refs[r]'s vector, length 1, at r * dimensions. */
    internal val data: FloatArray
) {
    constructor(vectors: Map<VerseRef, FloatArray>) : this(
        vectors.keys.sorted(),
        vectors.values.firstOrNull()?.size ?: 0,
        pack(vectors)
    )

    val size: Int get() = refs.size

    /** Where each verse's row is, for looking one up by reference. */
    val rows: Map<VerseRef, Int> by lazy { refs.withIndex().associate { (row, ref) -> ref to row } }

    /** A new index over the same verses with every row replaced by [transform]'s (then scaled to length 1). */
    internal fun mapRows(transform: (row: Int, source: FloatArray, out: FloatArray) -> Unit): VectorIndex {
        val out = FloatArray(data.size)
        val buffer = FloatArray(dimensions)
        for (row in refs.indices) {
            buffer.fill(0f)
            transform(row, data, buffer)
            normalized(buffer).copyInto(out, row * dimensions)
        }
        return VectorIndex(refs, dimensions, out)
    }

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
        private fun pack(vectors: Map<VerseRef, FloatArray>): FloatArray {
            val refs = vectors.keys.sorted()
            val dimensions = vectors.values.firstOrNull()?.size ?: 0
            val data = FloatArray(refs.size * dimensions)
            refs.forEachIndexed { row, ref ->
                val vector = normalized(vectors.getValue(ref))
                require(vector.size == dimensions) { "$ref has ${vector.size} numbers, not $dimensions" }
                vector.copyInto(data, row * dimensions)
            }
            return data
        }

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
fun score(query: SearchEvalQuery, vector: FloatArray, index: VectorIndex): QueryResult =
    rank(query, index.scores(vector), index)

/** Like [score], for [scores] some other way (in [index]'s verse order): higher is closer. */
fun rank(query: SearchEvalQuery, scores: FloatArray, index: VectorIndex): QueryResult {
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
        } catch (e: Busy) {
            return progress.copy(stoppedAt = batch.first().ref, stopReason = "Cloudflare's API rate limit was reached; wait 5 minutes")
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
    require(args.size in 6..7) { "Usage: SemanticSearchEval <queries.md> <report folder> <bible_default.db> <model> <ID,ID,…> <translation.tsv> [naves.csv]" }
    val (queriesPath, reportPath, kjvPath, model, onlyArg) = args
    val biblePath = args[5]
    val navesPath = args.getOrNull(6).orEmpty()
    // Only needed when something isn't cached yet: trying variants on cached vectors needs no token.
    val provider by lazy {
        val baseUrl = System.getenv("AI_BASE_URL")?.takeIf { it.isNotBlank() }
        val token = System.getenv("AI_API_TOKEN")?.takeIf { it.isNotBlank() }
        if (baseUrl == null || token == null) {
            System.err.println("Some vectors aren't cached yet: set AI_BASE_URL and AI_API_TOKEN first (docs/semantic_search_eval.md, \"Running it\").")
            exitProcess(1)
        }
        // The app shares this Cloudflare account (#343).
        DevAiBudget.fromEnvironment().also { println(it.summary()) }
            .wrap(WorkersAiEmbeddingProvider(WorkersAiEmbeddingProvider.accountAiUrl(baseUrl), token, model))
    }

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

    val queryCache = QueryVectorCache(File(reportFolder, "cache/${QueryVectorCache.fileName(model)}"), model)
    val cachedQueries = queryCache.load()
    val newQueries = queries.filter { it.query !in cachedQueries }
    val embedded = try {
        if (newQueries.isEmpty()) QueryVectors(emptyMap(), 0.0, false) else runBlocking { embedQueries(newQueries, provider) }
    } catch (e: QuotaExhausted) {
        println("Every verse is embedded and saved, but the allowance ran out before the queries. Run again after 00:00 UTC.")
        exitProcess(0)
    } catch (e: Busy) {
        println("Every verse is embedded and saved, but Cloudflare's API rate limit was reached before the queries. Run again in 5 minutes.")
        exitProcess(1)
    } catch (e: Unavailable) {
        println("Every verse is embedded and saved, but Workers AI kept failing on the queries (${e.message}). Run again.")
        exitProcess(1)
    }
    newQueries.forEach { cachedQueries[it.query] = embedded.vectors.getValue(it.id) }
    if (newQueries.isNotEmpty()) queryCache.save(cachedQueries)
    val queryVectors = QueryVectors(queries.associate { it.id to cachedQueries.getValue(it.query) }, embedded.neurons, embedded.neuronsEstimated)

    val index = VectorIndex(vectors.filterKeys { it in verseText })
    vectors.clear() // ~130 MB that the variants below can use instead
    val topics = navesPath.takeIf { it.isNotBlank() }?.let { loadTopicIndex(File(it), reportFolder, model, index) { provider } }
    val results = queries.map { score(it, queryVectors.vectors.getValue(it.id), index) }
    val date = LocalDate.now(ZoneOffset.UTC)
    // Another queries file (-PqueriesFile, #393) names its reports, so the held-out set's don't overwrite the main set's.
    val set = File(queriesPath).nameWithoutExtension.removePrefix("semantic_search_eval").trim('_', '-')
    val suffix = (if (set.isEmpty()) "" else "-${VectorCache.slug(set)}") + if (only.isEmpty()) "" else "-partial"
    val name = "$date-${VectorCache.slug(model.substringAfterLast('/'))}-$translation$suffix"
    val file = File(reportFolder.apply { mkdirs() }, "$name.md")
    file.writeText(report(results, model, translation, date, index, verseText, bible, queryVectors))

    val hits = results.count { it.hit }
    println("Hit rate: $hits of ${results.size} (${percent(hits, results.size)}); missed: ${results.filterNot { it.hit }.joinToString(", ") { it.query.id }.ifEmpty { "none" }}")
    println("Report: ${file.absolutePath}")

    val variants = runVariants(model, translation, kjvPath, reportFolder, index, verses, queries, queryVectors, topics)
    val variantsFile = File(reportFolder, "$name-variants.md")
    var variantsText = variantsReport(model, translation, date, variants)
    variants.forEach { println("  ${it.results.count { r -> r.hit }} of ${it.results.size}  ${it.name}") }

    // The guides' phrase vectors are bge-m3's, so Key passages can only be scored with that model.
    val live = variants.firstOrNull { it.name == LIVE_VARIANT }
    if (model == VerseSearch.MODEL && live != null) {
        val keyPassages = KeyPassages(TopicRouter.fromResources(model))
        fun withPassages(variant: VariantResult) =
            variant.results.map { KeyPassagesResult(it.query, keyPassages.forQuery(queryVectors.vectors.getValue(it.query.id)), it) }
        val onLive = withPassages(live)
        variantsText += "\n" + keyPassagesReport(onLive)
        println("  ${onLive.count { it.hit }} of ${onLive.size}  Key passages first, then $LIVE_VARIANT (what Search shows)")
        // The same on top of each topical-index variant (#394): what Search would show with it.
        val navesVariants = variants.filter { it.name.startsWith(NAVES_PREFIX) }
        if (navesVariants.isNotEmpty()) {
            variantsText += "\n" + keyPassagesOnTopReport(listOf(live) + navesVariants, ::withPassages)
            navesVariants.forEach { v -> println("  ${withPassages(v).count { it.hit }} of ${v.results.size}  Key passages first, then ${v.name}") }
        }
    }
    if (topics != null) {
        variantsText += "\n" + topicMatchesReport(queries, queryVectors, topics)
        if (model == VerseSearch.MODEL) {
            val ask = askScores(queries, queryVectors, TopicRouter.fromResources(model, threshold = 0.0), topics)
            File(reportFolder, "$name-ask.md").writeText(askSuggestionReport(ask))
            for (m in ASK_MARGINS) {
                println("  Ask offered at margin $m: " + ask.groupBy { it.query.group }.entries.joinToString(", ") { (g, s) -> "$g ${s.count { it.margin > m }}/${s.size}" })
            }
        }
        // The pages people would see at each threshold, to check off-topic matches by eye (#394).
        for (threshold in listOf(0.60f, 0.65f, 0.70f)) {
            val variant = variants.firstOrNull { it.name == "$NAVES_PREFIX ≥$threshold, 3 entries, RRF" } ?: continue
            File(reportFolder, "$name-naves-top-$threshold.md").writeText(topResultsReport(variant, threshold, 3, queryVectors, topics, index, verseText))
        }
    }
    variantsFile.writeText(variantsText)
    println("Variants: ${variantsFile.absolutePath}")
    exitProcess(0)
}

/**
 * Tries the ranking variants of SearchVariants.kt on this run's vectors, plus, when this run is
 * another translation and this model's KJV vectors are cached too, both translations together.
 * Free: nothing is embedded here.
 */
private fun runVariants(
    model: String,
    translation: String,
    kjvPath: String,
    reportFolder: File,
    index: VectorIndex,
    verses: List<Verse>,
    queries: List<SearchEvalQuery>,
    queryVectors: QueryVectors,
    topics: TopicIndex? = null
): List<VariantResult> {
    fun evaluate(variant: Variant) =
        VariantResult(variant.name, queries.map { q -> rank(q, variant.scores(q, queryVectors.vectors.getValue(q.id)), index) })
    val texts = verses.associate { it.ref to it.text }
    val keywords = KeywordIndex(index.refs, index.refs.map { texts.getValue(it) })
    fun hybrid(name: String, vectorScores: (SearchEvalQuery, FloatArray) -> FloatArray, words: KeywordIndex = keywords) =
        Variant(name) { q, v -> reciprocalRankFusion(vectorScores(q, v), words.scores(q.query)) }

    val out = mutableListOf<VariantResult>()
    out += evaluate(Variant("plain cosine ($translation)") { _, v -> index.scores(v) })
    out += evaluate(Variant("keywords only (BM25, $translation)") { q, _ -> keywords.scores(q.query) })
    out += evaluate(hybrid(LIVE_VARIANT, { _, v -> index.scores(v) }))
    if (topics != null) out += navesVariants(topics, index, keywords).map(::evaluate)
    val centred = index.centred()
    out += evaluate(Variant("centred") { _, v -> centred.scores(v) })
    for (weight in listOf(0.3f, 0.5f, 1f)) {
        val blended = index.withNeighbours(weight)
        out += evaluate(Variant("verse + neighbours ×$weight") { _, v -> blended.scores(v) })
        if (weight == 0.5f) {
            out += evaluate(hybrid("verse + neighbours ×0.5, + keywords (RRF)", { _, v -> blended.scores(v) }))
            val blendedCentred = blended.centred()
            out += evaluate(Variant("verse + neighbours ×0.5, centred") { _, v -> blendedCentred.scores(v) })
        }
    }

    val kjvCache = File(reportFolder, "cache/${VectorCache.fileName(model, "kjv")}")
    if (translation != "kjv" && kjvCache.isFile) {
        val kjvTexts = BibleText.readKjv(File(kjvPath)).associate { it.ref to it.text }
        val kjv = VectorIndex(VectorCache(kjvCache, model, "kjv").load())
        val both = KeywordIndex(index.refs, index.refs.map { "${texts.getValue(it)} ${kjvTexts[it].orEmpty()}" })
        out += evaluate(Variant("$translation + kjv vectors (average)") { _, v -> averageScores(v, index, listOf(index, kjv)) })
        val blended = index.withNeighbours(0.5f)
        val kjvBlended = kjv.withNeighbours(0.5f)
        val bothBlended: (SearchEvalQuery, FloatArray) -> FloatArray = { _, v -> averageScores(v, blended, listOf(blended, kjvBlended)) }
        out += evaluate(Variant("$translation + kjv, neighbours ×0.5", bothBlended))
        out += evaluate(hybrid("$translation + kjv, neighbours ×0.5, + keywords of both (RRF)", bothBlended, both))
    }
    return out
}
