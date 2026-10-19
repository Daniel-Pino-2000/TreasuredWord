package com.application.bibleapp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerseOfTheDayTest {

    private fun verse(number: Int) =
        VerseUI(id = number, text = "v$number text", bookId = 43, chapter = 3, verse = number)

    private val john3 = (14..23).map { verse(it) }

    @Test
    fun `single verse resolves to just its own text and a plain reference`() {
        val ref = DailyVerseRef(bookId = 43, chapter = 3, startVerse = 16, endVerse = null)

        val result = resolveDailyVerseUI("John", ref, john3)

        assertEquals("John 3:16", result?.reference)
        assertEquals("v16 text", result?.text)
    }

    @Test
    fun `verse range keeps every verse in order with its number and shows a dashed reference`() {
        val ref = DailyVerseRef(bookId = 43, chapter = 3, startVerse = 20, endVerse = 21)

        val result = resolveDailyVerseUI("John", ref, john3)

        assertEquals("John 3:20-21", result?.reference)
        assertEquals(listOf(DailyVerseLine(20, "v20 text"), DailyVerseLine(21, "v21 text")), result?.verses)
        assertTrue(result!!.hasVerseNumbers)
        assertEquals("²⁰ v20 text ²¹ v21 text", result.text)
    }

    @Test
    fun `a range still resolves correctly even if the chapter list is out of order`() {
        val ref = DailyVerseRef(bookId = 43, chapter = 3, startVerse = 20, endVerse = 22)

        val result = resolveDailyVerseUI("John", ref, john3.shuffled())

        assertEquals(listOf(20, 21, 22), result?.verses?.map { it.number })
    }

    @Test
    fun `a range where endVerse equals startVerse is treated as a single verse`() {
        val ref = DailyVerseRef(bookId = 43, chapter = 3, startVerse = 16, endVerse = 16)

        val result = resolveDailyVerseUI("John", ref, john3)

        assertEquals("John 3:16", result?.reference)
        assertEquals("v16 text", result?.text)
    }

    @Test
    fun `legacy paragraph marker is stripped from the verse text`() {
        val chapter = listOf(VerseUI(id = 1, text = "¶ v1 text", bookId = 43, chapter = 3, verse = 1))
        val ref = DailyVerseRef(bookId = 43, chapter = 3, startVerse = 1)

        assertEquals("v1 text", resolveDailyVerseUI("John", ref, chapter)?.text)
    }

    @Test
    fun `superscript conversion handles multi-digit verse numbers`() {
        assertEquals("¹⁰⁹", toSuperscript(109))
    }

    @Test
    fun `missing start verse in the currently selected translation resolves to null`() {
        // e.g. the daily reference is Old Testament but the active translation is NT-only.
        val ref = DailyVerseRef(bookId = 43, chapter = 3, startVerse = 99, endVerse = null)

        val result = resolveDailyVerseUI("John", ref, john3)

        assertNull(result)
    }
}
