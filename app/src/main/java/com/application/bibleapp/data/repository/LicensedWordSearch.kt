package com.application.bibleapp.data.repository

import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.data.remote.VerseRef
import com.application.bibleapp.data.remote.YouVersionSearchPage
import com.application.bibleapp.utils.TextUtils.matchesSearchWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * What one word search found so far (#382). [verses] are all its results so far, in order.
 * [loadMore] finds the next ones (null when there are none); [moreFailed] says the last try
 * couldn't, so the screen offers Retry, which calls [loadMore] again. [savedChaptersOnly] is a
 * licensed version searched without YouVersion (offline, or too many requests): only the chapters
 * already on the phone (#378). [closeMatchesFor] is the query as typed when YouVersion searched a
 * corrected spelling instead.
 */
data class WordSearchPage(
    val verses: List<VerseUI>,
    val loadMore: (suspend () -> WordSearchPage)? = null,
    val moreFailed: Boolean = false,
    val savedChaptersOnly: Boolean = false,
    val closeMatchesFor: String? = null
)

/**
 * Word search in a licensed version (#382). These are never downloaded whole (#366), so the phone
 * only has the chapters already read; YouVersion's verse search covers the whole version, but
 * answers with references alone. Each result's text comes from its chapter: from the phone when
 * it's saved, else fetched and saved the way the reader does it ([loadChapter]), so the next search
 * or read of it costs nothing.
 *
 * To keep requests to YouVersion's per-key limit low, results are resolved [BATCH_SIZE] at a time,
 * the next batch only when the list scrolls near its end, and a batch's missing chapters at most
 * [PARALLEL_CHAPTERS] at once.
 *
 * YouVersion ranks the verses that have the words first, then verses that only relate to them
 * ("Melquisedec": its ten verses, then the rest of Hebrews 7). Only the first kind is kept, matched
 * as the phone's own search matches; a batch with none of them ends the list. A corrected spelling
 * can't be matched that way, so its results are all kept, up to [MAX_RESULTS] as on the phone.
 *
 * Offline, or when YouVersion can't answer (HTTP 429 and the rest), it searches the saved
 * chapters instead ([searchSaved]) and says so (#378).
 */
class LicensedWordSearch(
    private val searchPage: suspend (query: String, pageToken: String?) -> YouVersionSearchPage,
    private val loadChapter: suspend (bookId: Int, chapter: Int) -> ChapterLoad,
    private val searchSaved: suspend (query: String) -> List<VerseUI>,
    private val isOnline: () -> Boolean
) {
    suspend fun search(query: String): WordSearchPage {
        if (!isOnline()) return savedChapters(query)
        val first = try {
            searchPage(query, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return savedChapters(query) // YouVersionBibleSource logs a 429
        }
        val page = Run(query, first).next()
        // Not even the first verse's chapter could be fetched: the saved chapters are better than nothing.
        return if (page.verses.isEmpty() && page.moreFailed) savedChapters(query) else page
    }

    private suspend fun savedChapters(query: String) = WordSearchPage(searchSaved(query), savedChaptersOnly = true)

    /** One search's paging state: the references still to resolve, YouVersion's next page, what's shown. */
    private inner class Run(private val query: String, first: YouVersionSearchPage) {
        private val closeMatchesFor = first.searchInsteadFor
        private val queue = ArrayDeque<VerseRef>()
        private val queued = mutableSetOf<VerseRef>() // the screen keys rows by reference: never twice
        private var pageToken = first.nextPageToken
        private val shown = mutableListOf<VerseUI>()
        private val chapters = mutableMapOf<Pair<Int, Int>, List<VerseUI>>()
        private var ended = false

        init {
            enqueue(first.refs)
        }

        private fun enqueue(refs: List<VerseRef>) = refs.forEach { if (queued.add(it)) queue.addLast(it) }

        suspend fun next(): WordSearchPage {
            try {
                while (queue.size < BATCH_SIZE && pageToken != null) {
                    val page = searchPage(query, pageToken)
                    pageToken = page.nextPageToken
                    enqueue(page.refs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return page(failed = true)
            }

            val batch = queue.take(BATCH_SIZE)
            val failed = loadChapters(batch)
            var resolved = 0
            var kept = 0
            for (ref in batch) {
                val verses = chapters[ref.bookId to ref.chapter] ?: break // its chapter couldn't load: retry from here
                resolved++
                val verse = verses.firstOrNull { it.verse == ref.verse } ?: continue
                if (closeMatchesFor == null && !matchesSearchWords(verse.text, query)) continue
                shown += verse.copy(richContent = null)
                kept++
            }
            repeat(resolved) { queue.removeFirst() }
            if (resolved > 0 && kept == 0 && closeMatchesFor == null) ended = true // only related verses from here on
            if (shown.size >= MAX_RESULTS) ended = true
            return page(failed = failed && resolved < batch.size)
        }

        /** Loads the batch's chapters not loaded yet; true if one couldn't be (offline, too many requests). */
        private suspend fun loadChapters(batch: List<VerseRef>): Boolean {
            val missing = batch.map { it.bookId to it.chapter }.distinct().filter { it !in chapters }
            val permits = Semaphore(PARALLEL_CHAPTERS)
            val loads = coroutineScope {
                missing.map { key -> async { permits.withPermit { key to loadChapter(key.first, key.second) } } }.awaitAll()
            }
            var failed = false
            loads.forEach { (key, load) ->
                when {
                    load.verses.isNotEmpty() -> chapters[key] = load.verses
                    // Offline or busy: worth trying again. Anything else won't change, so its verses are skipped.
                    load.problem == ChapterProblem.OFFLINE || load.problem == ChapterProblem.BUSY -> failed = true
                    else -> chapters[key] = emptyList()
                }
            }
            return failed
        }

        private fun page(failed: Boolean): WordSearchPage {
            val more = !ended && (queue.isNotEmpty() || pageToken != null)
            return WordSearchPage(
                verses = shown.toList(),
                loadMore = if (more) ::next else null,
                moreFailed = failed && more,
                closeMatchesFor = closeMatchesFor
            )
        }
    }

    companion object {
        /** About a screen of results, so the first batch fills the screen and the next waits for a scroll. */
        const val BATCH_SIZE = 8
        const val PARALLEL_CHAPTERS = 3
        /** As many as a downloaded Bible's search lists (BibleDatabaseManager.searchVerses). */
        const val MAX_RESULTS = 500
    }
}
