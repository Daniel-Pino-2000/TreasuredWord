package com.application.bibleapp.server.search.eval

import kotlin.math.ln

/*
 * Other ways to rank verses, tried on vectors the evaluation already paid for (#287). None of
 * them calls Workers AI, so comparing them costs no neurons: the report says which one phases 2–3
 * should build. With 43 queries the numbers are a guide, not proof: a variant that wins by one
 * query may not win on the next 43.
 */

/**
 * Each verse blended with the verses either side of it in the same chapter: its row becomes
 * verse + [weight] × (previous + next). A short verse ("Jesus wept.", "Fearfulness and trembling
 * are come upon me") says little alone, and the passage around it is what a reader means (#287).
 */
fun VectorIndex.withNeighbours(weight: Float): VectorIndex = mapRows { row, data, out ->
    val ref = refs[row]
    addRow(data, row, out, 1f)
    if (row > 0 && refs[row - 1].sameChapter(ref)) addRow(data, row - 1, out, weight)
    if (row + 1 < size && refs[row + 1].sameChapter(ref)) addRow(data, row + 1, out, weight)
}

/**
 * Every row minus the average verse. Embeddings share a common direction, so a few verses end up
 * close to almost everything (Psalm 55:5 came first for five feelings queries with bge-m3);
 * taking the average out spreads them apart. Queries are left as they are.
 */
fun VectorIndex.centred(): VectorIndex {
    val mean = FloatArray(dimensions)
    for (row in refs.indices) for (i in 0 until dimensions) mean[i] += data[row * dimensions + i]
    for (i in mean.indices) mean[i] /= size.coerceAtLeast(1)
    return mapRows { row, data, out ->
        addRow(data, row, out, 1f)
        for (i in out.indices) out[i] -= mean[i]
    }
}

private fun VectorIndex.addRow(data: FloatArray, row: Int, out: FloatArray, weight: Float) {
    val offset = row * dimensions
    for (i in 0 until dimensions) out[i] += weight * data[offset + i]
}

private fun VerseRef.sameChapter(other: VerseRef) = book == other.book && chapter == other.chapter

/**
 * The average of [indexes]' scores for [query], in [into]'s verse order: one model's vectors of
 * two translations, so a verse matches if either wording does. A verse missing from one
 * translation (the BSB leaves out 16 later additions, like Matthew 17:21) keeps the others' score.
 */
fun averageScores(query: FloatArray, into: VectorIndex, indexes: List<VectorIndex>): FloatArray {
    val sums = FloatArray(into.size)
    val counts = IntArray(into.size)
    for (index in indexes) {
        val scores = index.scores(query)
        if (index.refs == into.refs) {
            for (row in scores.indices) { sums[row] += scores[row]; counts[row]++ }
        } else {
            val rows = into.rows
            index.refs.forEachIndexed { i, ref -> rows[ref]?.let { row -> sums[row] += scores[i]; counts[row]++ } }
        }
    }
    return FloatArray(into.size) { if (counts[it] == 0) -1f else sums[it] / counts[it] }
}

/**
 * Word search with BM25 (the ranking Postgres full-text search and most search engines are built
 * on), over [texts] in [refs] order. It's here to be mixed with the vectors by [reciprocalRankFusion]:
 * it finds the verse with the exact word ("worry" → Matthew 6:34 in the BSB) that the vectors may
 * rank 50th. English only: words of other languages simply match nothing.
 */
class KeywordIndex(val refs: List<VerseRef>, texts: List<String>) {
    private val postings = HashMap<String, MutableList<Pair<Int, Int>>>()
    private val lengths = IntArray(refs.size)
    private val averageLength: Double

    init {
        require(texts.size == refs.size) { "${texts.size} texts for ${refs.size} verses" }
        texts.forEachIndexed { row, text ->
            val words = words(text)
            lengths[row] = words.size
            words.groupingBy { it }.eachCount().forEach { (word, count) -> postings.getOrPut(word) { mutableListOf() } += row to count }
        }
        averageLength = lengths.average().takeIf { it > 0 } ?: 1.0
    }

    /** Every verse's BM25 score for [query]; 0 where no word matches. */
    fun scores(query: String): FloatArray {
        val scores = FloatArray(refs.size)
        for (word in words(query).distinct()) {
            val list = postings[word] ?: continue
            val idf = ln(1 + (refs.size - list.size + 0.5) / (list.size + 0.5))
            for ((row, count) in list) {
                val norm = count * (K1 + 1) / (count + K1 * (1 - B + B * lengths[row] / averageLength))
                scores[row] += (idf * norm).toFloat()
            }
        }
        return scores
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75

        /** Common words that would match half the Bible, KJV ones included. */
        private val STOP = setOf(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "do", "for", "from", "have", "he", "her", "him", "his",
            "how", "i", "if", "in", "is", "it", "me", "my", "not", "of", "on", "or", "our", "so", "that", "the", "their",
            "them", "they", "this", "to", "up", "us", "was", "we", "what", "when", "who", "will", "with", "you", "your",
            "am", "can", "does", "did", "just", "about", "all", "get", "got", "feel", "like", "really", "keep", "im",
            "thee", "thou", "thy", "thine", "ye", "unto", "hath", "shall", "saith", "said", "also", "which", "there"
        )

        /** Lower-case words with a light English stemming, so "worry", "worries" and "worried" meet. */
        fun words(text: String): List<String> =
            text.lowercase().replace("’", "").replace("'", "").split(Regex("[^\\p{L}]+"))
                .filter { it.length > 1 && it !in STOP }
                .map(::stem)

        fun stem(word: String): String = when {
            word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
            word.length > 4 && word.endsWith("ied") -> word.dropLast(3) + "y"
            word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
            word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
            word.length > 4 && (word.endsWith("ses") || word.endsWith("xes") || word.endsWith("ches") || word.endsWith("shes")) -> word.dropLast(2)
            word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
            else -> word
        }
    }
}

/**
 * Reciprocal rank fusion: each verse scores 1/(60 + its rank) in every list it's ranked in, so a
 * verse near the top of either list rises without the two kinds of score needing the same scale.
 * Verses with a keyword score of 0 aren't in the keyword list.
 */
fun reciprocalRankFusion(vectorScores: FloatArray, keywordScores: FloatArray, k: Int = 60): FloatArray {
    require(vectorScores.size == keywordScores.size)
    val fused = FloatArray(vectorScores.size)
    vectorScores.indices.sortedByDescending { vectorScores[it] }.forEachIndexed { rank, row -> fused[row] += 1f / (k + rank + 1) }
    keywordScores.indices.filter { keywordScores[it] > 0f }.sortedByDescending { keywordScores[it] }
        .forEachIndexed { rank, row -> fused[row] += 1f / (k + rank + 1) }
    return fused
}

/** One way of ranking, named for the report; [scores] gives every verse's score for one query. */
class Variant(val name: String, val scores: (query: SearchEvalQuery, vector: FloatArray) -> FloatArray)

/** One variant's results over all the queries. */
class VariantResult(val name: String, val results: List<QueryResult>)

/**
 * The variants table: hits and mean reciprocal rank overall, per group and in English, then every
 * query's best expected rank under each variant, so a change that helps some queries and hurts
 * others shows.
 */
fun variantsReport(model: String, translation: String, date: java.time.LocalDate, variants: List<VariantResult>): String = buildString {
    fun hits(results: List<QueryResult>) = "${results.count { it.hit }}/${results.size}"
    appendLine("# Search by meaning variants, $date")
    appendLine()
    appendLine("- **Model:** `$model`, **translation:** $translation. No neurons: every variant reuses the cached vectors.")
    appendLine("- Hits are queries with an expected verse in the top $TOP_K; MRR is mean reciprocal rank (1.00 = first every time).")
    appendLine("- With ${variants.firstOrNull()?.results?.size ?: 0} queries, one query is 2–3 points: treat small differences as noise.")
    appendLine()
    val groups = variants.firstOrNull()?.results?.map { it.query.group }?.distinct().orEmpty()
    appendLine("| Variant | All | MRR | ${groups.joinToString(" | ")} | English |")
    appendLine("|---|---|---|${groups.joinToString("") { "---|" }}---|")
    for (variant in variants) {
        val r = variant.results
        appendLine(
            "| ${variant.name} | ${hits(r)} | ${"%.2f".format(meanReciprocalRank(r))} | " +
                groups.joinToString(" | ") { group -> hits(r.filter { it.query.group == group }) } +
                " | ${hits(r.filter { it.query.language == "en" })} |"
        )
    }
    appendLine()
    appendLine("## Best expected rank per query")
    appendLine()
    appendLine("Bold is a hit (top $TOP_K).")
    appendLine()
    appendLine("| Query | ${variants.indices.joinToString(" | ") { "V${it + 1}" }} |")
    appendLine("|---|${variants.joinToString("") { "---|" }}")
    val queries = variants.firstOrNull()?.results?.map { it.query }.orEmpty()
    queries.forEachIndexed { q, query ->
        appendLine("| ${query.id} | " + variants.joinToString(" | ") { v ->
            val result = v.results[q]
            val rank = result.bestRank?.toString() ?: "–"
            if (result.hit) "**$rank**" else rank
        } + " |")
    }
    appendLine()
    variants.forEachIndexed { i, v -> appendLine("- **V${i + 1}:** ${v.name}") }
}
