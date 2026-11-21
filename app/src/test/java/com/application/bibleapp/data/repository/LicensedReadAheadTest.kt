package com.application.bibleapp.data.repository

import com.application.bibleapp.data.remote.YouVersionBusyException
import com.application.bibleapp.data.remote.YouVersionNotLicensedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LicensedReadAheadTest {

    // Three books: 1 has 3 chapters, 2 has 2, 3 has 1.
    private val structure = mapOf(
        1 to mapOf(1 to 10, 2 to 10, 3 to 10),
        2 to mapOf(1 to 10, 2 to 10),
        3 to mapOf(1 to 10)
    )

    private class FakeStore(var pace: ReadAheadPace = ReadAheadPace()) : ReadAheadPaceStore {
        override fun load() = pace
        override fun save(pace: ReadAheadPace) { this.pace = pace }
    }

    /** A clock that only moves when the read-ahead sleeps, recording when each fetch went out. */
    private class Harness(
        val store: FakeStore = FakeStore(),
        var day: String = "2026-325",
        val saved: MutableSet<Pair<Int, Int>> = mutableSetOf(),
        var stillAdded: Boolean = true,
        val failWith: (Pair<Int, Int>) -> Exception? = { null }
    ) {
        var clock = 1_000_000L
        val fetches = mutableListOf<Pair<Pair<Int, Int>, Long>>()

        val readAhead = LicensedReadAhead(
            store = store,
            today = { day },
            now = { clock },
            sleep = { clock += it },
            isSaved = { b, c -> (b to c) in saved },
            isStillAdded = { stillAdded },
            fetchAndSave = { b, c ->
                fetches += (b to c) to clock
                failWith(b to c)?.let { throw it }
                saved += b to c
            }
        )

        fun run(order: List<Pair<Int, Int>>, forMs: Long = Long.MAX_VALUE / 2) =
            runBlocking { readAhead.run(order, untilMs = clock + forMs) }
    }

    @Test
    fun `order is the rest of the book, then the next books, then back round`() {
        assertEquals(
            listOf(1 to 3, 2 to 1, 2 to 2, 3 to 1, 1 to 1),
            readAheadOrder(structure, bookId = 1, chapter = 2)
        )
    }

    @Test
    fun `order from the last chapter of the Bible starts again at the first`() {
        assertEquals(
            listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2),
            readAheadOrder(structure, bookId = 3, chapter = 1)
        )
    }

    @Test
    fun `fetches only missing chapters, one pause apart`() {
        val h = Harness(saved = mutableSetOf(2 to 1))
        val outcome = h.run(readAheadOrder(structure, 1, 2))

        assertEquals(ReadAheadOutcome.Done, outcome)
        assertEquals(listOf(1 to 3, 2 to 2, 3 to 1, 1 to 1), h.fetches.map { it.first })
        h.fetches.zipWithNext().forEach { (a, b) ->
            assertTrue(b.second - a.second >= ReadAheadLimits.START_INTERVAL_MS)
        }
    }

    @Test
    fun `a restarted run waits for the pause after the last fetch`() {
        val h = Harness()
        h.store.pace = ReadAheadPace(dayKey = h.day, fetchedToday = 1, lastFetchAtMs = h.clock - 5_000)
        h.run(listOf(1 to 1))

        assertEquals(h.clock, h.fetches.single().second)
        assertEquals(1_000_000L + ReadAheadLimits.START_INTERVAL_MS - 5_000, h.fetches.single().second)
    }

    @Test
    fun `stops at the daily cap and starts again the next day`() {
        val h = Harness()
        h.store.pace = ReadAheadPace(dayKey = h.day, fetchedToday = ReadAheadLimits.DAILY_CAP - 1)

        assertEquals(ReadAheadOutcome.CapReached, h.run(readAheadOrder(structure, 1, 1)))
        assertEquals(1, h.fetches.size)

        h.day = "2026-326"
        assertEquals(ReadAheadOutcome.Done, h.run(readAheadOrder(structure, 1, 1)))
        assertEquals(5, h.fetches.size) // every chapter but the one opened
        assertEquals(4, h.store.pace.fetchedToday) // counted afresh on the new day
    }

    @Test
    fun `a 429 pauses until Retry-After and halves the pace`() {
        var busy = true
        val h = Harness(failWith = { if (busy) YouVersionBusyException(retryAfterSeconds = 120) else null })
        val sentAt = h.clock

        val outcome = h.run(listOf(1 to 1, 1 to 2))

        assertEquals(ReadAheadOutcome.Paused(sentAt + 120_000), outcome)
        assertEquals(1, h.fetches.size)
        assertEquals(ReadAheadLimits.START_INTERVAL_MS * 2, h.store.pace.intervalMs)

        // The next run fetches nothing before Retry-After, then goes at the slower pace.
        busy = false
        h.run(listOf(1 to 1, 1 to 2))
        val (first, second) = h.fetches.drop(1)
        assertTrue(first.second >= sentAt + 120_000)
        assertEquals(ReadAheadLimits.START_INTERVAL_MS * 2, second.second - first.second)
    }

    @Test
    fun `a 429 without Retry-After waits the default time`() {
        val h = Harness(failWith = { YouVersionBusyException(retryAfterSeconds = null) })
        val outcome = h.run(listOf(1 to 1))
        assertEquals(ReadAheadOutcome.Paused(1_000_000L + ReadAheadLimits.DEFAULT_WAIT_MS), outcome)
    }

    @Test
    fun `repeated 429s never slow the pace past the maximum`() {
        var pace = ReadAheadPace()
        repeat(20) { pace = pace.afterBusy(0L, 1) }
        assertEquals(ReadAheadLimits.MAX_INTERVAL_MS, pace.intervalMs)
        assertEquals(ReadAheadLimits.START_INTERVAL_MS, pace.copy(dayKey = "a").on("b").intervalMs)
    }

    @Test
    fun `a 429 the reader got pauses the read-ahead too`() {
        val h = Harness()
        h.store.pace = ReadAheadPace().afterBusy(h.clock, retryAfterSeconds = 60)
        h.run(listOf(1 to 1))
        assertTrue(h.fetches.single().second >= 1_000_000L + 60_000)
    }

    @Test
    fun `a wait past the run's end hands over to the next run`() {
        val h = Harness()
        val outcome = h.run(listOf(1 to 1, 1 to 2), forMs = 1_000)
        assertEquals(ReadAheadOutcome.Paused(1_000_000L + ReadAheadLimits.START_INTERVAL_MS), outcome)
        assertEquals(1, h.fetches.size)
    }

    @Test
    fun `stops when the version is removed or not licensed`() {
        val removed = Harness(stillAdded = false)
        assertEquals(ReadAheadOutcome.Stopped, removed.run(listOf(1 to 1)))
        assertTrue(removed.fetches.isEmpty())

        val refused = Harness(failWith = { YouVersionNotLicensedException(403) })
        assertEquals(ReadAheadOutcome.Stopped, refused.run(listOf(1 to 1, 1 to 2)))
        assertEquals(1, refused.fetches.size)
    }

    @Test
    fun `a failed request tries again later, and a missing chapter is skipped`() {
        val dropped = Harness(failWith = { IOException("no route") })
        assertEquals(ReadAheadOutcome.Paused(1_000_000L + ReadAheadLimits.DEFAULT_WAIT_MS), dropped.run(listOf(1 to 1)))

        val missing = Harness(failWith = { if (it == 1 to 1) NoSuchElementException() else null })
        assertEquals(ReadAheadOutcome.Done, missing.run(listOf(1 to 1, 1 to 2)))
        assertEquals(listOf(1 to 1, 1 to 2), missing.fetches.map { it.first })
    }
}
