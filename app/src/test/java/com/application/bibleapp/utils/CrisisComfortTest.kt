package com.application.bibleapp.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which verse of comfort the crisis help card shows (#282). */
class CrisisComfortTest {

    private val psalm34 = VerseReference(19, 34, 18)
    private val isaiah41 = VerseReference(23, 41, 10)
    private val romans8 = VerseReference(45, 8, 38, 39)
    private val john316 = VerseReference(43, 3, 16)

    @Test
    fun `the verse the answer chose from the list`() {
        assertEquals(isaiah41, crisisComfortVerse(listOf(john316, isaiah41, romans8)))
        assertEquals(romans8, crisisComfortVerse(listOf(romans8)))
    }

    @Test
    fun `Psalm 34-18 when the answer cites none from the list, or nothing`() {
        assertEquals(psalm34, crisisComfortVerse(listOf(john316)))
        assertEquals(psalm34, crisisComfortVerse(emptyList()))
    }

    @Test
    fun `the list reads like the AI's markers, so a cited verse matches`() {
        // [[ISA 41:10]] and [[ROM 8:38-39]] as the answer parser reads them.
        val cited = parseAiAnswer("Fear not [[ISA 41:10]]. Nothing can separate you [[ROM 8:38-39]].").citedReferences()
        assertEquals(listOf(isaiah41, romans8), cited)
        assertEquals(isaiah41, crisisComfortVerse(cited))
    }

    @Test
    fun `every verse on the list is one the app can show`() {
        for (verse in CRISIS_COMFORT_VERSES) {
            val usfm = "${com.application.bibleapp.data.model.UsfmBookCodes.code(verse.bookId)} ${verse.chapter}:${verse.startVerse}" +
                (verse.endVerse?.let { "-$it" } ?: "")
            assertEquals(usfm, listOf(verse), parseAiAnswer("[[$usfm]]").citedReferences())
        }
    }
}
