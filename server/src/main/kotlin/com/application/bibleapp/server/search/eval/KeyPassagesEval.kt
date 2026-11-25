package com.application.bibleapp.server.search.eval

import com.application.bibleapp.server.ai.UsfmBookCodes

/*
 * What the Search screen really shows (#320, #393): a matching topic guide's Key passages first,
 * then the verses search ranks. Scoring search alone undersells the queries a guide covers and
 * says nothing about the ones it doesn't, so the evaluation reports both.
 */

private val passagePattern = Regex("""^([1-3]?[A-Z]{2,3}) (\d+)(?::(\d+)(?:-(\d+))?)?$""")

/** Whether a guide passage covers [verse]: "MAT 11:28-30" its verses, "PSA 73" its whole chapter. */
fun passageCovers(passage: String, verse: VerseRef): Boolean {
    val match = passagePattern.matchEntire(passage.trim()) ?: return false
    val book = UsfmBookCodes.codes.indexOf(match.groupValues[1]) + 1
    if (book != verse.book || match.groupValues[2].toInt() != verse.chapter) return false
    val first = match.groupValues[3].toIntOrNull() ?: return true
    val last = match.groupValues[4].toIntOrNull() ?: first
    return verse.verse in first..last
}

/** One query on the Search screen: the guide [passages] it matched (none for most) and the outcome. */
class KeyPassagesResult(val query: SearchEvalQuery, val passages: List<String>, val searchAlone: QueryResult) {
    /** A passage covers an expected verse. */
    val passageHit: Boolean get() = passages.any { p -> query.expected.any { passageCovers(p, it) } }

    /**
     * A page of [TOP_K]: the passages take the first places and the ranked verses fill the rest,
     * the same count `TopicRoutingEval` uses, so the two runners agree.
     */
    val hit: Boolean
        get() = passageHit || searchAlone.top.take((TOP_K - passages.size).coerceAtLeast(0)).any { it.ref in query.expected }
}

/** The Search screen's numbers next to search alone's, overall and per group, then every query that matched a guide or missed. */
fun keyPassagesReport(results: List<KeyPassagesResult>): String = buildString {
    fun count(list: List<KeyPassagesResult>, hit: (KeyPassagesResult) -> Boolean) = "${list.count(hit)} of ${list.size}"
    appendLine("## Key passages first (what Search shows, #320)")
    appendLine()
    appendLine("Search alone is the ranking above (\"plain + keywords (RRF)\", the live one). With Key passages, a matching")
    appendLine("topic guide's passages take the first places of the $TOP_K and search fills the rest.")
    appendLine()
    appendLine("| Group | Queries | Matched a guide | Search alone | With Key passages |")
    appendLine("|---|---|---|---|---|")
    val groups = results.groupBy { it.query.group }.toList() + ("All" to results)
    for ((group, inGroup) in groups) {
        appendLine(
            "| $group | ${inGroup.size} | ${inGroup.count { it.passages.isNotEmpty() }} " +
                "| ${count(inGroup) { it.searchAlone.hit }} | ${count(inGroup) { it.hit }} |"
        )
    }
    appendLine()
    appendLine("| Query | Guide passages | Passage hit | Search alone rank | With Key passages |")
    appendLine("|---|---|---|---|---|")
    for (result in results) {
        appendLine(
            "| ${result.query.id} | ${result.passages.size.takeIf { it > 0 } ?: "–"} | ${if (result.passageHit) "yes" else "–"} " +
                "| ${result.searchAlone.bestRank ?: "–"} | ${if (result.hit) "yes" else "**no**"} |"
        )
    }
}
