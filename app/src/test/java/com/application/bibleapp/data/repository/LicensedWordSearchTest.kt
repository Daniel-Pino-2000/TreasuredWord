package com.application.bibleapp.data.repository

import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.data.remote.VerseRef
import com.application.bibleapp.data.remote.YouVersionBusyException
import com.application.bibleapp.data.remote.YouVersionSearchPage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word search in a licensed version through YouVersion (#382). Verse text here is made up, never
 * licensed Scripture (CLAUDE.md): each verse is "word<chapter>_<verse>", plus "amor" where a test
 * wants a match.
 */
class LicensedWordSearchTest {

    private val pages = mutableListOf<YouVersionSearchPage>()
    private val pageRequests = mutableListOf<String?>()
    private val chapterRequests = mutableListOf<Pair<Int, Int>>()
    private val saved = mutableSetOf<Pair<Int, Int>>()
    private val busyChapters = mutableSetOf<Pair<Int, Int>>()
    private var online = true
    private var searchFails: Exception? = null
    /** Verses whose text has the word "amor"; null: every verse has it. */
    private var matching: Set<VerseRef>? = null
    private val savedSearch = listOf(verse(VerseRef(1, 1, 1), "amor guardado"))

    private fun verse(ref: VerseRef, text: String) =
        VerseUI(id = null, text = text, bookId = ref.bookId, chapter = ref.chapter, verse = ref.verse)

    private fun chapterText(bookId: Int, chapter: Int) = (1..30).map { v ->
        val ref = VerseRef(bookId, chapter, v)
        verse(ref, if (matching == null || ref in matching!!) "el amor $chapter $v" else "otra cosa $chapter $v")
    }

    private val search = LicensedWordSearch(
        searchPage = { _, token ->
            pageRequests += token
            searchFails?.let { throw it }
            pages.removeAt(0)
        },
        loadChapter = { bookId, chapter ->
            val key = bookId to chapter
            when {
                key in saved -> ChapterLoad(chapterText(bookId, chapter))
                key in busyChapters -> ChapterLoad(emptyList(), ChapterProblem.BUSY)
                else -> {
                    chapterRequests += key
                    saved += key
                    ChapterLoad(chapterText(bookId, chapter))
                }
            }
        },
        searchSaved = { savedSearch },
        isOnline = { online }
    )

    private fun page(vararg refs: VerseRef, next: String? = null, insteadFor: String? = null) =
        YouVersionSearchPage(refs.toList(), insteadFor, next)

    private fun refs(count: Int, start: Int = 1) = (start until start + count).map { VerseRef(43, it, 1) }.toTypedArray()

    @Test
    fun `finds verses in chapters never opened, with their text, in YouVersion's order`() = runTest {
        pages += page(VerseRef(62, 4, 8), VerseRef(43, 3, 16))

        val result = search.search("amor")

        assertEquals(listOf(VerseRef(62, 4, 8), VerseRef(43, 3, 16)), result.verses.map { VerseRef(it.bookId!!, it.chapter!!, it.verse!!) })
        assertEquals("el amor 4 8", result.verses.first().text)
        assertFalse(result.savedChaptersOnly)
        assertNull(result.loadMore)
    }

    @Test
    fun `a saved chapter costs no request, and one chapter is fetched once for many verses`() = runTest {
        saved += 43 to 3
        pages += page(VerseRef(43, 3, 16), VerseRef(43, 3, 17), VerseRef(62, 4, 8), VerseRef(62, 4, 16))

        search.search("amor")

        assertEquals(listOf(62 to 4), chapterRequests)
    }

    @Test
    fun `only the first batch is resolved, the rest as the list scrolls`() = runTest {
        pages += page(*refs(20))

        val first = search.search("amor")
        assertEquals(LicensedWordSearch.BATCH_SIZE, first.verses.size)
        assertEquals(LicensedWordSearch.BATCH_SIZE, chapterRequests.size)

        val second = first.loadMore!!()
        assertEquals(2 * LicensedWordSearch.BATCH_SIZE, second.verses.size)
        val third = second.loadMore!!()
        assertEquals(20, third.verses.size)
        assertNull(third.loadMore)
    }

    @Test
    fun `the next page is asked for only when the batch needs it`() = runTest {
        pages += page(*refs(10), next = "p2")
        pages += page(*refs(10, start = 11))

        val first = search.search("amor")
        assertEquals(listOf<String?>(null), pageRequests)

        first.loadMore!!()
        assertEquals(listOf(null, "p2"), pageRequests)
    }

    @Test
    fun `a verse on two pages is listed once`() = runTest {
        pages += page(VerseRef(43, 3, 16), next = "p2")
        pages += page(VerseRef(43, 3, 16), VerseRef(43, 3, 17))

        val result = search.search("amor")

        assertEquals(listOf(16, 17), result.verses.map { it.verse })
    }

    @Test
    fun `verses that only relate to the words are left out, and a batch of only those ends the list`() = runTest {
        matching = setOf(VerseRef(58, 7, 1), VerseRef(58, 7, 17))
        val related = (2..16).map { VerseRef(58, 7, it) }
        pages += page(VerseRef(58, 7, 1), VerseRef(58, 7, 17), *related.toTypedArray(), next = "p2")

        var result = search.search("amor")
        while (result.loadMore != null) result = result.loadMore!!()

        assertEquals(listOf(1, 17), result.verses.map { it.verse })
        assertEquals(listOf<String?>(null), pageRequests) // never asked for page 2
    }

    @Test
    fun `a corrected spelling keeps every result and says so`() = runTest {
        matching = emptySet()
        pages += page(VerseRef(43, 3, 16), insteadFor = "amorr")

        val result = search.search("amorr")

        assertEquals(1, result.verses.size)
        assertEquals("amorr", result.closeMatchesFor)
    }

    @Test
    fun `offline it searches the saved chapters and says so`() = runTest {
        online = false

        val result = search.search("amor")

        assertEquals(savedSearch, result.verses)
        assertTrue(result.savedChaptersOnly)
        assertTrue(pageRequests.isEmpty())
    }

    @Test
    fun `too many requests falls back to the saved chapters`() = runTest {
        searchFails = YouVersionBusyException(retryAfterSeconds = 30)

        val result = search.search("amor")

        assertEquals(savedSearch, result.verses)
        assertTrue(result.savedChaptersOnly)
    }

    @Test
    fun `when not even the first chapter loads, the saved chapters are searched`() = runTest {
        busyChapters += 43 to 3
        pages += page(VerseRef(43, 3, 16))

        val result = search.search("amor")

        assertTrue(result.savedChaptersOnly)
    }

    @Test
    fun `a chapter that can't load later keeps what was found and retries from there`() = runTest {
        pages += page(*refs(12))
        busyChapters += 43 to 10

        val first = search.search("amor")
        val failed = first.loadMore!!()
        assertEquals(9, failed.verses.size) // chapter 9, then 10 is busy
        assertTrue(failed.moreFailed)
        assertNotNull(failed.loadMore)

        busyChapters.clear()
        val retried = failed.loadMore!!()
        assertEquals(12, retried.verses.size)
        assertFalse(retried.moreFailed)
        assertNull(retried.loadMore)
    }
}
