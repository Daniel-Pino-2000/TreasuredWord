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

/**
 * What a search found: the ranked [verses], a matching topic guide's [keyPassages] (#320), if any,
 * and [crisis] when that guide is the crisis guide (#324).
 */
data class SearchFound(val verses: List<VerseHit>, val keyPassages: List<String> = emptyList(), val crisis: Boolean = false)

/**
 * Search by meaning (#287, phase 3), ranked the way phase 1's evaluation chose: the query's
 * vector against every verse's bge-m3 vector of the BSB, and Postgres full-text search on the
 * BSB text, fused by reciprocal rank (k = 60). The vectors find verses *about* the idea in other
 * words; the keywords lift the verse with the exact word that the vectors may rank 50th.
 *
 * A third list comes from Nave's Topical Bible (#394, migration V11): when the query is close to
 * one of its entries ("Envy", "Jonah: Swallowed by a great fish"), that entry's verses, the ones
 * Nave's says teach the topic, join the fusion. The vectors find verses that *sound like* a query;
 * this list brings the ones that are *about* it. While V11's tables are empty it adds nothing.
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
    private val keyPassages: KeyPassages? = null,
    /** topic_entries.source for the topical list (#394); tests use rows of their own. */
    private val topicSource: String = TOPIC_SOURCE
) {
    private val day = AtomicReference(LocalDate.now(clock))
    private val searchesToday = AtomicInteger(0)

    /** @throws com.application.bibleapp.server.ai.AiProviderException when the query can't be embedded. */
    suspend fun search(query: String, limit: Int = RESULTS): SearchFound {
        val vector = embedQuery(query)
        val started = System.nanoTime()
        val hits = newSuspendedTransaction(Dispatchers.IO) {
            rankVerses(TransactionManager.current().connection.connection as Connection, provider.model, translation, vector, query, limit, topicSource)
        }
        log.info("Search: ${hits.size} results in ${(System.nanoTime() - started) / 1_000_000} ms")
        // Not logged, not even as a count: which guide matched would hint at what was asked.
        val match = keyPassagesFor(vector)
        return SearchFound(hits, match.passages, match.crisis)
    }

    /** Key passages never cost the search itself: if matching fails, the verses still come back. */
    private fun keyPassagesFor(vector: FloatArray): KeyPassages.Match = try {
        keyPassages?.match(vector) ?: KeyPassages.Match.NONE
    } catch (e: Exception) {
        log.warn("Key passages failed: {}", e::class.simpleName)
        KeyPassages.Match.NONE
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
        /** Which topical index the third list reads (topic_entries.source). */
        const val TOPIC_SOURCE = "naves"
        /**
         * A Nave's entry joins at cosine similarity 0.65 or more (pgvector distance 0.35 or less),
         * up to [TOPIC_ENTRIES] of them. The held-out set measured 0.55 and 0.60 a query or two
         * higher, but their weak matches put off-topic verses on the page ("I hate the way I look"
         * matched "Spitting: In the face"); 0.65 keeps nearly all the gain without them (#394).
         */
        private const val TOPIC_MAX_DISTANCE = 0.35
        private const val TOPIC_ENTRIES = 3
        /** ~0.01 neurons each, so 20,000 searches are ~200 of the 10,000 a day. */
        const val DAILY_CAP = 20_000

        /**
         * All three rankings in one round trip (Neon is a few hundred ms away). Each list keeps its
         * best [CANDIDATES]; a verse past that in every list would score under 3/(60 + 200), well
         * below the 20th result. The keywords list ORs the query's words (stemmed by Postgres's
         * English dictionary, common words dropped), so a long question still matches verses with
         * some of its words; a query in another language usually has none and leaves the ranking to
         * the vectors. The topical list ranks the matching entries' verses by their own closeness to
         * the query, as the evaluation measured (`NAVES_PREFIX` variants in `search/eval`).
         */
        fun rankVerses(
            connection: Connection,
            model: String,
            translation: String,
            vector: FloatArray,
            query: String,
            limit: Int,
            topicSource: String = TOPIC_SOURCE
        ): List<VerseHit> =
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
                ),
                topics AS (
                    SELECT id FROM (
                        SELECT id, embedding <=> ?::halfvec AS distance
                        FROM topic_entries WHERE source = ? AND model = ?
                        ORDER BY distance LIMIT $TOPIC_ENTRIES
                    ) nearest
                    WHERE distance <= $TOPIC_MAX_DISTANCE
                ),
                by_topic AS (
                    SELECT book, chapter, verse, row_number() OVER (ORDER BY distance, book, chapter, verse) AS rank
                    FROM (
                        SELECT e.book, e.chapter, e.verse, e.embedding <=> ?::halfvec AS distance
                        FROM (
                            SELECT DISTINCT v.book, v.chapter, v.verse
                            FROM topic_entry_verses v JOIN topics t ON t.id = v.id
                            WHERE v.source = ?
                        ) named
                        JOIN verse_embeddings e
                          ON e.model = ? AND e.translation = ?
                         AND e.book = named.book AND e.chapter = named.chapter AND e.verse = named.verse
                        ORDER BY distance, e.book, e.chapter, e.verse LIMIT $CANDIDATES
                    ) closest
                )
                SELECT book, chapter, verse, SUM(1.0 / ($RRF_K + rank)) AS score
                FROM (SELECT * FROM by_meaning UNION ALL SELECT * FROM by_words UNION ALL SELECT * FROM by_topic) ranked
                GROUP BY book, chapter, verse
                ORDER BY score DESC, book, chapter, verse
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                val literal = VerseSearchLoader.vectorLiteral(vector)
                statement.setString(1, literal)
                statement.setString(2, model)
                statement.setString(3, translation)
                statement.setString(4, query)
                statement.setString(5, translation)
                // topics, then by_topic: its distance comes before its inner source filter.
                statement.setString(6, literal)
                statement.setString(7, topicSource)
                statement.setString(8, model)
                statement.setString(9, literal)
                statement.setString(10, topicSource)
                statement.setString(11, model)
                statement.setString(12, translation)
                statement.setInt(13, limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(VerseHit(rows.getInt(1), rows.getInt(2), rows.getInt(3), rows.getDouble(4)))
                    }
                }
            }
    }
}
