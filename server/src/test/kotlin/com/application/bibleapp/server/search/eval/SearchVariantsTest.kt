package com.application.bibleapp.server.search.eval

import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The free ranking variants of the search-by-meaning evaluation (#287): no network, no neurons. */
class SearchVariantsTest {

    private val folder = Files.createTempDirectory("search-variants").toFile()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    private fun oneHot(axis: Int, size: Int = 4) = FloatArray(size).also { it[axis] = 1f }

    @Test
    fun neighboursBlendOnlyWithinTheChapter() {
        // Psalm 1:1–2 and Psalm 2:1: verse 1:2's neighbour is 1:1, never 2:1.
        val index = VectorIndex(
            mapOf(
                VerseRef(19, 1, 1) to oneHot(0),
                VerseRef(19, 1, 2) to oneHot(1),
                VerseRef(19, 2, 1) to oneHot(2)
            )
        )
        val blended = index.withNeighbours(1f)

        val second = blended.scores(oneHot(0))[1]
        assertEquals(0.707f, second, 0.001f) // (0 + 1, 1, 0) / √2 against axis 0
        assertEquals(0f, blended.scores(oneHot(2))[1], 0.0001f) // Psalm 2:1 isn't mixed into 1:2
        assertEquals(1f, blended.scores(oneHot(2))[2], 0.0001f) // and 2:1, alone in its chapter, is unchanged
    }

    @Test
    fun centringTakesOutWhatEveryVerseShares() {
        // Every verse leans along axis 3; after centring, only what tells them apart is left.
        val index = VectorIndex((0..2).associate { VerseRef(1, 1, it + 1) to oneHot(it).also { v -> v[3] = 5f } })
        val centred = index.centred()

        val scores = centred.scores(oneHot(3))
        scores.forEach { assertEquals(0f, it, 0.0001f) }
        assertTrue(centred.scores(oneHot(1))[1] > 0.8f)
    }

    @Test
    fun averageScoresKeepsAVerseMissingFromOneTranslation() {
        val a = VerseRef(40, 17, 20)
        val b = VerseRef(40, 17, 21) // left out of the BSB
        val kjv = VectorIndex(mapOf(a to oneHot(0), b to oneHot(1)))
        val bsb = VectorIndex(mapOf(a to oneHot(1)))

        val scores = averageScores(oneHot(1), kjv, listOf(kjv, bsb))

        assertEquals(0.5f, scores[0], 0.0001f) // (0 + 1) / 2
        assertEquals(1f, scores[1], 0.0001f) // the KJV's alone
    }

    @Test
    fun wordsAreStemmedAndCommonOnesDropped() {
        assertEquals(listOf("worry", "worry", "worry", "tomorrow"), KeywordIndex.words("Worries, worried; worry about the tomorrow"))
        assertEquals(listOf("lord", "shepherd", "want"), KeywordIndex.words("The LORD is my shepherd; I shall not want."))
        assertEquals(listOf("god", "love"), KeywordIndex.words("does God really love me?"))
    }

    @Test
    fun bm25PrefersTheRareWordAndTheShortVerse() {
        val refs = (1..4).map { VerseRef(40, 6, it) }
        val index = KeywordIndex(
            refs,
            listOf(
                "Therefore do not worry about tomorrow",
                "Do not worry about your life, what you will eat or drink, or about your body, what you will wear",
                "Look at the birds of the air",
                "the birds of the air and the lilies of the field"
            )
        )

        val scores = index.scores("worry")
        assertTrue(scores[0] > scores[1]) // same word, shorter verse
        assertEquals(0f, scores[2])
        assertEquals(0f, index.scores("ich habe Angst").max()) // other languages match nothing
    }

    @Test
    fun fusionLiftsAVerseNearTheTopOfEitherList() {
        val vectors = floatArrayOf(0.9f, 0.8f, 0.7f, 0.1f)
        val keywords = floatArrayOf(0f, 0f, 0f, 7f) // only verse 4 has the word

        val fused = reciprocalRankFusion(vectors, keywords)

        // Verse 4 was last by meaning but the only keyword match: being in both lists puts it first.
        assertContentEquals(listOf(3, 0, 1, 2), fused.indices.sortedByDescending { fused[it] })
    }

    @Test
    fun queryVectorsRoundTripByText() {
        val file = folder.resolve("cache/bge-m3.queries")
        val cache = QueryVectorCache(file, "@cf/baai/bge-m3")
        cache.save(mapOf("worry" to floatArrayOf(1f, 2f), "gossip" to floatArrayOf(3f, 4f)))

        val loaded = QueryVectorCache(file, "@cf/baai/bge-m3").load()
        assertContentEquals(floatArrayOf(3f, 4f), loaded.getValue("gossip"))
        assertEquals(2, loaded.size)
        // Another model's file is ignored, not mixed in.
        assertTrue(QueryVectorCache(file, "@cf/qwen/qwen3-embedding-0.6b").load().isEmpty())
    }

    @Test
    fun theVariantsReportListsEveryVariantAndQuery() {
        val ref = VerseRef(19, 23, 1)
        val query = SearchEvalQuery(id = "KJV-01", query = "the Lord is my shepherd", expected = setOf(ref))
        val index = VectorIndex(mapOf(ref to oneHot(0), VerseRef(19, 23, 2) to oneHot(1)))
        val first = rank(query, floatArrayOf(1f, 0f), index)
        val second = rank(query, floatArrayOf(0f, 1f), index)

        val report = variantsReport(
            "@cf/baai/bge-m3", "bsb", LocalDate.of(2026, 11, 14),
            listOf(VariantResult("plain", listOf(first)), VariantResult("other", listOf(second)))
        )

        assertTrue("| plain | 1/1 | 1.00 |" in report)
        assertTrue("| other | 1/1 | 0.50 |" in report)
        assertTrue("| KJV-01 | **1** | **2** |" in report)
    }
}
