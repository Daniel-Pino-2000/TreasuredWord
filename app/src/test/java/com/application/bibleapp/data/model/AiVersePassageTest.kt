package com.application.bibleapp.data.model

import com.application.bibleapp.utils.VerseReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A verse card's text comes from the selected translation, else the bundled KJV (#129). */
class AiVersePassageTest {

    private fun chapter(vararg verses: Pair<Int, String>) =
        verses.map { (number, text) -> VerseUI(id = number, text = text, bookId = 43, chapter = 3, verse = number) }

    private val kjv = chapter(15 to "That whosoever…", 16 to "For God so loved the world…", 17 to "For God sent not…")

    private fun chapters(selected: List<VerseUI>): suspend (String) -> List<VerseUI> = { versionId ->
        if (versionId == "kjv") kjv else selected
    }

    @Test
    fun `the selected translation's text when it has the verses`() = runTest {
        val spanish = chapter(16 to "¶ Porque de tal manera amó Dios al mundo…", 17 to "Porque no envió Dios…")

        val passage = loadAiVersePassage(VerseReference(43, 3, 16, 17), "spa", chapters(spanish))

        assertEquals(
            AiVersePassage("spa", listOf(PassageVerse(16, "Porque de tal manera amó Dios al mundo…"), PassageVerse(17, "Porque no envió Dios…"))),
            passage
        )
    }

    @Test
    fun `the KJV when the selected translation doesn't have the chapter`() = runTest {
        val passage = loadAiVersePassage(VerseReference(43, 3, 16), "nt_only", chapters(emptyList()))

        assertEquals(AiVersePassage("kjv", listOf(PassageVerse(16, "For God so loved the world…"))), passage)
    }

    @Test
    fun `the whole passage from the KJV when the selected translation lacks one verse`() = runTest {
        // A blank verse counts as missing, e.g. one a modern translation leaves out.
        val partial = chapter(16 to "Porque de tal manera…", 17 to "  ")

        val passage = loadAiVersePassage(VerseReference(43, 3, 16, 17), "spa", chapters(partial))

        assertEquals("kjv", passage!!.versionId)
        assertEquals(listOf(16, 17), passage.verses.map { it.number })
    }

    @Test
    fun `a whole chapter is every verse of it`() = runTest {
        val passage = loadAiVersePassage(VerseReference(43, 3), "kjv", chapters(emptyList()))

        assertEquals(listOf(15, 16, 17), passage!!.verses.map { it.number })
    }

    @Test
    fun `nothing when neither Bible has it`() = runTest {
        assertNull(loadAiVersePassage(VerseReference(43, 3, 40), "spa", chapters(emptyList())))
    }
}
