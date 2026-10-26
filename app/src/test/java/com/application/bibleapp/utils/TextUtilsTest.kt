package com.application.bibleapp.utils

import com.application.bibleapp.utils.TextUtils.isWrittenWithoutSpaces
import com.application.bibleapp.utils.TextUtils.normalizeForSearch
import com.application.bibleapp.utils.TextUtils.searchPattern
import com.application.bibleapp.utils.TextUtils.wholeWordPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {

    /**
     * What search does: every word of the normalized query must match the normalized verse, with
     * a space on each side, by its LIKE pattern. Patterns are always "%...%", so LIKE is a
     * substring test.
     */
    private fun finds(query: String, verse: String, pattern: (String) -> String = { it.searchPattern() }): Boolean {
        val text = " " + verse.normalizeForSearch() + " "
        return query.normalizeForSearch().split(" ").all { text.contains(pattern(it).removeSurrounding("%")) }
    }

    /** Whether the verse would be listed among the first results, with every word whole. */
    private fun ranksFirst(query: String, verse: String) = finds(query, verse) { it.wholeWordPattern() }

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
    fun `a word matches only at the start of a word`() {
        assertFalse(finds("paz", "um rapaz capaz"))
        assertTrue(finds("amor", "O amor é paciente"))
        assertTrue(finds("amor", "Amor e fidelidade"))
        assertTrue(finds("amor", "fidelidade e amor"))
        assertTrue(finds("amor", "os seus amores"))
        assertFalse(finds("love", "my beloved son"))
        assertFalse(finds("love", "cloven"))
        assertFalse(finds("joy", "enjoy the land"))
        assertTrue(finds("love", "God so loved the world"))
    }

    @Test
    fun `verses with the whole word come before words that only start with it, so love before the Amorites`() {
        assertTrue(finds("amor", "a terra dos amorreus"))
        assertFalse(ranksFirst("amor", "a terra dos amorreus"))
        assertTrue(ranksFirst("amor", "O amor é paciente"))
        assertTrue(ranksFirst("amor", "fidelidade e amor."))
        assertFalse(ranksFirst("love", "God so loved the world"))
        assertTrue(ranksFirst("lord's house", "the LORD’s house"))
        assertFalse(ranksFirst("lord house", "the LORD’s household"))
        assertTrue(ranksFirst("爱", "神爱世人"))
        assertEquals("% amor %", "amor".wholeWordPattern())
    }

    @Test
    fun `Korean matches the stem with its endings`() {
        assertTrue(finds("사랑", "하나님이 세상을 이처럼 사랑하사"))
        assertTrue(finds("사랑", "사랑하는 자들아"))
    }

    @Test
    fun `scripts without spaces still match inside a run of text`() {
        assertTrue(finds("爱", "神爱世人，甚至将他的独生子赐给他们"))
        assertTrue(finds("世人", "神爱世人"))
        assertTrue(finds("รัก", "พระเจ้าทรงรักโลก"))
        assertTrue("爱".isWrittenWithoutSpaces())
        assertTrue("ความรัก".isWrittenWithoutSpaces())
        assertFalse("사랑".isWrittenWithoutSpaces())
        assertFalse("love".isWrittenWithoutSpaces())
        assertEquals("%爱%", "爱".searchPattern())
        assertEquals("% amor%", "amor".searchPattern())
    }

    @Test
    fun `Traditional Chinese is folded to Simplified`() {
        assertEquals("爱", "愛".normalizeForSearch())
        assertEquals("喜乐", "喜樂".normalizeForSearch())
        assertEquals("神爱世人", "神愛世人".normalizeForSearch())
        // Already Simplified, and characters both scripts share, stay as they are.
        assertEquals("神爱世人", "神爱世人".normalizeForSearch())
    }

    @Test
    fun `characters with more than one Traditional form all fold to the same one`() {
        assertEquals("后", "後".normalizeForSearch())
        assertEquals("后", "后".normalizeForSearch())
        assertEquals("发", "發".normalizeForSearch())
        assertEquals("发", "髮".normalizeForSearch())
        assertTrue(finds("後來", "后来他们"))
        assertTrue(finds("头发", "頭髮"))
    }

    @Test
    fun `Simplified and Traditional queries find either script's verses`() {
        assertTrue(finds("爱", "神愛世人，甚至將他的獨生子賜給他們"))
        assertTrue(finds("愛", "神爱世人，甚至将他的独生子赐给他们"))
        assertTrue(finds("喜乐", "你們要靠主常常喜樂"))
        assertTrue(finds("喜樂", "你们要靠主常常喜乐"))
    }

    @Test
    fun `a query of only punctuation is empty`() {
        assertEquals("", "?!…".normalizeForSearch())
    }
}
