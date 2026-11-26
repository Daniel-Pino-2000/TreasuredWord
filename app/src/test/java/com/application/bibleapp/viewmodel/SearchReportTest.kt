package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.PassageVerse
import com.application.bibleapp.utils.VerseReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a search report sends (#402): only a finished search, as USFM references. */
class SearchReportTest {

    private val done = MeaningSearchState(
        query = "worried about my job",
        status = MeaningSearchStatus.DONE,
        results = listOf(
            MeaningVerse(40, 6, 34, "Take therefore no thought for the morrow", "kjv"),
            MeaningVerse(50, 4, 6, "Be careful for nothing", "kjv")
        ),
        keyPassages = listOf(
            KeyPassage(VerseReference(40, 6, 25, 34), listOf(PassageVerse(25, "…")), "kjv"),
            KeyPassage(VerseReference(19, 73), listOf(PassageVerse(1, "…")), "kjv")
        )
    )

    @Test
    fun `a finished search becomes a draft of references, without the verses' text`() {
        val request = done.reportDraft("BSB")!!.toRequest(language = "es", appVersion = "1.9")

        assertEquals("worried about my job", request.q)
        assertEquals("es", request.language)
        assertEquals("BSB", request.bibleVersion)
        assertEquals("1.9", request.appVersion)
        assertEquals(listOf("MAT 6:34", "PHP 4:6"), request.results)
        assertEquals(listOf("MAT 6:25-34", "PSA 73"), request.keyPassages)
    }

    @Test
    fun `a search that found nothing can still be reported`() {
        val request = done.copy(results = emptyList(), keyPassages = emptyList()).reportDraft("kjv")!!.toRequest("en", "1.9")

        assertEquals(emptyList<String>(), request.results)
        assertEquals(emptyList<String>(), request.keyPassages)
    }

    @Test
    fun `nothing to report until search by meaning has answered`() {
        for (status in MeaningSearchStatus.entries - MeaningSearchStatus.DONE) {
            assertNull(status.name, done.copy(status = status).reportDraft("kjv"))
        }
        assertNull(MeaningSearchState().reportDraft("kjv"))
    }

    @Test
    fun `the lists keep to the contract's size`() {
        val many = done.copy(results = List(25) { MeaningVerse(19, 119, it + 1, "…", "kjv") })

        assertEquals(20, many.reportDraft("kjv")!!.toRequest("en", "1.9").results.size)
    }

    @Test
    fun `references are written as the server's key passages are`() {
        assertEquals("1CO 13:4", usfmReference(VerseReference(46, 13, 4)))
        assertEquals("PSA 23", usfmReference(VerseReference(19, 23)))
        assertNull(usfmReference(VerseReference(67, 1, 1)))
    }
}
