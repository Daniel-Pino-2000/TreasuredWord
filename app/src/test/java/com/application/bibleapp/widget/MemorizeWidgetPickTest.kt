package com.application.bibleapp.widget

import com.application.bibleapp.data.model.MemoryStatus
import com.application.bibleapp.data.model.MemoryVerse
import com.application.bibleapp.utils.parseIsoTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which passage the "Verse to memorize" widget (#364) shows each day, and its hint mode. */
class MemorizeWidgetPickTest {

    private val now = parseIsoTimestamp("2026-11-19T12:00:00Z")!!
    private val past = "2026-11-18T12:00:00Z"
    private val future = "2026-11-25T12:00:00Z"

    private fun verse(
        id: Long,
        status: MemoryStatus = MemoryStatus.NEW,
        nextReviewAt: String? = null
    ) = MemoryVerse(
        localId = id, remoteId = null, versionId = "kjv", bookId = 43, chapter = 3, verseStart = id.toInt(), verseEnd = id.toInt(),
        status = status, nextReviewAt = nextReviewAt, createdAt = "2026-11-01T00:00:00Z", updatedAt = "2026-11-01T00:00:00Z"
    )

    private val list = (1L..6L).map { verse(it) }

    @Test
    fun `an empty list has no pick`() {
        assertNull(pickMemorizeVerse(emptyList(), "2026-323", 1, null, now))
    }

    @Test
    fun `the same day and widget always pick the same passage`() {
        val first = pickMemorizeVerse(list, "2026-323", 7, null, now)
        repeat(5) { assertEquals(first, pickMemorizeVerse(list, "2026-323", 7, null, now)) }
    }

    @Test
    fun `the day's pick is kept even when what's due changes`() {
        val pick = pickMemorizeVerse(list, "2026-323", 7, null, now)!!
        // Practiced since: now Memorized and not due, while the others are still due.
        val practiced = list.map { if (it.localId == pick.localId) it.copy(status = MemoryStatus.MEMORIZED, nextReviewAt = future) else it }
        assertEquals(pick, pickMemorizeVerse(practiced, "2026-323", 7, pick, now))
    }

    @Test
    fun `days and widgets don't all pick the same passage`() {
        val byDay = (300..330).map { pickMemorizeVerse(list, "2026-$it", 7, null, now)!!.localId }.toSet()
        assertTrue("one passage every day: $byDay", byDay.size > 1)
        val byWidget = (1..30).map { pickMemorizeVerse(list, "2026-323", it, null, now)!!.localId }.toSet()
        assertTrue("one passage for every widget: $byWidget", byWidget.size > 1)
    }

    @Test
    fun `yesterday's passage isn't repeated when there's another`() {
        val two = list.take(2)
        var stored: MemorizePick? = null
        (300..340).forEach { day ->
            val pick = pickMemorizeVerse(two, "2026-$day", 7, stored, now)!!
            if (stored != null) assertNotEquals("day $day", stored!!.localId, pick.localId)
            assertEquals(stored?.localId, pick.previousLocalId)
            stored = pick
        }
    }

    @Test
    fun `a list of one shows it every day`() {
        val one = list.take(1)
        val yesterday = pickMemorizeVerse(one, "2026-322", 7, null, now)
        assertEquals(1L, pickMemorizeVerse(one, "2026-323", 7, yesterday, now)!!.localId)
    }

    @Test
    fun `a removed passage is replaced the same day, still avoiding yesterday's`() {
        val three = list.take(3)
        val stored = MemorizePick("2026-323", localId = 2, previousLocalId = 1)
        val withoutTwo = three.filter { it.localId != 2L }
        val pick = pickMemorizeVerse(withoutTwo, "2026-323", 7, stored, now)!!
        assertEquals(3L, pick.localId)
        assertEquals(1L, pick.previousLocalId)
    }

    @Test
    fun `due passages come first, then ones being learned, then memorized ones`() {
        val due = verse(1, MemoryStatus.LEARNING, nextReviewAt = past)
        val learning = verse(2, MemoryStatus.LEARNING, nextReviewAt = future)
        val memorized = verse(3, MemoryStatus.MEMORIZED, nextReviewAt = future)
        val all = listOf(memorized, learning, due)
        (1..40).forEach { widget -> assertEquals(1L, pickMemorizeVerse(all, "2026-323", widget, null, now)!!.localId) }
        (1..40).forEach { widget -> assertEquals(2L, pickMemorizeVerse(listOf(memorized, learning), "2026-323", widget, null, now)!!.localId) }
    }

    @Test
    fun `a passage never practiced is due, a memorized one only when its review comes`() {
        assertEquals(0, memorizeTier(verse(1), now))
        assertEquals(0, memorizeTier(verse(1, MemoryStatus.MEMORIZED, nextReviewAt = past), now))
        assertEquals(2, memorizeTier(verse(1, MemoryStatus.MEMORIZED), now))
        assertEquals(1, memorizeTier(verse(1, MemoryStatus.LEARNING, nextReviewAt = future), now))
    }

    @Test
    fun `yesterday's due passage gives way to another due one`() {
        val dueA = verse(1, MemoryStatus.LEARNING, nextReviewAt = past)
        val dueB = verse(2, MemoryStatus.LEARNING, nextReviewAt = past)
        val stored = MemorizePick("2026-322", localId = 1, previousLocalId = null)
        assertEquals(2L, pickMemorizeVerse(listOf(dueA, dueB), "2026-323", 7, stored, now)!!.localId)
    }

    @Test
    fun `the hint keeps first letters and punctuation`() {
        assertEquals(
            "F G s l t w, t h g h o b S,",
            firstLettersHint("For God so loved the world, that he gave his only begotten Son,")
        )
    }

    @Test
    fun `the hint keeps verse numbers, quotes and dashes`() {
        assertEquals("¹⁶ “F G s l.” — J", firstLettersHint("¹⁶ “For God so loved.” — Jesus"))
        assertEquals("T L i m s; I s n w.",firstLettersHint("The LORD is my shepherd; I shall not want."))
        assertEquals("L h", firstLettersHint("Lord's  house"))
    }
}
