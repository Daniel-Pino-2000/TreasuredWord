package com.application.bibleapp.server.search

import com.application.bibleapp.server.ai.QuotaExhausted
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import kotlinx.coroutines.Dispatchers
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** One verse a search found, best first. [score] is its reciprocal-rank-fusion score: higher is closer. */
data class VerseHit(val book: Int, val chapter: Int, val verse: Int, val score: Double)

/** What a search found: the ranked [verses], and a matching topic guide's [keyPassages] (#320), if any. */
data class SearchFound(val verses: List<VerseHit>, val keyPassages: List<String> = emptyList())

/**
 * Search by meaning (#287, phase 3), ranked the way phase 1's evaluation chose: the query's
 * vector against every verse's bge-m3 vector of the BSB, and Postgres full-text search on the
 * BSB text, fused by reciprocal rank (k = 60). The vectors find verses *about* the idea in other
 * words; the keywords lift the verse with the exact word that the vectors may rank 50th.
 *
 * One embedding call per search (~0.01 neurons), then one query. Only references come back; the
 * app shows each verse in the reader's own translation. The query is never logged
 * ([WorkersAiEmbeddingProvider] logs only status, time and neurons).
 *
 * [dailyCap] protects the Bible Helper's share of the free 10,000 neurons a day: past it, every
 * search (and the Bible Helper's topic guide matching, [embedQuery]) answers [QuotaExhausted]
 * until 00:00 UTC. It's counted in memory, so a restart (Render's
 * sleep) starts it again; that only makes it looser, never stricter.
 *
 * [keyPassages] matches the same query vector to the topic guides (#320), for no extra neurons;
 * null leaves them out.
 */
class VerseSearch(
    private val provider: EmbeddingProvider,
    private val translation: String = TRANSLATION,
    private val clock: Clock = Clock.systemUTC(),
    private val dailyCap: Int = DAILY_CAP,
    private val keyPassages: KeyPassages? = null
) {
    private val day = AtomicReference(LocalDate.now(clock))
    private val searchesToday = AtomicInteger(0)

    /** @throws com.application.bibleapp.server.ai.AiProviderException when the query can't be embedded. */
    suspend fun search(query: String, limit: Int = RESULTS): SearchFound {
        val vector = embedQuery(query)
        val started = System.nanoTime()
        val hits = newSuspendedTransaction(Dispatchers.IO) {
            rankVerses(TransactionManager.current().connection.connection as Connection, provider.model, translation, vector, query, limit)
        }
        log.info("Search: ${hits.size} results in ${(System.nanoTime() - started) / 1_000_000} ms")
        // Not logged, not even as a count: which guide matched would hint at what was asked.
        return SearchFound(hits, keyPassagesFor(vector))
    }

    /** Key passages never cost the search itself: if matching fails, the verses still come back. */
    private fun keyPassagesFor(vector: FloatArray): List<String> = try {
        keyPassages?.forQuery(vector).orEmpty()
    } catch (e: Exception) {
        log.warn("Key passages failed: {}", e::class.simpleName)
        emptyList()
    }

    /**
     * [text]'s vector, counted against [dailyCap] like a search: also how the Bible Helper matches
     * a question to its topic guides (#319), so both share one cap on embeddings.
     *
     * @throws com.application.bibleapp.server.ai.AiProviderException when it can't be embedded,
     *   or [QuotaExhausted] past the cap.
     */
    suspend fun embedQuery(text: String): FloatArray {
        countSearch()
        return provider.embed(listOf(text), EmbeddingKind.QUERY).vectors.single()
    }

    private fun countSearch() {
        val today = LocalDate.now(clock)
        val seen = day.get()
        if (today != seen && day.compareAndSet(seen, today)) searchesToday.set(0)
        if (searchesToday.incrementAndGet() > dailyCap) throw QuotaExhausted("Today's $dailyCap searches are used up")
    }

    companion object {
        private val log = LoggerFactory.getLogger(VerseSearch::class.java)

        /** The model the stored vectors were made with (`verse_embeddings.model`, phase 1's choice). */
        const val MODEL = "@cf/baai/bge-m3"
        /** The translation the vectors and text are of: the public-domain BSB. */
        const val TRANSLATION = "bsb"
        const val RESULTS = 20
        private const val CANDIDATES = 200
        private const val RRF_K = 60
        /** ~0.01 neurons each, so 20,000 searches are ~200 of the 10,000 a day. */
        const val DAILY_CAP = 20_000

        /**
         * Both rankings in one round trip (Neon is a few hundred ms away). Each half keeps its best
         * [CANDIDATES]; a verse past that in both lists would score under 2/(60 + 200), well below the
         * 20th result. The keywords half ORs the query's words (stemmed by Postgres's English
         * dictionary, common words dropped), so a long question still matches verses with some of its
         * words; a query in another language usually has none and leaves the ranking to the vectors.
         */
        fun rankVerses(connection: Connection, model: String, translation: String, vector: FloatArray, query: String, limit: Int): List<VerseHit> =
            connection.prepareStatement(
                """
                WITH by_meaning AS (
                    SELECT book, chapter, verse, row_number() OVER (ORDER BY distance) AS rank
                    FROM (
                        SELECT book, chapter, verse, embedding <=> ?::halfvec AS distance
                        FROM verse_embeddings WHERE model = ? AND translation = ?
                        ORDER BY distance LIMIT $CANDIDATES
                    ) nearest
                ),
                words AS (
                    SELECT to_tsquery('simple', string_agg(quote_literal(word), ' | ')) AS query
                    FROM unnest(tsvector_to_array(to_tsvector('english', ?))) AS word
                ),
                by_words AS (
                    SELECT book, chapter, verse, row_number() OVER (ORDER BY relevance DESC, book, chapter, verse) AS rank
                    FROM (
                        SELECT s.book, s.chapter, s.verse, ts_rank(s.words, w.query) AS relevance
                        FROM search_verses s, words w
                        WHERE w.query IS NOT NULL AND s.translation = ? AND s.words @@ w.query
                        ORDER BY relevance DESC, s.book, s.chapter, s.verse LIMIT $CANDIDATES
                    ) matching
                )
                SELECT book, chapter, verse, SUM(1.0 / ($RRF_K + rank)) AS score
                FROM (SELECT * FROM by_meaning UNION ALL SELECT * FROM by_words) ranked
                GROUP BY book, chapter, verse
                ORDER BY score DESC, book, chapter, verse
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, VerseSearchLoader.vectorLiteral(vector))
                statement.setString(2, model)
                statement.setString(3, translation)
                statement.setString(4, query)
                statement.setString(5, translation)
                statement.setInt(6, limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(VerseHit(rows.getInt(1), rows.getInt(2), rows.getInt(3), rows.getDouble(4)))
                    }
                }
            }
    }
}
