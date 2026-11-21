package com.application.bibleapp.data.repository

import com.application.bibleapp.data.remote.YouVersionBusyException
import com.application.bibleapp.data.remote.YouVersionNotLicensedException
import kotlinx.coroutines.CancellationException

/**
 * How gently a licensed version is read ahead in the background (#384). Kept in one place so they
 * can be raised once YouVersion's limits are known: the rate limit is per App Key (shared by
 * every reader) and unpublished, so a burst of whole-Bible downloads could lock every reader out.
 */
object ReadAheadLimits {
    /** The pause between two chapters at the normal pace. */
    const val START_INTERVAL_MS = 15_000L

    /** The slowest pace after repeated 429s (each one doubles the pause). Back to the start each day. */
    const val MAX_INTERVAL_MS = 4 * 60_000L

    /** Chapters a phone fetches in the background per day, across all licensed versions. */
    const val DAILY_CAP = 100

    /** How long to wait after a 429 that didn't say (no `Retry-After`), or after a failed request. */
    const val DEFAULT_WAIT_MS = 5 * 60_000L

    /** How long one background run lasts before it hands over to the next (WorkManager stops a
     *  worker after 10 minutes). */
    const val RUN_MS = 8 * 60_000L
}

/**
 * The pace of the read-ahead, saved on the phone so a restarted job can't burst: chapters fetched
 * on [dayKey], the pause between them, when the last one went, and until when YouVersion asked to
 * wait (a 429's `Retry-After`).
 */
data class ReadAheadPace(
    val dayKey: String = "",
    val fetchedToday: Int = 0,
    val intervalMs: Long = ReadAheadLimits.START_INTERVAL_MS,
    val lastFetchAtMs: Long = 0L,
    val blockedUntilMs: Long = 0L
) {
    /** A new day starts with no chapters counted and the normal pace again. */
    fun on(today: String): ReadAheadPace =
        if (today == dayKey) this else copy(dayKey = today, fetchedToday = 0, intervalMs = ReadAheadLimits.START_INTERVAL_MS)

    val capReached: Boolean get() = fetchedToday >= ReadAheadLimits.DAILY_CAP

    /** The soonest the next chapter may be fetched. */
    val nextFetchAtMs: Long get() = maxOf(lastFetchAtMs + intervalMs, blockedUntilMs)

    /** One request went out (whatever came back): it counts toward the day's cap. */
    fun afterRequest(nowMs: Long): ReadAheadPace = copy(fetchedToday = fetchedToday + 1, lastFetchAtMs = nowMs)

    /** YouVersion answered 429: wait until `Retry-After` has passed, then go at half the pace. */
    fun afterBusy(nowMs: Long, retryAfterSeconds: Long?): ReadAheadPace {
        val wait = retryAfterSeconds?.takeIf { it > 0 }?.times(1000) ?: ReadAheadLimits.DEFAULT_WAIT_MS
        return copy(
            intervalMs = (intervalMs * 2).coerceAtMost(ReadAheadLimits.MAX_INTERVAL_MS),
            blockedUntilMs = maxOf(blockedUntilMs, nowMs + wait)
        )
    }
}

/** Where [ReadAheadPace] is kept between runs (SharedPreferences in the app, a field in tests). */
interface ReadAheadPaceStore {
    fun load(): ReadAheadPace
    fun save(pace: ReadAheadPace)
}

/**
 * The order a licensed version is read ahead from the chapter just opened (#384): the rest of
 * that book, then the next books, and after the last book back to the first, ending just before
 * the opened chapter. [structure] is the version's book id -> (chapter -> verse count).
 */
fun readAheadOrder(structure: Map<Int, Map<Int, Int>>, bookId: Int, chapter: Int): List<Pair<Int, Int>> {
    val all = structure.keys.sorted().flatMap { book -> structure.getValue(book).keys.sorted().map { book to it } }
    val start = all.indexOfFirst { (b, c) -> b > bookId || (b == bookId && c > chapter) }
    if (start < 0) return all.filterNot { it == bookId to chapter }
    return (all.drop(start) + all.take(start)).filterNot { it == bookId to chapter }
}

/** How a background run ended, and so what the job does next. */
sealed interface ReadAheadOutcome {
    /** Every chapter in the order is saved. */
    data object Done : ReadAheadOutcome
    /** The day's cap is used up: nothing more until the reader opens a licensed chapter another day. */
    data object CapReached : ReadAheadOutcome
    /** The version was removed or isn't licensed to the key any more. */
    data object Stopped : ReadAheadOutcome
    /** Waiting (the pace, a 429 or a failure) past this run's end: carry on at [resumeAtMs]. */
    data class Paused(val resumeAtMs: Long) : ReadAheadOutcome
}

/**
 * One background run of the read-ahead (#384): fetches the chapters of [order] not saved yet, one
 * at a time at [ReadAheadPace]'s pace, until [ReadAheadOutcome]. The pace is re-read before each
 * chapter, so a 429 the reader got meanwhile pauses this too.
 */
class LicensedReadAhead(
    private val store: ReadAheadPaceStore,
    private val today: () -> String,
    private val now: () -> Long,
    private val sleep: suspend (Long) -> Unit,
    private val isSaved: (bookId: Int, chapter: Int) -> Boolean,
    private val isStillAdded: () -> Boolean,
    /** Fetches and saves one chapter; throws [YouVersionBusyException] on a 429. */
    private val fetchAndSave: suspend (bookId: Int, chapter: Int) -> Unit
) {
    suspend fun run(order: List<Pair<Int, Int>>, untilMs: Long): ReadAheadOutcome {
        for ((bookId, chapter) in order) {
            if (isSaved(bookId, chapter)) continue
            waitForTurn(untilMs)?.let { return it }
            if (!isStillAdded()) return ReadAheadOutcome.Stopped
            if (isSaved(bookId, chapter)) continue // the reader opened it while this waited
            val sentAt = now()
            store.save(store.load().on(today()).afterRequest(sentAt))
            try {
                fetchAndSave(bookId, chapter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YouVersionBusyException) {
                val pace = store.load().afterBusy(now(), e.retryAfterSeconds)
                store.save(pace)
                return ReadAheadOutcome.Paused(pace.blockedUntilMs)
            } catch (e: YouVersionNotLicensedException) {
                return ReadAheadOutcome.Stopped
            } catch (e: NoSuchElementException) {
                continue // a chapter this version doesn't have
            } catch (e: Exception) {
                // Most likely the connection dropped: try again later, not straight away.
                return ReadAheadOutcome.Paused(now() + ReadAheadLimits.DEFAULT_WAIT_MS)
            }
        }
        return ReadAheadOutcome.Done
    }

    /** Sleeps until the pace allows the next request; or says why this run ends instead. */
    private suspend fun waitForTurn(untilMs: Long): ReadAheadOutcome? {
        while (true) {
            val pace = store.load().on(today())
            if (pace.capReached) return ReadAheadOutcome.CapReached
            val wait = pace.nextFetchAtMs - now()
            if (wait <= 0) return null
            if (pace.nextFetchAtMs > untilMs) return ReadAheadOutcome.Paused(pace.nextFetchAtMs)
            sleep(wait)
        }
    }
}
