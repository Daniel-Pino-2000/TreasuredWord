package com.application.bibleapp.data.repository

import com.application.bibleapp.data.model.CachedDailyVerse
import com.application.bibleapp.data.model.DailyVerseRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import com.application.bibleapp.data.model.VerseOfTheDay
import java.util.Calendar
import kotlin.random.Random

/** A cached daily verse is only used on the day it was fetched for (#183), and a day never
 *  repeats the day before's verse (#298). */
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

    // --- #298: never yesterday's verse again because of when it was fetched ---

    @Test
    fun `yesterday's key is the day before, also across a new year`() {
        fun at(year: Int, month: Int, day: Int) = Calendar.getInstance().apply { clear(); set(year, month, day, 0, 30) }

        assertEquals(dailyVerseDateKey(at(2026, Calendar.NOVEMBER, 13)), previousDailyVerseDateKey(at(2026, Calendar.NOVEMBER, 14)))
        assertEquals(dailyVerseDateKey(at(2026, Calendar.DECEMBER, 31)), previousDailyVerseDateKey(at(2027, Calendar.JANUARY, 1)))
    }

    @Test
    fun `a fetched verse that differs from yesterday's is kept`() {
        assertEquals(fetchedRef, verseToKeepForToday(fetchedRef, yesterdays = cachedRef) { curatedRef })
        assertEquals(fetchedRef, verseToKeepForToday(fetchedRef, yesterdays = null) { curatedRef })
    }

    @Test
    fun `yesterday's verse fetched again gives the curated verse, which avoids yesterday's`() {
        var avoided: DailyVerseRef? = null
        val kept = verseToKeepForToday(cachedRef, yesterdays = cachedRef) { avoid -> avoided = avoid; curatedRef }

        assertEquals(curatedRef, kept)
        assertEquals(cachedRef, avoided)
    }

    /**
     * OurManna serves one verse per day of its own clock. Phones from UTC-10 to UTC+14 fetch at a
     * random local time each day (a delayed job, or the app opened late or early), and keep what
     * [verseToKeepForToday] picks. No day may show the same verse as the day before.
     */
    @Test
    fun `no phone shows the same verse two days running, whenever it fetches`() {
        val random = Random(298)
        fun ourMannaVerse(ourMannaDay: Long) = DailyVerseRef(bookId = 19, chapter = (ourMannaDay % 150 + 1).toInt(), startVerse = 1)
        for (offsetHours in -10..14) {
            var yesterdays: DailyVerseRef? = null
            for (day in 0 until 365L) {
                val fetchHour = random.nextInt(24)
                val ourMannaDay = Math.floorDiv(day * 24 + fetchHour - offsetHours, 24L)
                val calendar = Calendar.getInstance().apply { clear(); set(2026, Calendar.JANUARY, 1); add(Calendar.DAY_OF_YEAR, day.toInt()) }
                val kept = verseToKeepForToday(ourMannaVerse(ourMannaDay), yesterdays) { avoid -> VerseOfTheDay.forDay(calendar, avoid) }
                assertNotEquals("UTC$offsetHours, day $day", yesterdays, kept)
                yesterdays = kept
            }
        }
    }

    @Test
    fun `the curated verse changes every day, also from December 31 to January 1`() {
        val day = Calendar.getInstance().apply { clear(); set(2026, Calendar.JANUARY, 1, 9, 0) }
        var previous = VerseOfTheDay.forDay(day)
        repeat(800) {
            day.add(Calendar.DAY_OF_YEAR, 1)
            val current = VerseOfTheDay.forDay(day)
            assertNotEquals("${day.time}", previous, current)
            previous = current
        }
    }

    @Test
    fun `the curated verse is the same all day and never the one it should avoid`() {
        fun at(hour: Int) = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 14, hour, 0) }

        val morning = VerseOfTheDay.forDay(at(0))
        assertEquals(morning, VerseOfTheDay.forDay(at(23)))
        assertNotEquals(morning, VerseOfTheDay.forDay(at(12), avoid = morning))
    }
}
