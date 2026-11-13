package com.application.bibleapp.utils

import com.application.bibleapp.data.model.BibleBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Verse markers in a Bible Helper answer (#129, #190). */
class AiAnswerParserTest {

    private fun ref(bookId: Int, chapter: Int, start: Int? = null, end: Int? = null) =
        AiAnswerPart.Reference(VerseReference(bookId, chapter, start, end))

    private fun text(text: String) = AiAnswerPart.Text(text)

    private fun book(bookId: Int) = AiAnswerPart.Book(bookId)

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
    fun `broken brackets never show on screen`() {
        // The [[JHN]] in the middle is a whole book; the rest loses only its double brackets (#190).
        assertEquals(
            listOf(text("Look at JHN 3:16 and [ROM 8:28] or "), book(43), text(" or ROM 8:28]")),
            parseAiAnswer("Look at [[JHN 3:16 and [ROM 8:28] or [[JHN]] or [[ROM 8:28]")
        )
        assertEquals(listOf(text("Empty  here")), parseAiAnswer("Empty [[]] here"))
    }

    // The markers Gemma 4 and Qwen3 wrote in #122's test run (#190).

    @Test
    fun `a space after the number and JHN instead of 1JN`() {
        assertEquals(listOf(text("Confess "), ref(62, 1, 9)), parseAiAnswer("Confess [[1 JHN 1:9]]"))
    }

    @Test
    fun `a four-letter code`() {
        assertEquals(listOf(ref(26, 36, 26)), parseAiAnswer("[[EZEK 36:26]]"))
    }

    @Test
    fun `PHI for Philippians`() {
        assertEquals(listOf(ref(50, 4, 6, 7)), parseAiAnswer("[[PHI 4:6-7]]"))
    }

    @Test
    fun `full English names`() {
        assertEquals(listOf(ref(43, 5, 39)), parseAiAnswer("[[JOHN 5:39]]"))
        assertEquals(listOf(ref(19, 119, 105)), parseAiAnswer("[[PSALM 119:105]]"))
        assertEquals(listOf(ref(22, 2, 4)), parseAiAnswer("[[Song of Solomon 2:4]]"))
        assertEquals(listOf(ref(52, 4, 16, 17)), parseAiAnswer("[[1 Thess. 4:16-17]]"))
    }

    @Test
    fun `a whole book is its name, without a card`() {
        val parts = parseAiAnswer("The [[PSA]] are prayers.")

        assertEquals(listOf(text("The "), book(19), text(" are prayers.")), parts)
        assertEquals(emptyList<VerseReference>(), parts.citedReferences())
    }

    @Test
    fun `two markers in one`() {
        val parts = parseAiAnswer("See [[PSA 103:8], [1JN 1:9]].")

        assertEquals(listOf(text("See "), ref(19, 103, 8), text(", "), ref(62, 1, 9), text(".")), parts)
        assertEquals(listOf(VerseReference(19, 103, 8), VerseReference(62, 1, 9)), parts.citedReferences())
        assertEquals(
            listOf(ref(45, 3, 23), text("; "), ref(45, 6, 23)),
            parseAiAnswer("[[ROM 3:23; ROM 6:23]]")
        )
    }

    @Test
    fun `a marker of several with one unreadable part keeps the rest`() {
        assertEquals(
            listOf(ref(43, 3, 16), text(", XYZ 1:1")),
            parseAiAnswer("[[JHN 3:16], [XYZ 1:1]]")
        )
    }

    // A marker the model left open before the next one (#222).

    @Test
    fun `a marker left open before the next one is still a reference`() {
        val parts = parseAiAnswer("[[GEN 2:24], [[ROM 1:26-27]]")

        assertEquals(listOf(ref(1, 2, 24), text(", "), ref(45, 1, 26, 27)), parts)
        assertEquals(listOf(VerseReference(1, 2, 24), VerseReference(45, 1, 26, 27)), parts.citedReferences())
        assertEquals(listOf(ref(1, 2, 24), text("; "), ref(45, 1, 26, 27)), parseAiAnswer("[[GEN 2:24]; [[ROM 1:26-27]]"))
        assertEquals(listOf(ref(1, 2, 24), ref(45, 1, 26, 27)), parseAiAnswer("[[GEN 2:24][[ROM 1:26-27]]"))
        assertEquals(listOf(ref(19, 23), text(" "), book(43)), parseAiAnswer("[[PSA 23 [[JHN]]"))
    }

    @Test
    fun `the open markers Gemma 4 wrote in the eval`() {
        // LIFE-06 in the 2026-11-05 runs v3 and v2.
        val v3 = parseAiAnswer(
            "contrary to God's design for marriage [[GEN 2:24], [[ROM 1:26-27]], [[1CO 6:9-10]], the core"
        )
        assertEquals(
            listOf(
                text("contrary to God's design for marriage "), ref(1, 2, 24), text(", "),
                ref(45, 1, 26, 27), text(", "), ref(46, 6, 9, 10), text(", the core")
            ),
            v3
        )
        val v2 = parseAiAnswer("one man and one woman [[GEN 2:24], [[MAT 19:4-6]], we also teach")
        assertEquals(
            listOf(text("one man and one woman "), ref(1, 2, 24), text(", "), ref(40, 19, 4, 6), text(", we also teach")),
            v2
        )
        (v2 + v3).filterIsInstance<AiAnswerPart.Text>().forEach { part ->
            assertFalse(part.text, "[" in part.text || "]" in part.text || "GEN" in part.text)
        }
    }

    @Test
    fun `an open marker that isn't a real reference stays plain text`() {
        // As before #222: only the double brackets go.
        assertEquals(listOf(text("JHN 22:1], "), ref(43, 3, 16)), parseAiAnswer("[[JHN 22:1], [[JHN 3:16]]"))
        assertEquals(listOf(text("see the notes "), ref(43, 3, 16)), parseAiAnswer("[[see the notes [[JHN 3:16]]"))
        // Every passage must be real, or the whole open marker stays text.
        assertEquals(
            listOf(text("JHN 3:16, XYZ 1:1, "), ref(45, 3, 23)),
            parseAiAnswer("[[JHN 3:16, XYZ 1:1, [[ROM 3:23]]")
        )
    }

    @Test
    fun `a one-chapter book cited by verse alone`() {
        assertEquals(listOf(ref(65, 1, 24)), parseAiAnswer("[[JUD 24]]"))
        assertEquals(listOf(ref(65, 1)), parseAiAnswer("[[JUD 1]]"))
    }

    @Test
    fun `no answer shows raw double brackets`() {
        val markers = listOf(
            "[[1 JHN 1:9]]", "[[EZEK 36:26]]", "[[PSA]]", "[[PSA 103:8], [1JN 1:9]]", "[[PHI 4:6-7]]",
            "[[JOHN 5:39]]", "[[PSALM 119:105]]", "[[XYZ 1:1]]", "[[JHN 99:1]]", "[[not a verse]]", "[[JHN 3:16"
        )
        markers.forEach { marker ->
            val shown = parseAiAnswer("Before $marker after").filterIsInstance<AiAnswerPart.Text>().joinToString { it.text }
            assertFalse(marker, "[[" in shown || "]]" in shown)
        }
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

    @Test
    fun `plain text names books and references in the version's names`() {
        val spanish = mapOf(43 to "Juan", 19 to "Salmos")
        val name = { id: Int -> spanish[id] ?: BibleBooks.getBookById(id)!!.name }

        assertEquals(
            "Los Salmos y Juan 3:16-18, Juan 5:39.",
            parseAiAnswer("Los [[PSA]] y [[JHN 3:16-18], [JOHN 5:39]].").plainText(name)
        )
    }

    @Test
    fun `a marker still being written is left out until it's whole (#285)`() {
        assertEquals("See ", withoutUnfinishedMarker("See [[JHN 3:"))
        assertEquals("See ", withoutUnfinishedMarker("See [[JHN 3:16]"))
        assertEquals("See ", withoutUnfinishedMarker("See ["))
        assertEquals("See [[JHN 3:16]]", withoutUnfinishedMarker("See [[JHN 3:16]]"))
        assertEquals("See [[JHN 3:16]] and ", withoutUnfinishedMarker("See [[JHN 3:16]] and [[ROM"))
        assertEquals("No markers.", withoutUnfinishedMarker("No markers."))
    }
}
