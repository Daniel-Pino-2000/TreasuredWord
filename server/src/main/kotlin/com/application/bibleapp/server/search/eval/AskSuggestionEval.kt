package com.application.bibleapp.server.search.eval

import com.application.bibleapp.server.ai.TopicRouter

/*
 * When should Search offer the Bible Helper (#401)? A search about someone's life ("my dad is in
 * the hospital and I'm scared") is a request for help, not a look-up. The query's vector is
 * compared with what people ask the Bible Helper (the topic guides' "Asks" phrases) and with what
 * people look up (Nave's entry labels): closer to the first means "offer Ask". No new embeddings.
 */

/** One query's two closenesses: to the closest guide phrase ([asks]) and to the closest Nave's entry ([lookup]). */
class AskScores(val query: SearchEvalQuery, val asks: Float, val lookup: Float) {
    val margin: Float get() = asks - lookup
}

fun askScores(queries: List<SearchEvalQuery>, queryVectors: QueryVectors, router: TopicRouter, topics: TopicIndex): List<AskScores> =
    queries.map { q ->
        val v = queryVectors.vectors.getValue(q.id)
        AskScores(q, router.scoresFor(v).first().score.toFloat(), topics.best(v)?.second ?: 0f)
    }

/** The rules tried: offer Ask when the guide phrases are closer than Nave's by more than a margin. */
val ASK_MARGINS = listOf(-0.10f, -0.05f, -0.02f, 0f, 0.02f, 0.05f, 0.10f)

/** How often each rule offers Ask, per group: LIFE and MOD should get it; NEW, STORY and KJV shouldn't. */
fun askSuggestionReport(scores: List<AskScores>): String = buildString {
    val groups = scores.map { it.query.group }.distinct()
    appendLine("## When to offer the Bible Helper (#401)")
    appendLine()
    appendLine("Offered when (closest guide phrase) − (closest Nave's entry) is above the margin.")
    appendLine("LIFE and MOD are sentences about someone's life; NEW, STORY and KJV are look-ups.")
    appendLine()
    appendLine("| Margin | ${groups.joinToString(" | ")} |")
    appendLine("|---|${groups.joinToString("") { "---|" }}")
    for (m in ASK_MARGINS) {
        appendLine("| $m | " + groups.joinToString(" | ") { g ->
            val inGroup = scores.filter { it.query.group == g }
            "${inGroup.count { it.margin > m }}/${inGroup.size}"
        } + " |")
    }
    appendLine()
    appendLine("| Query | Text | Guide phrase | Nave's entry | Margin |")
    appendLine("|---|---|---|---|---|")
    for (s in scores) {
        appendLine("| ${s.query.id} | ${s.query.query.replace("|", "/").take(50)} | ${"%.3f".format(s.asks)} | ${"%.3f".format(s.lookup)} | ${"%+.3f".format(s.margin)} |")
    }
}
