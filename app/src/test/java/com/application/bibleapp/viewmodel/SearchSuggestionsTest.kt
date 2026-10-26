package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.TextDirection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSuggestionsTest {

    private fun translation(id: String, languageCode: String) = BibleTranslation(
        id = id,
        displayName = id,
        nativeName = id,
        languageName = languageCode,
        languageCode = languageCode,
        textDirection = TextDirection.LTR
    )

    private val catalog = listOf(translation("BSB", "eng"), translation("twi_asa", "twi"), translation("spa_pdt", "spa"))

    @Test
    fun `the bundled KJV gets suggestions, even before the catalog loads`() {
        assertTrue(searchSuggestionsFor("kjv", catalog).contains("love"))
        assertTrue(searchSuggestionsFor("kjv", emptyList()).isNotEmpty())
    }

    @Test
    fun `an English download gets suggestions`() {
        assertTrue(searchSuggestionsFor("BSB", catalog).isNotEmpty())
    }

    @Test
    fun `a version gets words in its own language`() {
        val spanish = searchSuggestionsFor("spa_pdt", catalog)
        assertTrue(spanish.contains("amor"))
        assertFalse(spanish.contains("love"))
    }

    @Test
    fun `Korean and Chinese versions get words in their own script`() {
        val catalog = listOf(translation("kor_old", "kor"), translation("cmn_cu1", "cmn"))
        assertTrue(searchSuggestionsFor("kor_old", catalog).contains("사랑"))
        // Both Simplified and Traditional; the filter keeps the ones the Bible actually uses.
        assertTrue(searchSuggestionsFor("cmn_cu1", catalog).containsAll(listOf("爱", "愛")))
    }

    @Test
    fun `a language without a list gets none`() {
        assertTrue(searchSuggestionsFor("twi_asa", catalog).isEmpty())
    }

    @Test
    fun `a version whose language is unknown gets none`() {
        assertTrue(searchSuggestionsFor("BSB", emptyList()).isEmpty())
    }

    @Test
    fun `only words that find something are kept, in order`() = runTest {
        val filter = SearchSuggestionFilter { word, _ -> word != "gozo" }
        assertEquals(listOf("amor", "paz"), filter.matching("spa_pdt", listOf("amor", "gozo", "paz")))
    }

    @Test
    fun `answers are remembered per version`() = runTest {
        var checks = 0
        val filter = SearchSuggestionFilter { word, version -> checks++; version == "spa_pdt" && word == "amor" }
        assertNull(filter.cached("spa_pdt", listOf("amor")))

        filter.matching("spa_pdt", listOf("amor", "paz"))
        assertEquals(listOf("amor"), filter.cached("spa_pdt", listOf("amor", "paz")))
        assertEquals(listOf("amor"), filter.matching("spa_pdt", listOf("amor", "paz")))
        assertEquals(2, checks)

        // Another version is checked separately.
        assertNull(filter.cached("spa_r09", listOf("amor")))
    }

    @Test
    fun `a failed check hides the word without remembering it`() = runTest {
        var fail = true
        val filter = SearchSuggestionFilter { _, _ -> if (fail) throw IllegalStateException("db") else true }
        assertTrue(filter.matching("spa_pdt", listOf("amor")).isEmpty())
        assertNull(filter.cached("spa_pdt", listOf("amor")))

        fail = false
        assertEquals(listOf("amor"), filter.matching("spa_pdt", listOf("amor")))
    }
}
