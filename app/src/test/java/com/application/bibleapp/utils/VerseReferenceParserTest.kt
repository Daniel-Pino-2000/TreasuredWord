package com.application.bibleapp.utils

import com.application.bibleapp.data.model.VerseUI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerseReferenceParserTest {

    // A few of Twi (ASA) and Spanish book names, as a downloaded version stores them.
    private val twi = mapOf(43 to "Yohane", 62 to "1 Yohane", 1 to "Genesis")
    private val spanish = mapOf(1 to "Génesis", 43 to "Juan", 62 to "1 Juan", 19 to "Salmos")

    private fun parse(query: String, names: Map<Int, String> = emptyMap()) = parseVerseReference(query, names)

    @Test
    fun `English name with chapter and verse`() {
        assertEquals(VerseReference(43, 3, 16), parse("John 3:16"))
    }

    @Test
    fun `common abbreviation`() {
        assertEquals(VerseReference(43, 3, 16), parse("Jn 3:16"))
        assertEquals(VerseReference(40, 5, 3), parse("Mt 5.3"))
        assertEquals(VerseReference(50, 4, 13), parse("Phil 4:13"))
    }

    @Test
    fun `lowercase chapter only`() {
        assertEquals(VerseReference(43, 3), parse("john 3"))
    }

    @Test
    fun `verse range`() {
        assertEquals(VerseReference(43, 3, 16, 18), parse("John 3:16-18"))
        assertEquals(VerseReference(43, 3, 16, 18), parse("John 3:16–18"))
    }

    @Test
    fun `numbered books`() {
        assertEquals(VerseReference(62, 4, 8), parse("1 John 4:8"))
        assertEquals(VerseReference(62, 4, 8), parse("1john 4:8"))
        assertEquals(VerseReference(46, 13, 4), parse("1 Cor 13:4"))
    }

    @Test
    fun `the selected version's own book names`() {
        assertEquals(VerseReference(43, 3, 16), parse("Yohane 3:16", twi))
        assertEquals(VerseReference(62, 4, 8), parse("1 Yohane 4:8", twi))
        assertEquals(VerseReference(43, 3, 16), parse("juan 3:16", spanish))
    }

    @Test
    fun `accents don't matter`() {
        assertEquals(VerseReference(1, 1, 1), parse("Genesis 1:1", spanish))
        assertEquals(VerseReference(1, 1, 1), parse("génesis 1:1"))
    }

    @Test
    fun `English names still work with another version selected`() {
        assertEquals(VerseReference(43, 3, 16), parse("John 3:16", twi))
    }

    @Test
    fun `a unique start of a name works, an ambiguous one doesn't`() {
        assertEquals(VerseReference(1, 1), parse("Gen 1"))
        assertEquals(VerseReference(43, 1), parse("Yoh 1", twi))
        assertEquals(VerseReference(19, 23), parse("Sal 23", spanish))
        assertNull(parse("Jo 1")) // Job, Joel, John, Jonah, Joshua
        assertNull(parse("J 1"))
    }

    @Test
    fun `non-Latin book names`() {
        assertEquals(VerseReference(43, 3, 16), parse("约翰福音3:16", mapOf(43 to "约翰福音")))
        assertEquals(VerseReference(43, 3, 16), parse("요한복음 3:16", mapOf(43 to "요한복음")))
    }

    @Test
    fun `plain words and numbers are not references`() {
        assertNull(parse("love"))
        assertNull(parse("love one another"))
        assertNull(parse("love 3"))
        assertNull(parse("316"))
        assertNull(parse("John"))
        assertNull(parse("John 0"))
    }

    @Test
    fun `passage keeps only the requested verses, one result each`() {
        fun verse(number: Int, text: String) =
            VerseUI(id = number, text = text, bookId = 43, chapter = 3, verse = number)
        val chapter = listOf(
            verse(15, "That whosoever believeth"),
            verse(16, "¶ For God so loved the world,"),
            verse(16, "that he gave his only begotten Son"),
            verse(17, "For God sent not his Son"),
            verse(18, "He that believeth on him")
        )

        assertEquals(
            listOf("For God so loved the world, that he gave his only begotten Son"),
            chapter.passage(VerseReference(43, 3, 16)).map { it.text }
        )
        assertEquals(listOf(16, 17, 18), chapter.passage(VerseReference(43, 3, 16, 18)).map { it.verse })
        assertEquals(listOf(15, 16, 17, 18), chapter.passage(VerseReference(43, 3)).map { it.verse })
        assertEquals(emptyList<VerseUI>(), chapter.passage(VerseReference(43, 3, 40)))
    }
}
