package com.application.bibleapp.server.search.eval

import com.application.bibleapp.server.ai.Busy
import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.search.EmbeddingKind
import com.application.bibleapp.server.search.EmbeddingProvider
import com.application.bibleapp.server.search.WorkersAiEmbeddingProvider
import kotlinx.coroutines.runBlocking
import java.io.File

/*
 * The topical index's side of the evaluation (#394): embedding Nave's entries once, the ranking
 * variants that use them, and what they matched. Run with -Pnaves=<NavesTopicalDictionary.csv>.
 */

/** Every topical-index variant's name starts with this, so the report can pick them out. */
const val NAVES_PREFIX = "Nave's"

/**
 * Nave's entries with their vectors, embedding the ones not cached yet (~29,000 short labels, a
 * few hundred neurons once; later runs read `cache/<model>-naves.queries`). Null, with the reason
 * printed, when the embedding can't finish today: the run goes on without the topical variants.
 */
fun loadTopicIndex(file: File, reportFolder: File, model: String, index: VectorIndex, provider: () -> EmbeddingProvider): TopicIndex? {
    val entries = NavesTopics.load(file)
    val cache = QueryVectorCache(File(reportFolder, "cache/${VectorCache.slug(model.substringAfterLast('/'))}-naves.queries"), model)
    val vectors = cache.load()
    val missing = entries.map { it.text }.distinct().filter { it !in vectors }
    println("Nave's: ${"%,d".format(entries.size)} entries, ${"%,d".format(entries.map { it.text }.distinct().size - missing.size)} already embedded.")
    if (missing.isNotEmpty()) {
        val estimate = WorkersAiEmbeddingProvider.neurons(WorkersAiEmbeddingProvider.estimateTokens(missing))
        println("Embedding ${"%,d".format(missing.size)} Nave's entries, about ${"%,.0f".format(estimate)} neurons...")
        val embeddings = provider()
        var batches = 0
        try {
            runBlocking {
                for (batch in missing.chunked(embeddings.maxBatch)) {
                    val result = embedWithRetries(embeddings, batch, EmbeddingKind.DOCUMENT)
                    batch.zip(result.vectors).forEach { (text, vector) -> vectors[text] = vector }
                    if (++batches % 50 == 0) {
                        cache.save(vectors)
                        println("  ${"%,d".format(batches * embeddings.maxBatch)} of ${"%,d".format(missing.size)}")
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is QuotaExhausted && e !is Busy && e !is Unavailable) throw e
            cache.save(vectors)
            println("Nave's embedding stopped (${e::class.simpleName}); ${"%,d".format(vectors.size)} saved. Run again to carry on. Skipping the topical variants.")
            return null
        }
        cache.save(vectors)
    }
    return TopicIndex(entries, entries.map { vectors.getValue(it.text) }, index)
}

/**
 * Ways to put Nave's verses into the ranking, on cached vectors (no neurons). "RRF" adds the
 * matching entries' verses, ranked by their own similarity to the query, as a third list next to
 * the vectors and the keywords; "×2" counts that list twice; "first" puts its best 5 above the
 * live ranking, as Key passages are.
 */
fun navesVariants(topics: TopicIndex, index: VectorIndex, keywords: KeywordIndex): List<Variant> {
    fun lists(q: SearchEvalQuery, v: FloatArray, threshold: Float, entries: Int): Triple<FloatArray, FloatArray, List<Int>> {
        val vectorScores = index.scores(v)
        return Triple(vectorScores, keywords.scores(q.query), rankedRows(topics.verseRows(v, threshold, entries), vectorScores))
    }
    fun fused(q: SearchEvalQuery, v: FloatArray, threshold: Float, entries: Int, weight: Int): FloatArray {
        val (vectorScores, keywordScores, topical) = lists(q, v, threshold, entries)
        return reciprocalRankFusion(
            listOf(rankedAll(vectorScores, positiveOnly = false), rankedAll(keywordScores, positiveOnly = true)) + List(weight) { topical },
            index.size
        )
    }
    val out = mutableListOf<Variant>()
    for (threshold in listOf(0.55f, 0.60f, 0.65f, 0.70f)) {
        out += Variant("$NAVES_PREFIX ≥$threshold, 3 entries, RRF") { q, v -> fused(q, v, threshold, 3, 1) }
    }
    for (threshold in listOf(0.60f, 0.65f)) {
        out += Variant("$NAVES_PREFIX ≥$threshold, 3 entries, RRF ×2") { q, v -> fused(q, v, threshold, 3, 2) }
        out += Variant("$NAVES_PREFIX ≥$threshold, 1 entry, RRF ×2") { q, v -> fused(q, v, threshold, 1, 2) }
        out += Variant("$NAVES_PREFIX ≥$threshold, 3 entries, 5 first") { q, v ->
            val (vectorScores, keywordScores, topical) = lists(q, v, threshold, 3)
            reciprocalRankFusion(vectorScores, keywordScores).also { scores ->
                topical.take(5).forEachIndexed { i, row -> scores[row] += 10f + (5 - i) }
            }
        }
    }
    return out
}

/** Key passages first on top of the live ranking and of each topical variant: what Search would show with each. */
fun keyPassagesOnTopReport(variants: List<VariantResult>, withPassages: (VariantResult) -> List<KeyPassagesResult>): String = buildString {
    val groups = variants.first().results.map { it.query.group }.distinct()
    appendLine("## Key passages first, on each ranking (#394)")
    appendLine()
    appendLine("| Ranking under Key passages | All | ${groups.joinToString(" | ")} |")
    appendLine("|---|---|${groups.joinToString("") { "---|" }}")
    for (variant in variants) {
        val results = withPassages(variant)
        appendLine(
            "| ${variant.name} | ${results.count { it.hit }}/${results.size} | " +
                groups.joinToString(" | ") { g -> results.filter { it.query.group == g }.let { "${it.count { r -> r.hit }}/${it.size}" } } + " |"
        )
    }
}

/** Each query's closest Nave's entry, to judge the matching by eye. */
fun topicMatchesReport(queries: List<SearchEvalQuery>, queryVectors: QueryVectors, topics: TopicIndex): String = buildString {
    appendLine("## Closest Nave's entry per query (#394)")
    appendLine()
    appendLine("| Query | Closest entry | Score |")
    appendLine("|---|---|---|")
    for (q in queries) {
        val (entry, score) = topics.best(queryVectors.vectors.getValue(q.id)) ?: continue
        appendLine("| ${q.id} | ${entry.text.replace("|", "/").take(80)} | ${"%.3f".format(score)} |")
    }
}
