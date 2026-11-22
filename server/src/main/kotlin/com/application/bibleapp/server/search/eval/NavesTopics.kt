package com.application.bibleapp.server.search.eval

import com.application.bibleapp.server.ai.UsfmBookCodes
import java.io.File

/*
 * Nave's Topical Bible as a topical index for search by meaning (#394). Search finds verses that
 * *sound like* a query; Nave's lists, for 5,319 topics, the verses that *teach* each one, the same
 * idea as the hand-checked topic guides (#320) but for thousands of topics. A query is matched to
 * Nave's entries by meaning, and the matching entries' verses are ranked first.
 *
 * Source: NavesTopicalDictionary.csv from github.com/BradyStephenson/bible-data (CC BY 4.0, data
 * from the public-domain 1896 text). Only its references and short entry labels are used.
 */

/** A passage Nave's names: [lastVerse] null with [firstVerse] null is the whole chapter. */
data class TopicPassage(val book: Int, val chapter: Int, val firstVerse: Int?, val lastVerse: Int?)

/**
 * One entry line of a topic: [text] is what a query is matched against ("Envy" for the topic's
 * general verses, "Envy, instances of: Cain, of Abel" for a sub-entry), [passages] its verses.
 */
data class TopicEntry(val subject: String, val text: String, val passages: List<TopicPassage>)

object NavesTopics {

    /** Nave's writes Song of Songs as "So"; everything else is a USFM code. */
    private val codes: Map<String, Int> = UsfmBookCodes.codes.withIndex().associate { (i, code) -> code to i + 1 } + ("So" to 22)
    private val codePattern = codes.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val firstReference = Regex("""(?<![A-Za-z0-9])($codePattern) \d""")
    private val segment = Regex("""^(?:($codePattern) )?(\d+)(?::([\d,\-\s]+))?""")

    fun load(file: File): List<TopicEntry> = parse(file.readText())

    fun parse(csv: String): List<TopicEntry> {
        val rows = readCsv(csv.removePrefix("﻿"))
        require(rows.firstOrNull() == listOf("section", "subject", "entry")) { "Not Nave's CSV: ${rows.firstOrNull()}" }
        return rows.drop(1).filter { it.size >= 3 }.flatMap { (_, subject, entry) -> entries(subject.trim(), entry) }
    }

    /** A topic's lines: indented ones belong to the last unindented label ("INSTANCES OF"). */
    private fun entries(subject: String, entry: String): List<TopicEntry> {
        val name = titleCase(subject)
        var parent: String? = null
        val out = mutableListOf<TopicEntry>()
        for (raw in entry.replace("\r\n", "\n").lines()) {
            if (raw.isBlank()) continue
            val indented = raw.startsWith(" ") || raw.startsWith("\t")
            val line = raw.trim().removePrefix("-").trim()
            val start = firstReference.find(line)?.range?.first
            val label = (if (start == null) line else line.substring(0, start)).trim().trimEnd('.', ',', ';', ':').trim()
            if (!indented) parent = label
            val passages = start?.let { passages(line.substring(it)) }.orEmpty()
            if (passages.isEmpty()) continue
            val text = when {
                label.isEmpty() || label.equals("General scriptures concerning", ignoreCase = true) -> name
                indented && !parent.isNullOrEmpty() && parent != label -> "$name, ${parent!!.lowercase()}: $label"
                else -> "$name: $label"
            }
            out += TopicEntry(subject, text, passages)
        }
        return out
    }

    /** "EXO 6:16-20; JOS 21:4,10; 1CH 6:2,3; 23:13; 24" — a chapter without a book continues the last book. */
    fun passages(references: String): List<TopicPassage> {
        val out = mutableListOf<TopicPassage>()
        var book: Int? = null
        for (part in references.split(';')) {
            val match = segment.find(part.trim()) ?: continue
            match.groupValues[1].takeIf { it.isNotEmpty() }?.let { book = codes.getValue(it) }
            val currentBook = book ?: continue
            val chapter = match.groupValues[2].toInt()
            val verses = match.groupValues[3].trim()
            if (verses.isEmpty()) {
                out += TopicPassage(currentBook, chapter, null, null)
                continue
            }
            for (item in verses.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val (first, last) = item.split('-').map { it.trim() }.let { it[0].toIntOrNull() to it.getOrNull(1)?.toIntOrNull() }
                if (first == null || first < 1) continue
                // "13-7" is a range into the next chapter ("6:13-7:5"); keep its first verse only.
                out += TopicPassage(currentBook, chapter, first, last?.takeIf { it >= first } ?: first)
            }
        }
        return out
    }

    private fun titleCase(subject: String) =
        subject.lowercase().split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    /** RFC 4180: commas, quotes doubled inside quoted fields, newlines inside them. */
    fun readCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += field.toString(); field.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += field.toString(); field.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows
    }
}

/**
 * Nave's entries with their vectors, for ranking (#394): which entries a query is close to, and
 * those entries' verses in [index]'s row order.
 */
class TopicIndex(val entries: List<TopicEntry>, vectors: List<FloatArray>, index: VectorIndex) {
    private val dimensions = vectors.firstOrNull()?.size ?: 0
    private val data = FloatArray(entries.size * dimensions).also { data ->
        vectors.forEachIndexed { row, v -> VectorIndex.normalized(v).copyInto(data, row * dimensions) }
    }

    /** Each entry's verse rows in the verse index (whole chapters expanded); verses the BSB leaves out are dropped. */
    private val rows: List<IntArray> = run {
        val byChapter = index.refs.withIndex().groupBy({ (it.value.book to it.value.chapter) }, { it.index })
        val byRef = index.rows
        entries.map { entry ->
            entry.passages.flatMap { p ->
                if (p.firstVerse == null) byChapter[p.book to p.chapter].orEmpty()
                else (p.firstVerse..(p.lastVerse ?: p.firstVerse)).mapNotNull { v ->
                    runCatching { VerseRef(p.book, p.chapter, v) }.getOrNull()?.let { byRef[it] }
                }
            }.distinct().toIntArray()
        }
    }

    /** Every entry's similarity to [query]. */
    fun scores(query: FloatArray): FloatArray {
        val q = VectorIndex.normalized(query)
        return FloatArray(entries.size) { row ->
            var sum = 0f
            val offset = row * dimensions
            for (i in 0 until dimensions) sum += data[offset + i] * q[i]
            sum
        }
    }

    /**
     * The verse rows of the [maxEntries] closest entries scoring at least [threshold], best entry
     * first: the verses Nave's says teach what the query is about. Empty when nothing is close.
     */
    fun verseRows(query: FloatArray, threshold: Float, maxEntries: Int): Set<Int> {
        val scores = scores(query)
        return scores.indices.filter { scores[it] >= threshold }
            .sortedByDescending { scores[it] }
            .take(maxEntries)
            .flatMapTo(LinkedHashSet()) { rows[it].asIterable() }
    }

    /** The closest entry and its score, for the report. */
    fun best(query: FloatArray): Pair<TopicEntry, Float>? {
        val scores = scores(query)
        val row = scores.indices.maxByOrNull { scores[it] } ?: return null
        return entries[row] to scores[row]
    }
}

/**
 * Search's ranking with a topical index on top: the vectors, the keywords and the matching
 * entries' verses (ranked by their own similarity to the query), fused by reciprocal rank (#394).
 */
fun reciprocalRankFusion(lists: List<List<Int>>, size: Int, k: Int = 60): FloatArray {
    val fused = FloatArray(size)
    for (list in lists) list.forEachIndexed { rank, row -> fused[row] += 1f / (k + rank + 1) }
    return fused
}

/** [rows] sorted by [scores], best first. */
fun rankedRows(rows: Collection<Int>, scores: FloatArray): List<Int> = rows.sortedByDescending { scores[it] }

/** Every row with a positive [scores] value, best first (the keyword list); all rows for vectors. */
fun rankedAll(scores: FloatArray, positiveOnly: Boolean): List<Int> =
    scores.indices.filter { !positiveOnly || scores[it] > 0f }.sortedByDescending { scores[it] }
