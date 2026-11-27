package com.application.bibleapp.data.repository

import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.data.remote.EsvSearchPage
import kotlinx.coroutines.CancellationException

/**
 * Word search in the ESV (#377). The phone may keep only 500 ESV verses, so searching them finds
 * little; Crossway's search covers the whole ESV and sends each verse's text with it. That text is
 * shown, never saved, so it costs nothing against the 500-verse limit. One page (20 verses) is
 * asked for at a time, the next only when the list scrolls near its end, since every page is one of
 * the key's 5,000 queries a day; and no more than [MAX_RESULTS] are listed, well under the 500
 * verses Crossway lets one screen show.
 *
 * Offline, or when Crossway can't answer (throttled and the rest), it searches the saved chapters
 * instead and says so, as a YouVersion version's search does (#378).
 */
class EsvWordSearch(
    private val searchPage: suspend (query: String, page: Int) -> EsvSearchPage,
    private val searchSaved: suspend (query: String) -> List<VerseUI>,
    private val isOnline: () -> Boolean
) {
    suspend fun search(query: String): WordSearchPage {
        if (!isOnline()) return savedChapters(query)
        val first = try {
            searchPage(query, 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return savedChapters(query) // EsvBibleSource logs a 429
        }
        return Run(query).add(first, number = 1)
    }

    private suspend fun savedChapters(query: String) = WordSearchPage(searchSaved(query), savedChaptersOnly = true)

    private inner class Run(private val query: String) {
        private val shown = mutableListOf<VerseUI>()
        private val seen = mutableSetOf<Triple<Int, Int, Int>>() // the screen keys rows by reference: never twice
        private var lastPage = 0

        fun add(found: EsvSearchPage, number: Int): WordSearchPage {
            lastPage = number
            found.results.forEach { result ->
                if (shown.size < MAX_RESULTS && seen.add(Triple(result.ref.bookId, result.ref.chapter, result.ref.verse))) {
                    shown += VerseUI(id = null, text = result.text, bookId = result.ref.bookId, chapter = result.ref.chapter, verse = result.ref.verse)
                }
            }
            return page(more = found.hasMore && shown.size < MAX_RESULTS, failed = false)
        }

        private suspend fun next(): WordSearchPage = try {
            add(searchPage(query, lastPage + 1), lastPage + 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            page(more = true, failed = true)
        }

        private fun page(more: Boolean, failed: Boolean) = WordSearchPage(
            verses = shown.toList(),
            loadMore = if (more) ::next else null,
            moreFailed = failed
        )
    }

    companion object {
        /** At most five of Crossway's pages; its terms let a screen show up to 500 verses. */
        const val MAX_RESULTS = 100
    }
}
