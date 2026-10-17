package com.application.bibleapp.utils

import com.application.bibleapp.data.remote.VerseLocationDto
import org.junit.Assert.assertEquals
import org.junit.Test

class VerseRefFormatTest {

    private val names = mapOf(42 to "Lucas", 43 to "Juan")
    private val bookName = { id: Int -> names.getValue(id) }
    private fun v(book: Int, chapter: Int, verse: Int) = VerseLocationDto(book, chapter, verse)

    @Test
    fun `single verse`() {
        assertEquals("Lucas 12:4", formatFullVerseRefs(listOf(v(42, 12, 4)), bookName))
    }

    @Test
    fun `consecutive verses collapse into a range regardless of input order`() {
        assertEquals("Lucas 12:4-6", formatFullVerseRefs(listOf(v(42, 12, 6), v(42, 12, 4), v(42, 12, 5)), bookName))
    }

    @Test
    fun `scattered verses list every run`() {
        assertEquals(
            "Lucas 12:4-6, 9",
            formatFullVerseRefs(listOf(v(42, 12, 4), v(42, 12, 5), v(42, 12, 6), v(42, 12, 9)), bookName)
        )
    }

    @Test
    fun `chapter and book changes restart the reference`() {
        assertEquals(
            "Lucas 12:40; 13:1; Juan 3:16",
            formatFullVerseRefs(listOf(v(43, 3, 16), v(42, 13, 1), v(42, 12, 40)), bookName)
        )
    }

    @Test
    fun `share text numbers verses only when there are several`() {
        val text = mapOf(v(42, 12, 4) to "Les digo.", v(42, 12, 5) to "Les advertiré.")
        assertEquals(
            "\"Les digo.\"\n— Lucas 12:4 (PDT)",
            buildVerseShareText(listOf(v(42, 12, 4)), text::get, bookName, "PDT")
        )
        assertEquals(
            "\"4 Les digo. 5 Les advertiré.\"\n— Lucas 12:4-5 (PDT)",
            buildVerseShareText(listOf(v(42, 12, 5), v(42, 12, 4)), text::get, bookName, "PDT")
        )
    }
}
