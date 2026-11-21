package com.application.bibleapp.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** YouVersion's verse search (#382), on Ktor's MockEngine. No Scripture text: the API sends references only. */
class YouVersionSearchTest {

    private val sent = mutableListOf<HttpRequestData>()

    private fun source(body: String) = YouVersionBibleSource(
        appKey = "test-key",
        baseUrl = "https://yv.test",
        client = HttpClient(MockEngine { request ->
            sent += request
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(HttpTimeout) }
    )

    @Test
    fun `parses references, the corrected spelling and the next page`() = runTest {
        val page = source(
            """{"verses":[{"reference":"JHN.3.16"},{"reference":"1JN.4.8"},{"reference":"JHN.3.16-17"},{"reference":"XYZ.1.1"}],
               "user_intent":"text","did_you_mean":[],"search_instead_for":"lvoe","next_page_token":"abc+/="}"""
        ).searchVerses(yvId = 89, query = "lvoe")

        // A range or an unknown book can't be one result row, so it's left out.
        assertEquals(listOf(VerseRef(43, 3, 16), VerseRef(62, 4, 8)), page.refs)
        assertEquals("lvoe", page.searchInsteadFor)
        assertEquals("abc+/=", page.nextPageToken)
    }

    @Test
    fun `the last page has no token`() = runTest {
        val page = source("""{"verses":[],"search_instead_for":null,"next_page_token":null}""").searchVerses(89, "zzqxv")
        assertEquals(emptyList<VerseRef>(), page.refs)
        assertNull(page.searchInsteadFor)
        assertNull(page.nextPageToken)
    }

    @Test
    fun `sends the key, the version, the words and the page token, encoded`() = runTest {
        source("""{"verses":[]}""").searchVerses(yvId = 103, query = "amor de Dios & paz", pageToken = "a+b/c=")

        val request = sent.single()
        assertEquals("test-key", request.headers["X-YVP-App-Key"])
        assertEquals("/v1/search-verses", request.url.encodedPath)
        val params = request.url.parameters
        assertEquals("103", params["bible_id"])
        assertEquals("amor de Dios & paz", params["query"])
        assertEquals("text", params["user_intent"])
        assertEquals("25", params["page_size"])
        assertEquals("a+b/c=", params["page_token"])
    }

    @Test
    fun `a query over the API's 100 characters is cut`() = runTest {
        source("""{"verses":[]}""").searchVerses(89, "a".repeat(150))
        assertEquals(100, sent.single().url.parameters["query"]?.length)
    }

    @Test
    fun `parseUsfmVerse takes only single verses of the 66 books`() {
        assertEquals(VerseRef(19, 23, 1), parseUsfmVerse("PSA.23.1"))
        assertEquals(VerseRef(1, 1, 1), parseUsfmVerse("gen.1.1"))
        assertNull(parseUsfmVerse("PSA.23"))
        assertNull(parseUsfmVerse("PSA.23.0"))
        assertNull(parseUsfmVerse("PSA.INTRO.1"))
        assertNull(parseUsfmVerse("TOB.1.1"))
    }
}
