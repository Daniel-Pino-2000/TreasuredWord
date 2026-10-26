package com.application.bibleapp.utils

import com.application.bibleapp.utils.TextUtils.normalizeForSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {

    /** What search does: every word of the query must appear in the verse, both normalized. */
    private fun finds(query: String, verse: String): Boolean {
        val text = verse.normalizeForSearch()
        return query.normalizeForSearch().split(" ").all { it in text }
    }

    @Test
    fun `accents are removed in every Latin language, not just Spanish`() {
        assertEquals("graca esperanca perdao", "graça esperança perdão".normalizeForSearch())
        assertEquals("grace esperance noel", "grâce espérance Noël".normalizeForSearch())
        assertEquals("perdon nino pinguino", "perdón niño pingüino".normalizeForSearch())
    }

    @Test
    fun `capitals are lowercased, accented ones too`() {
        assertEquals("a ete ca", "À ÉTÉ ÇA".normalizeForSearch())
        assertEquals("liebe", "LIEBE".normalizeForSearch())
    }

    @Test
    fun `punctuation becomes single spaces`() {
        assertEquals("the lord s word", "  The LORD’s – word!  ".normalizeForSearch())
        assertEquals("pag ibig", "pag-ibig".normalizeForSearch())
    }

    @Test
    fun `non-Latin scripts are kept`() {
        assertEquals("사랑", "사랑".normalizeForSearch())
        assertEquals("神爱世人 甚至", "神爱世人，甚至".normalizeForSearch())
        assertEquals("любовь", "Любовь".normalizeForSearch())
    }

    @Test
    fun `Greek capitals match lowercase words ending in a final sigma`() {
        assertEquals("λογοσ", "ΛΟΓΟΣ".normalizeForSearch())
        assertEquals("λογοσ", "λόγος".normalizeForSearch())
    }

    @Test
    fun `Hangul stays whole syllables, so one doesn't match inside another`() {
        assertEquals("사랑".length, "사랑".normalizeForSearch().length)
        assertFalse(finds("사", "삭개오"))
    }

    @Test
    fun `the issue's examples find their verses`() {
        assertTrue(finds("graça", "Graça e paz vos sejam multiplicadas"))
        assertTrue(finds("perdão", "o PERDÃO dos pecados"))
        assertTrue(finds("grâce", "Que la grâce et la paix vous soient multipliées"))
        assertTrue(finds("grace", "À toi Grâce"))
        assertTrue(finds("사랑", "하나님이 세상을 이처럼 사랑하사"))
        assertTrue(finds("爱", "神爱世人，甚至将他的独生子赐给他们"))
    }

    @Test
    fun `English and Spanish searches still match as before`() {
        assertTrue(finds("lord's house", "the LORD’s house"))
        assertTrue(finds("perdon", "perdón"))
        assertTrue(finds("perdón", "Perdon"))
        assertFalse(finds("gods", "God’s"))
    }

    @Test
    fun `a query of only punctuation is empty`() {
        assertEquals("", "?!…".normalizeForSearch())
    }
}
