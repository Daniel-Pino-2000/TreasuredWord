package com.application.bibleapp.utils

import com.application.bibleapp.data.model.BibleBooks
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verse markers in a Bible Helper answer (#129). */
class AiAnswerParserTest {

    private fun ref(bookId: Int, chapter: Int, start: Int? = null, end: Int? = null) =
        AiAnswerPart.Reference(VerseReference(bookId, chapter, start, end))

    private fun text(text: String) = AiAnswerPart.Text(text)

    @Test
    fun `a single verse`() {
        assertEquals(
            listOf(text("God loved the world "), ref(43, 3, 16), text(".")),
            parseAiAnswer("God loved the world [[JHN 3:16]].")
        )
    }

    @Test
    fun `a range`() {
        assertEquals(listOf(ref(43, 3, 16, 18)), parseAiAnswer("[[JHN 3:16-18]]"))
    }

    @Test
    fun `a range written with an en dash or spaces`() {
        assertEquals(listOf(ref(43, 3, 16, 18)), parseAiAnswer("[[ JHN 3:16 – 18 ]]"))
    }

    @Test
    fun `a whole chapter`() {
        assertEquals(listOf(text("Read "), ref(19, 23)), parseAiAnswer("Read [[PSA 23]]"))
    }

    @Test
    fun `a lowercase code`() {
        assertEquals(listOf(ref(62, 5, 11, 13)), parseAiAnswer("[[1jn 5:11-13]]"))
    }

    @Test
    fun `a range of one verse is a single verse`() {
        assertEquals(listOf(ref(43, 3, 16)), parseAiAnswer("[[JHN 3:16-16]]"))
    }

    @Test
    fun `an unknown code becomes plain text`() {
        assertEquals(listOf(text("See XYZ 1:1 here.")), parseAiAnswer("See [[XYZ 1:1]] here."))
    }

    @Test
    fun `chapter 0 or a chapter past the book's end becomes plain text`() {
        assertEquals(listOf(text("JHN 0:1")), parseAiAnswer("[[JHN 0:1]]"))
        assertEquals(listOf(text("JHN 22:1")), parseAiAnswer("[[JHN 22:1]]"))
    }

    @Test
    fun `a verse past the chapter's end becomes plain text`() {
        // John 3 has 36 verses.
        assertEquals(listOf(text("JHN 3:37")), parseAiAnswer("[[JHN 3:37]]"))
        assertEquals(listOf(text("JHN 3:30-40")), parseAiAnswer("[[JHN 3:30-40]]"))
        assertEquals(listOf(text("JHN 3:0")), parseAiAnswer("[[JHN 3:0]]"))
    }

    @Test
    fun `a backwards range becomes plain text`() {
        assertEquals(listOf(text("JHN 3:18-16")), parseAiAnswer("[[JHN 3:18-16]]"))
    }

    @Test
    fun `a verse the selected translation numbers further than the KJV is kept`() {
        // Malachi 3 has 18 verses in the KJV; translations that follow the Hebrew numbering have 24.
        val structure = mapOf(39 to mapOf(3 to 24))

        assertEquals(listOf(ref(39, 3, 24)), parseAiAnswer("[[MAL 3:24]]", structure))
        assertEquals(listOf(text("MAL 3:24")), parseAiAnswer("[[MAL 3:24]]"))
    }

    @Test
    fun `broken brackets stay as they are`() {
        val answer = "Look at [[JHN 3:16 and [ROM 8:28] or [[JHN]] or [[ROM 8:28]"

        assertEquals(listOf(text(answer)), parseAiAnswer(answer))
    }

    @Test
    fun `two references in one sentence`() {
        assertEquals(
            listOf(text("Compare "), ref(45, 3, 23), text(" with "), ref(49, 2, 8, 9), text(".")),
            parseAiAnswer("Compare [[ROM 3:23]] with [[EPH 2:8-9]].")
        )
    }

    @Test
    fun `an answer without markers is one piece of text`() {
        assertEquals(listOf(text("Grace and peace.")), parseAiAnswer("Grace and peace."))
        assertEquals(emptyList<AiAnswerPart>(), parseAiAnswer(""))
    }

    @Test
    fun `each cited passage gets one card, in order of first mention`() {
        val parts = parseAiAnswer("[[ROM 3:23]], [[EPH 2:8-9]], [[ROM 3:23]] and [[XYZ 1:1]]")

        assertEquals(
            listOf(VerseReference(45, 3, 23), VerseReference(49, 2, 8, 9)),
            parts.citedReferences()
        )
    }

    @Test
    fun `a reference reads in the version's book names`() {
        val spanish = mapOf(43 to "Juan", 19 to "Salmos")
        val name = { id: Int -> spanish[id] ?: BibleBooks.getBookById(id)!!.name }

        assertEquals("Juan 3:16", formatVerseReference(VerseReference(43, 3, 16), name))
        assertEquals("Juan 3:16-18", formatVerseReference(VerseReference(43, 3, 16, 18), name))
        assertEquals("Salmos 23", formatVerseReference(VerseReference(19, 23), name))
    }
}
