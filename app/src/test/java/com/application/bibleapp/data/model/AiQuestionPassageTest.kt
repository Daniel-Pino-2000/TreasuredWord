package com.application.bibleapp.data.model

import com.application.bibleapp.data.remote.AiAskRequestDto
import com.application.bibleapp.data.remote.AiMessageDto
import com.application.bibleapp.data.remote.AiPassageDto
import com.application.bibleapp.data.remote.HttpClientProvider
import com.application.bibleapp.data.remote.VerseLocationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Ask about this verse" turning a reader selection into a passage (#132). */
class AiQuestionPassageTest {

    private val spanish = mapOf(43 to "Juan")
    private val text = mapOf(16 to "Porque de tal manera amó Dios al mundo", 17 to "Porque no envió Dios", 18 to "El que en él cree", 20 to "Porque todo aquel")

    private fun build(vararg verses: Int, textOf: (VerseLocationDto) -> String? = { text[it.verse] }) =
        buildAiQuestionPassage(verses.map { VerseLocationDto(43, 3, it) }, textOf, { spanish[it] ?: "?" }, "RV1909")

    @Test
    fun `one verse has no number in its text`() {
        assertEquals(
            AiQuestionPassage("Juan 3:16 (RV1909)", AiPassageDto("JHN 3:16", "Porque de tal manera amó Dios al mundo")),
            build(16)
        )
    }

    @Test
    fun `a selection in any order becomes runs, with numbered verses`() {
        val passage = build(20, 17, 16, 18)!!

        assertEquals("Juan 3:16-18, 20 (RV1909)", passage.label)
        assertEquals("JHN 3:16-18,20", passage.dto.reference)
        assertEquals("16 Porque de tal manera amó Dios al mundo 17 Porque no envió Dios 18 El que en él cree 20 Porque todo aquel", passage.dto.text)
    }

    @Test
    fun `verses without text are left out, and none at all is no passage`() {
        assertEquals("JHN 3:16,18", build(16, 17, 18) { if (it.verse == 17) "  " else text[it.verse] }!!.dto.reference)
        assertNull(build(16) { null })
        assertNull(buildAiQuestionPassage(emptyList(), { "x" }, { "x" }, "KJV"))
    }

    @Test
    fun `only the first verse's chapter counts`() {
        val selection = listOf(VerseLocationDto(43, 4, 1), VerseLocationDto(43, 3, 16))

        assertEquals("JHN 3:16", buildAiQuestionPassage(selection, { "text" }, { "Juan" }, "RV1909")!!.dto.reference)
    }

    @Test
    fun `a long passage is cut at a word to the contract's 2,000 characters`() {
        val passage = build(16, 17) { "palabra ".repeat(300) }!!

        assertTrue(passage.dto.text.length <= AI_PASSAGE_MAX_CHARS)
        assertTrue(passage.dto.text.endsWith("palabra…"))
    }

    @Test
    fun `a question without a passage leaves the field out of the JSON`() {
        val json = HttpClientProvider.json.encodeToString(AiAskRequestDto.serializer(), AiAskRequestDto(listOf(AiMessageDto("user", "Hi")), "en"))

        assertFalse("passage" in json)
    }
}
