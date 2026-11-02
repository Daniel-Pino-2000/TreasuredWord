package com.application.bibleapp.data.repository

import com.application.bibleapp.data.model.CachedDailyVerse
import com.application.bibleapp.data.model.DailyVerseRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.util.Calendar

/** A cached daily verse is only used on the day it was fetched for (#183). */
class DailyVerseCacheTest {

    private val today = "2026-306"
    private val cachedRef = DailyVerseRef(bookId = 43, chapter = 3, startVerse = 20, endVerse = 21)
    private val fetchedRef = DailyVerseRef(bookId = 19, chapter = 23, startVerse = 1)
    private val curatedRef = DailyVerseRef(bookId = 6, chapter = 1, startVerse = 9)

    @Test
    fun `fresh cache is used without fetching`() = runTest {
        var fetches = 0
        val ref = pickDailyVerse(
            cached = CachedDailyVerse(dateKey = today, ref = cachedRef),
            todayKey = today,
            refresh = { fetches++; fetchedRef },
            curated = { curatedRef }
        )
        assertEquals(cachedRef, ref)
        assertEquals(0, fetches)
    }

    @Test
    fun `cache from a previous day is refreshed`() = runTest {
        val ref = pickDailyVerse(
            cached = CachedDailyVerse(dateKey = "2026-305", ref = cachedRef),
            todayKey = today,
            refresh = { fetchedRef },
            curated = { curatedRef }
        )
        assertEquals(fetchedRef, ref)
    }

    @Test
    fun `empty cache is refreshed`() = runTest {
        val ref = pickDailyVerse(cached = null, todayKey = today, refresh = { fetchedRef }, curated = { curatedRef })
        assertEquals(fetchedRef, ref)
    }

    @Test
    fun `stale cache while offline gives the curated verse, not the old one`() = runTest {
        val ref = pickDailyVerse(
            cached = CachedDailyVerse(dateKey = "2026-305", ref = cachedRef),
            todayKey = today,
            refresh = { throw IOException("No internet connection") },
            curated = { curatedRef }
        )
        assertEquals(curatedRef, ref)
    }

    @Test
    fun `refFor only returns today's reference`() {
        assertEquals(cachedRef, CachedDailyVerse(today, cachedRef).refFor(today))
        assertNull(CachedDailyVerse("2026-305", cachedRef).refFor(today))
        assertNull(null.refFor(today))
    }

    @Test
    fun `date key changes at local midnight and across years`() {
        fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
            Calendar.getInstance().apply { clear(); set(year, month, day, hour, minute) }

        assertEquals(
            dailyVerseDateKey(at(2026, Calendar.NOVEMBER, 2, 0, 0)),
            dailyVerseDateKey(at(2026, Calendar.NOVEMBER, 2, 23, 59))
        )
        assertNotEquals(
            dailyVerseDateKey(at(2026, Calendar.NOVEMBER, 2, 23, 59)),
            dailyVerseDateKey(at(2026, Calendar.NOVEMBER, 3, 0, 0))
        )
        // Same day-of-year, different year.
        assertNotEquals(
            dailyVerseDateKey(at(2026, Calendar.JANUARY, 1, 12, 0)),
            dailyVerseDateKey(at(2027, Calendar.JANUARY, 1, 12, 0))
        )
    }
}
