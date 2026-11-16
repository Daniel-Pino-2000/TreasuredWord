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

/** A verse a search found, with its text in the searched translation: what the Bible Helper is given (#287, phase 5). */
data class FoundVerse(val book: Int, val chapter: Int, val verse: Int, val text: String)

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
 * search answers [QuotaExhausted] until 00:00 UTC. It's counted in memory, so a restart (Render's
 * sleep) starts it again; that only makes it looser, never stricter.
 */
class VerseSearch(
    private val provider: EmbeddingProvider,
    private val translation: String = TRANSLATION,
    private val clock: Clock = Clock.systemUTC(),
    private val dailyCap: Int = DAILY_CAP
) {
    private val day = AtomicReference(LocalDate.now(clock))
    private val searchesToday = AtomicInteger(0)

    /** @throws com.application.bibleapp.server.ai.AiProviderException when the query can't be embedded. */
    suspend fun search(query: String, limit: Int = RESULTS): List<VerseHit> =
        ranked(query, limit) { _, hits -> hits }

    /**
     * The same search, with each verse's text from [translation] (the BSB): the Bible Helper
     * reads it (#287, phase 5). Counted against the same [dailyCap] as the endpoint, since it
     * spends the same neurons.
     *
     * @throws com.application.bibleapp.server.ai.AiProviderException when the query can't be embedded.
     */
    suspend fun searchWithText(query: String, limit: Int): List<FoundVerse> =
        ranked(query, limit) { connection, hits -> verseTexts(connection, translation, hits) }

    /** Embeds [query], ranks, and runs [then] on the same connection: one transaction, one trip to Neon. */
    private suspend fun <T> ranked(query: String, limit: Int, then: (Connection, List<VerseHit>) -> List<T>): List<T> {
        countSearch()
        val vector = provider.embed(listOf(query), EmbeddingKind.QUERY).vectors.single()
        val started = System.nanoTime()
        val results = newSuspendedTransaction(Dispatchers.IO) {
            val connection = TransactionManager.current().connection.connection as Connection
            then(connection, rankVerses(connection, provider.model, translation, vector, query, limit))
        }
        log.info("Search: ${results.size} results in ${(System.nanoTime() - started) / 1_000_000} ms")
        return results
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

        /** [hits]' text in [translation], in [hits]' order; a verse without a stored text is left out. */
        fun verseTexts(connection: Connection, translation: String, hits: List<VerseHit>): List<FoundVerse> {
            if (hits.isEmpty()) return emptyList()
            val texts = connection.prepareStatement(
                """
                SELECT s.book, s.chapter, s.verse, s.text
                FROM search_verses s
                JOIN unnest(?::int[], ?::int[], ?::int[]) AS h(book, chapter, verse)
                    ON s.book = h.book AND s.chapter = h.chapter AND s.verse = h.verse
                WHERE s.translation = ?
                """.trimIndent()
            ).use { statement ->
                statement.setArray(1, connection.createArrayOf("int4", hits.map { it.book }.toTypedArray()))
                statement.setArray(2, connection.createArrayOf("int4", hits.map { it.chapter }.toTypedArray()))
                statement.setArray(3, connection.createArrayOf("int4", hits.map { it.verse }.toTypedArray()))
                statement.setString(4, translation)
                statement.executeQuery().use { rows ->
                    buildMap {
                        while (rows.next()) put(Triple(rows.getInt(1), rows.getInt(2), rows.getInt(3)), rows.getString(4))
                    }
                }
            }
            return hits.mapNotNull { hit ->
                texts[Triple(hit.book, hit.chapter, hit.verse)]?.let { FoundVerse(hit.book, hit.chapter, hit.verse, it) }
            }
        }

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
