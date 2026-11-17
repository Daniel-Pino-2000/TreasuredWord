package com.application.bibleapp.server.search

import com.application.bibleapp.server.ai.TopicRouter

/**
 * Search's "Key passages" (#320): when a query matches a hand-checked topic guide
 * (`resources/ai/topics.md`, the Bible Helper's guides, #319), the guide's passages, shown above
 * the verses search ranks. Search finds verses that *sound like* a query, which for a life
 * situation means laments; a guide's passages are the ones that answer it. The audit's pilot
 * (`docs/ai_quality_audit.md`) found an expected verse in the top 10 for 41 of 43 queries this way,
 * against 27 with search alone, and routed all 18 queries in nine other languages right.
 *
 * Matching is arithmetic on the query vector search already has, against the phrase vectors
 * stored in the server's resources ([TopicRouter.fromResources]): no extra neurons, nothing in
 * Neon. The Bible Helper's rule exactly ([TopicRouter.route]): the best guide at 0.70 or above and
 * a close second, or a crisis guide by itself.
 */
class KeyPassages(private val router: TopicRouter) {

    /**
     * What a query matched: the guides' [passages] as USFM references ("MAT 11:28-30", "PSA 73"),
     * and [crisis] when the guide is an `Alone` one (the crisis guide), so the app shows its help
     * card above them, as the Bible Helper does (#324).
     */
    data class Match(val passages: List<String>, val crisis: Boolean) {
        companion object {
            val NONE = Match(emptyList(), crisis = false)
        }
    }

    fun match(queryVector: FloatArray): Match {
        val routed = router.route(queryVector)
        if (routed.isEmpty()) return Match.NONE
        return Match(
            passages = routed.flatMap { it.guide.passages.map { passage -> passage.first } }.distinct(),
            // route() sends an alone guide by itself, so any() is that one guide.
            crisis = routed.any { it.guide.alone }
        )
    }

    /** The matching guides' passages, or none. */
    fun forQuery(queryVector: FloatArray): List<String> = match(queryVector).passages
}
