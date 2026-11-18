package com.application.bibleapp.server.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Picks the topic guides for a Bible Helper question (#319): one embedding of the question
 * ([embed], search's `VerseSearch.embedQuery`, so it counts against search's daily cap), then
 * [router]'s arithmetic against the phrase vectors made at build time.
 *
 * Never fails a question: a slow embedding (past [timeoutMillis]), an error or no neurons left
 * mean no guides, and the answer gets v5's instructions exactly. Only the outcome, the number of
 * guides and the time are logged, never the question or which guides it matched, since a guide's
 * topic (crisis, abuse) says what was asked.
 */
class TopicGuideMatcher(
    private val router: TopicRouter,
    private val embed: suspend (String) -> FloatArray,
    private val timeoutMillis: Long = TIMEOUT_MILLIS
) {
    /** Starter questions are the server's own words, so each one's guides are kept once found. */
    private val starterGuides = ConcurrentHashMap<String, List<RoutedGuide>>()

    /** The guides for [question], or none when there's no match or it can't be matched now. */
    suspend fun match(question: String): List<RoutedGuide> = matchOrNull(question).orEmpty()

    /** [match] for a starter question, remembered under [key] once it was matched. */
    suspend fun matchStarter(key: String, question: String): List<RoutedGuide> =
        starterGuides[key] ?: matchOrNull(question)?.also { starterGuides[key] = it }.orEmpty()

    /** Null when the question couldn't be matched (as opposed to matching no guide). */
    private suspend fun matchOrNull(question: String): List<RoutedGuide>? {
        val started = System.nanoTime()
        return try {
            val guides = withTimeout(timeoutMillis) { router.route(embed(question)) }
            log.info("Topic guides: {} in {} ms", guides.size, millisSince(started))
            guides
        } catch (e: TimeoutCancellationException) {
            log.warn("Topic guides: none, no vector after {} ms", millisSince(started))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // AiProviderException (no neurons, search's cap, Workers AI failing) or anything else:
            // the question is answered without guides either way.
            log.warn("Topic guides: none, {} after {} ms", e::class.simpleName, millisSince(started))
            null
        }
    }

    companion object {
        /**
         * One embedding took ~0.9 s from a home connection on 2026-11-17 (Render's log line shows
         * the server's own time); past this the person would wait longer for guides than they
         * help, so the answer goes ahead with v5's instructions.
         */
        const val TIMEOUT_MILLIS = 3_000L
        private val log = LoggerFactory.getLogger(TopicGuideMatcher::class.java)

        private fun millisSince(started: Long) = (System.nanoTime() - started) / 1_000_000
    }
}
