package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.TextDirection
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
    fun `a version in another language gets none`() {
        assertTrue(searchSuggestionsFor("twi_asa", catalog).isEmpty())
        assertTrue(searchSuggestionsFor("spa_pdt", catalog).isEmpty())
    }

    @Test
    fun `a version whose language is unknown gets none`() {
        assertTrue(searchSuggestionsFor("BSB", emptyList()).isEmpty())
    }
}
