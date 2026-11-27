package com.application.bibleapp.data.repository

import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.data.remote.EsvBusyException
import com.application.bibleapp.data.remote.EsvSearchPage
import com.application.bibleapp.data.remote.EsvSearchResult
import com.application.bibleapp.data.remote.VerseRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word search in the ESV goes through Crossway's search, one page at a time (#377). Made-up text. */
class EsvWordSearchTest {

    private fun result(verse: Int) = EsvSearchResult(VerseRef(43, 1, verse), "Made-up verse $verse")

    private val saved = listOf(VerseUI(id = 1, text = "A saved verse", bookId = 19, chapter = 23, verse = 1))

    private fun search(online: Boolean = true, pages: suspend (Int) -> EsvSearchPage): Pair<EsvWordSearch, MutableList<Int>> {
        val asked = mutableListOf<Int>()
        return EsvWordSearch(
            searchPage = { _, page -> asked += page; pages(page) },
            searchSaved = { saved },
            isOnline = { online }
        ) to asked
    }

    @Test
    fun `the first page is shown, and the next asked for only when the list scrolls`() = runTest {
        val (search, asked) = search { page -> EsvSearchPage(listOf(result(page * 2 - 1), result(page * 2)), hasMore = page < 2) }

        val first = search.search("made")
        assertEquals(listOf(1, 2), first.verses.map { it.verse })
        assertEquals(listOf(1), asked)

        val second = first.loadMore!!.invoke()
        assertEquals(listOf(1, 2, 3, 4), second.verses.map { it.verse })
        assertNull(second.loadMore)
        assertEquals(listOf(1, 2), asked)
    }

    @Test
    fun `offline it searches the saved chapters and says so`() = runTest {
        val (search, asked) = search(online = false) { error("not asked") }

        val page = search.search("made")

        assertTrue(page.savedChaptersOnly)
        assertEquals(saved, page.verses)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `throttled, it falls back to the saved chapters`() = runTest {
        val (search, _) = search { throw EsvBusyException(30) }

        assertTrue(search.search("made").savedChaptersOnly)
    }

    @Test
    fun `a later page that fails offers Retry and keeps what's shown`() = runTest {
        val (search, _) = search { page -> if (page == 1) EsvSearchPage(listOf(result(1)), hasMore = true) else throw EsvBusyException(null) }

        val failed = search.search("made").loadMore!!.invoke()

        assertTrue(failed.moreFailed)
        assertEquals(listOf(1), failed.verses.map { it.verse })
        assertNotNull(failed.loadMore)
    }

    @Test
    fun `no more than a hundred results are listed`() = runTest {
        val (search, _) = search { page -> EsvSearchPage((1..20).map { result((page - 1) * 20 + it) }, hasMore = true) }

        var page = search.search("made")
        while (page.loadMore != null) page = page.loadMore!!.invoke()

        assertEquals(EsvWordSearch.MAX_RESULTS, page.verses.size)
        assertFalse(page.moreFailed)
    }
}
