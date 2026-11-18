package com.application.bibleapp.server.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Picks the topic guides for a Bible Helper question (#319): one embedding of the question
 * ([embed], search's `VerseSearch.embedQuery`, so it counts against search's daily cap), then
 * [router]'s arithmetic against the phrase vectors made at build time.
 *
 * Never fails a question: a slow embedding (past [timeoutMillis]; [firstTimeoutMillis] for the
 * first one after a start, #341), an error or no neurons left mean no guides, and the answer gets
 * v5's instructions exactly. Only the outcome, the number of
 * guides and the time are logged, never the question or which guides it matched, since a guide's
 * topic (crisis, abuse) says what was asked.
 */
class TopicGuideMatcher(
    private val router: TopicRouter,
    private val embed: suspend (String) -> FloatArray,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val firstTimeoutMillis: Long = FIRST_TIMEOUT_MILLIS
) {
    /** Whether a match has been tried since the server started: only the first gets the long limit. */
    private val firstTried = AtomicBoolean(false)

    /** Starter questions are the server's own words, so each one's guides are kept once found. */
    private val starterGuides = ConcurrentHashMap<String, List<RoutedGuide>>()

    /** The guides for [question], or none when there's no match or it can't be matched now. */
    suspend fun match(question: String): List<RoutedGuide> = matchOrNull(question).orEmpty()

    /** [match] for a starter question, remembered under [key] once it was matched. */
    suspend fun matchStarter(key: String, question: String): List<RoutedGuide> =
        starterGuides[key] ?: matchOrNull(question)?.also { starterGuides[key] = it }.orEmpty()

    /**
     * One tiny embedding at startup, in the background, when the guides are on (#341): it opens
     * the connection to Cloudflare and reaches bge-m3 before the first question does, which on a
     * just-woken server took past [TIMEOUT_MILLIS]. It counts as one search (~0.0001 neurons).
     * Never throws and never blocks anything; only the outcome and time are logged.
     */
    suspend fun warmUp() {
        val started = System.nanoTime()
        try {
            withTimeout(WARM_UP_TIMEOUT_MILLIS) { embed(WARM_UP_TEXT) }
            log.info("Topic guides: warmed up in {} ms", millisSince(started))
        } catch (e: TimeoutCancellationException) {
            log.warn("Topic guides: warm-up gave up after {} ms", millisSince(started))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Topic guides: warm-up failed, {} after {} ms", e::class.simpleName, millisSince(started))
        }
    }

    /** Null when the question couldn't be matched (as opposed to matching no guide). */
    private suspend fun matchOrNull(question: String): List<RoutedGuide>? {
        val started = System.nanoTime()
        // The first question after a start already waited for Render to wake the server, and its
        // embedding may pay for the first connection (#341): it gets the longer limit, once.
        val limit = if (firstTried.compareAndSet(false, true)) firstTimeoutMillis else timeoutMillis
        return try {
            val guides = withTimeout(limit) { router.route(embed(question)) }
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
        /**
         * The first match after a start (#341): on 2026-11-18 it ran past 3 s right after a
         * restart, while warm ones took 0.6–1.1 s. Render's log then showed the first request to
         * Cloudflare after a start takes ~7.7–7.9 s even when it's free, and the warm-up's
         * embedding 8.7 s in all, so this leaves margin above that. Only once per start.
         */
        const val FIRST_TIMEOUT_MILLIS = 12_000L
        private const val WARM_UP_TIMEOUT_MILLIS = 20_000L
        /** Any short text does; this one says nothing about anyone's question. */
        private const val WARM_UP_TEXT = "Bible"
        private val log = LoggerFactory.getLogger(TopicGuideMatcher::class.java)

        private fun millisSince(started: Long) = (System.nanoTime() - started) / 1_000_000
    }
}
