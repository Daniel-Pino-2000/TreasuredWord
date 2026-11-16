package com.application.bibleapp.server.ai

import com.application.bibleapp.server.search.FoundVerse
import com.application.bibleapp.server.search.VerseSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * The verses the Bible Helper is given with a question (#287, phase 5): search by meaning's
 * best [count] for it, with their BSB text. The Bible Helper never depends on search, so [find]
 * never throws: out of neurons, past the search cap, Workers AI or the database failing, or
 * slower than [timeoutMillis], it returns no verses and the question is answered with v5's
 * instructions as before.
 *
 * Each question is one search, counted against the endpoint's daily cap ([VerseSearch]), since
 * both spend the same neurons. Neither the question nor the verses are logged.
 */
class HelperVerses(
    private val search: VerseSearch,
    private val count: Int = COUNT,
    private val timeoutMillis: Long = TIMEOUT_MILLIS
) {
    suspend fun find(question: String): List<FoundVerse> = try {
        withTimeoutOrNull(timeoutMillis) { search.searchWithText(question.trim(), count) }
            ?: emptyList<FoundVerse>().also { log.info("Verses for the Bible Helper: none, search took over $timeoutMillis ms") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.info("Verses for the Bible Helper: none, ${e::class.simpleName}")
        emptyList()
    }

    companion object {
        private val log = LoggerFactory.getLogger(HelperVerses::class.java)

        /** The task asked for 5-10; 8 verses add about 400 input tokens (~4 neurons) to a question. */
        const val COUNT = 8

        /**
         * A search takes ~0.1-0.3 s (124 ms median from the dev laptop, #287). The answer waits on
         * it, so a slow one is skipped rather than waited for.
         */
        const val TIMEOUT_MILLIS = 3_000L
    }
}
