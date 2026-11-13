package com.application.bibleapp.server.search.eval

import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.search.EmbeddingKind
import com.application.bibleapp.server.search.FakeEmbeddingProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The search-by-meaning evaluation (#287) with a fake provider: no network, no neurons. */
class SemanticSearchEvalTest {

    private val folder = Files.createTempDirectory("semantic-eval").toFile()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    private fun ref(n: Int) = VerseRef(19, 1, n)

    /** One-hot vectors: verse n points along axis n. */
    private fun oneHot(axis: Int, size: Int = 12, weight: Float = 1f) = FloatArray(size).also { it[axis] = weight }

    private val query = SearchEvalQuery(id = "T-01", query = "worry", expected = setOf(ref(3)))

    @Test
    fun theClosestVersesComeFirst() {
        val index = VectorIndex((1..12).associate { ref(it) to oneHot(it - 1) })
        // Mostly verse 3, a little verse 5, and the length doesn't matter.
        val vector = FloatArray(12).also { it[2] = 9f; it[4] = 3f }

        val top = index.top(index.scores(vector), 3)

        assertEquals(listOf(ref(3), ref(5)), top.take(2).map { it.ref })
        assertEquals(0.949f, top.first().score, 0.001f)
    }

    @Test
    fun aHitIsAnExpectedVerseInTheTopTen() {
        // Verse n scores 1/n-ish against the query, so verse 1 is first and verse 12 last.
        val vectors = (1..12).associate { n -> ref(n) to FloatArray(2).also { it[0] = 13f - n; it[1] = n.toFloat() } }
        val index = VectorIndex(vectors)
        val vector = floatArrayOf(1f, 0f)

        val third = score(query, vector, index)
        assertEquals(3, third.bestRank)
        assertTrue(third.hit)
        assertEquals(1, third.expectedInTop)

        val eleventh = score(query.copy(expected = setOf(ref(11), ref(12))), vector, index)
        assertEquals(11, eleventh.bestRank)
        assertFalse(eleventh.hit)
        assertEquals(TOP_K, eleventh.top.size)

        val notInTranslation = score(query.copy(expected = setOf(VerseRef(1, 1, 1))), vector, index)
        assertNull(notInTranslation.bestRank)
        assertFalse(notInTranslation.hit)
    }

    @Test
    fun meanReciprocalRankCountsOnlyTheTopTen() {
        val results = listOf(
            QueryResult(query, emptyList(), bestRank = 1),
            QueryResult(query, emptyList(), bestRank = 4),
            QueryResult(query, emptyList(), bestRank = 11),
            QueryResult(query, emptyList(), bestRank = null)
        )
        assertEquals((1.0 + 0.25) / 4, meanReciprocalRank(results), 1e-9)
    }

    private val verses = (1..10).map { Verse(ref(it), "verse number $it about word$it") }

    @Test
    fun stopsAtTheDailyLimitAndCarriesOnTheNextDay() = runBlocking<Unit> {
        val cacheFile = File(folder, "cache/fake-kjv.vec")
        val provider = FakeEmbeddingProvider(maxBatch = 4).apply { quotaAfterCalls = 1 }

        // Day one: one batch of 4, then the neurons run out.
        val dayOne = VectorCache(cacheFile, provider.model, "kjv")
        val dayOneVectors = dayOne.load()
        val stopped = embedMissingVerses(verses, dayOneVectors, dayOne, provider, retryPausesMillis = emptyList())
        assertFalse(stopped.finished)
        assertTrue(stopped.quotaExhausted)
        assertEquals(ref(5), stopped.stoppedAt)
        assertEquals(4, stopped.embedded)
        assertEquals(2.0, stopped.neurons)

        // Day two: a new run loads the 4 from disk and only asks for the other 6.
        provider.quotaAfterCalls = null
        provider.calls.clear()
        val dayTwo = VectorCache(cacheFile, provider.model, "kjv")
        val dayTwoVectors = dayTwo.load()
        assertEquals(4, dayTwoVectors.size)
        val done = embedMissingVerses(verses, dayTwoVectors, dayTwo, provider, retryPausesMillis = emptyList())

        assertTrue(done.finished)
        assertEquals(6, done.embedded)
        assertEquals(listOf(4, 2), provider.calls.map { it.texts.size })
        assertTrue(provider.calls.all { it.kind == EmbeddingKind.DOCUMENT })
        assertEquals("verse number 5 about word5", provider.calls.first().texts.first())
        assertEquals(verses.map { it.ref }, VectorCache(cacheFile, provider.model, "kjv").load().keys.sorted())

        // Day three: nothing left to embed, so nothing is asked for.
        provider.calls.clear()
        val again = embedMissingVerses(verses, dayTwoVectors, dayTwo, provider)
        assertEquals(0, again.embedded)
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun aPassingFailureIsTriedAgainButOneThatWontPassStops() = runBlocking<Unit> {
        val provider = FakeEmbeddingProvider(maxBatch = 10)
        provider.failures += Unavailable("busy", transient = true)
        provider.failures += Unavailable("busy", transient = true)
        val cache = VectorCache(File(folder, "a.vec"), provider.model, "kjv")

        val done = embedMissingVerses(verses, cache.load(), cache, provider, retryPausesMillis = listOf(0, 0))
        assertTrue(done.finished)
        assertEquals(3, provider.calls.size)

        val other = FakeEmbeddingProvider(maxBatch = 10)
        other.failures += Unavailable("bad request")
        val otherCache = VectorCache(File(folder, "b.vec"), other.model, "kjv")
        val stopped = embedMissingVerses(verses, otherCache.load(), otherCache, other, retryPausesMillis = listOf(0, 0))
        assertFalse(stopped.finished)
        assertFalse(stopped.quotaExhausted)
        assertEquals(ref(1), stopped.stoppedAt)
        assertEquals(1, other.calls.size)
    }

    @Test
    fun queriesAreEmbeddedAsQueries() = runBlocking<Unit> {
        val provider = FakeEmbeddingProvider(maxBatch = 2)
        val queries = (1..3).map { SearchEvalQuery(id = "Q-$it", query = "query $it", expected = setOf(ref(it))) }

        val vectors = embedQueries(queries, provider, retryPausesMillis = emptyList())

        assertEquals(setOf("Q-1", "Q-2", "Q-3"), vectors.vectors.keys)
        assertEquals(listOf(2, 1), provider.calls.map { it.texts.size })
        assertTrue(provider.calls.all { it.kind == EmbeddingKind.QUERY })

        provider.quotaAfterCalls = 0
        assertFailsWith<QuotaExhausted> { embedQueries(queries, provider, retryPausesMillis = emptyList()) }
    }

    @Test
    fun fromVersesToReportWithTheFakeProvider() = runBlocking<Unit> {
        val provider = FakeEmbeddingProvider(maxBatch = 4)
        val cache = VectorCache(File(folder, "c.vec"), provider.model, "kjv")
        val vectors = cache.load()
        val bible = embedMissingVerses(verses, vectors, cache, provider)
        val queries = listOf(
            SearchEvalQuery(id = "TOPIC-01", query = "word7", expected = setOf(ref(7))),
            SearchEvalQuery(id = "LANG-XX-01", query = "nothing alike", language = "sw", expected = setOf(VerseRef(1, 1, 1)))
        )
        val queryVectors = embedQueries(queries, provider)
        val index = VectorIndex(vectors)
        val results = queries.map { score(it, queryVectors.vectors.getValue(it.id), index) }

        assertEquals(ref(7), results.first().top.first().ref)
        val text = report(results, provider.model, "kjv", LocalDate.of(2026, 11, 14), index, verses.associate { it.ref to it.text }, bible, queryVectors)

        assertTrue("# Search by meaning evaluation, 2026-11-14" in text)
        assertTrue("**Hit rate:** 1 of 2 queries (50%)" in text, text)
        assertTrue("| TOPIC | 1 of 1 (100%) | 1.00 |" in text, text)
        assertTrue("| LANG | 0 of 1 (0%) | 0.00 |" in text, text)
        assertTrue("1. **PSA 1:7** ✓" in text, text)
        assertTrue("none of them is in this translation" in text)
        assertTrue("~5.00 to embed 10 verses" in text, text)
    }
}
