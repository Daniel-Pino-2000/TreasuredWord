package com.application.bibleapp.server.search.eval

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scoring what the Search screen shows: a guide's Key passages first, then search (#320, #393). */
class KeyPassagesEvalTest {

    private val mat11v28 = VerseRef(40, 11, 28)

    private fun query(vararg expected: VerseRef) = SearchEvalQuery("LIFE-01", "so tired", expected = expected.toSet())

    private fun ranked(q: SearchEvalQuery, vararg refs: VerseRef) =
        QueryResult(q, refs.map { Scored(it, 0.5f) }, refs.indexOfFirst { it in q.expected }.takeIf { it >= 0 }?.plus(1))

    @Test
    fun passagesCoverTheirVersesOrTheirWholeChapter() {
        assertTrue(passageCovers("MAT 11:28-30", mat11v28))
        assertTrue(passageCovers("MAT 11:28", mat11v28))
        assertTrue(passageCovers("MAT 11", mat11v28))
        assertFalse(passageCovers("MAT 11:29-30", mat11v28))
        assertFalse(passageCovers("MAT 12", mat11v28))
        assertFalse(passageCovers("MRK 11:28", mat11v28))
        assertFalse(passageCovers("not a reference", mat11v28))
    }

    @Test
    fun aPassageThatCoversAnExpectedVerseIsAHit() {
        val q = query(mat11v28)
        val result = KeyPassagesResult(q, listOf("PSA 23", "MAT 11:28-30"), ranked(q, VerseRef(19, 38, 10)))
        assertTrue(result.passageHit)
        assertTrue(result.hit)
        assertFalse(result.searchAlone.hit)
    }

    @Test
    fun passagesTakePlacesFromTheRankedVerses() {
        val q = query(mat11v28)
        // Expected verse ranked 9th: a hit alone, but 3 passages leave room for only 7 ranked verses.
        val others = (1..8).map { VerseRef(19, 38, it) }.toTypedArray()
        val search = ranked(q, *others, mat11v28)
        assertTrue(search.hit)
        assertFalse(KeyPassagesResult(q, listOf("PSA 23", "PSA 46", "ISA 41:10"), search).hit)
        assertTrue(KeyPassagesResult(q, emptyList(), search).hit)
    }
}
