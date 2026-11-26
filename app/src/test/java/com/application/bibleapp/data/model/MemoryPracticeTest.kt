package com.application.bibleapp.data.model

import com.application.bibleapp.utils.formatIsoTimestamp
import com.application.bibleapp.utils.parseIsoTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.random.Random

/** The Memorize practice schedule, Mixed mode's games, the streak and "Which reference?" (#363). */
class MemoryPracticeTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 24 * 60 * 60 * 1000L
    private val now = parseIsoTimestamp("2026-11-22T15:30:00Z")!!

    private fun verse(
        id: Long = 1,
        status: MemoryStatus = MemoryStatus.NEW,
        level: Int = 0,
        nextReviewAt: String? = null,
        lastPracticedAt: String? = null,
        correctStreak: Int = 0,
        memorizedAt: String? = null
    ) = MemoryVerse(
        localId = id, remoteId = null, versionId = "kjv", bookId = 43, chapter = 3, verseStart = id.toInt(), verseEnd = id.toInt(),
        status = status, level = level, nextReviewAt = nextReviewAt, lastPracticedAt = lastPracticedAt,
        correctStreak = correctStreak, memorizedAt = memorizedAt,
        createdAt = "2026-11-01T00:00:00Z", updatedAt = "2026-11-01T00:00:00Z"
    )

    private fun play(v: MemoryVerse, passed: Boolean, at: Long, game: PracticeGame = gameForProgress(v)) =
        applyPracticeResult(v, game, passed, at, utc)

    @Test
    fun `Mixed gives each level its game, and a Memorized passage the typing proof`() {
        assertEquals(PracticeGame.READ_AND_LISTEN, gameForProgress(verse(level = 0)))
        assertEquals(PracticeGame.FILL_THE_GAPS, gameForProgress(verse(status = MemoryStatus.LEARNING, level = 1)))
        assertEquals(PracticeGame.PUT_IN_ORDER, gameForProgress(verse(status = MemoryStatus.LEARNING, level = 2)))
        assertEquals(PracticeGame.FIRST_LETTERS, gameForProgress(verse(status = MemoryStatus.LEARNING, level = 3)))
        assertEquals(PracticeGame.FADING_WORDS, gameForProgress(verse(status = MemoryStatus.LEARNING, level = 4)))
        assertEquals(PracticeGame.TYPE_FROM_MEMORY, gameForProgress(verse(status = MemoryStatus.LEARNING, level = 5)))
        assertEquals(PracticeGame.TYPE_FROM_MEMORY, gameForProgress(verse(status = MemoryStatus.MEMORIZED, level = 5)))
    }

    @Test
    fun `a new passage is due, and a review date is due all of its day`() {
        assertTrue(isDueToday(verse(), now, utc))
        assertTrue(isDueToday(verse(nextReviewAt = "2026-11-22T23:00:00Z"), now, utc))
        assertTrue(isDueToday(verse(nextReviewAt = "2026-11-20T00:00:00Z"), now, utc))
        assertFalse(isDueToday(verse(nextReviewAt = "2026-11-23T00:00:00Z"), now, utc))
        assertFalse(isDueToday(verse(status = MemoryStatus.MEMORIZED, level = 5), now, utc))
    }

    @Test
    fun `passing moves up a level with reviews after 1, 2, 4, 7, 14 then 30 days`() {
        var v = verse()
        var at = now
        val expectedDays = listOf(1, 2, 4, 7, 14, 30)
        for ((level, days) in expectedDays.withIndex()) {
            val outcome = play(v, passed = true, at = at)
            assertTrue("level $level counts", outcome.counted)
            val progress = outcome.progress!!
            assertEquals((level + 1).coerceAtMost(MAX_MEMORY_LEVEL), progress.level)
            // The next review is local midnight that many days on.
            val next = parseIsoTimestamp(progress.nextReviewAt!!)!!
            assertEquals(startOfDay(at, days, utc), next)
            v = v.withProgress(progress)
            at = next + 9 * 60 * 60 * 1000L // practiced at 9:00 on the day it's due
        }
        // Six passes: level 5, with one pass of the typing game there. Not Memorized yet.
        assertEquals(MemoryStatus.LEARNING, v.status)
        assertEquals(1, v.correctStreak)
    }

    @Test
    fun `a passage is Memorized after passing the typing game at level 5 on two different days`() {
        val atTop = verse(status = MemoryStatus.LEARNING, level = 5, nextReviewAt = formatIsoTimestamp(now - day))
        val first = play(atTop, passed = true, at = now)
        assertFalse(first.newlyMemorized)
        assertEquals(1, first.progress!!.correctStreak)

        val later = atTop.withProgress(first.progress!!)
        val dueAt = parseIsoTimestamp(later.nextReviewAt!!)!!
        val second = play(later, passed = true, at = dueAt + 1000)
        assertTrue(second.newlyMemorized)
        assertTrue(second.movedUp)
        assertEquals(MemoryStatus.MEMORIZED.name, second.progress!!.status)
        assertNotNull(second.progress!!.memorizedAt)
        // It still comes back in about a month.
        assertEquals(startOfDay(dueAt + 1000, 30, utc), parseIsoTimestamp(second.progress!!.nextReviewAt!!))
    }

    @Test
    fun `the same day's second pass at the top doesn't count as another day`() {
        val atTop = verse(status = MemoryStatus.LEARNING, level = 5, correctStreak = 1, lastPracticedAt = formatIsoTimestamp(now - 60_000))
        val outcome = play(atTop, passed = true, at = now)
        assertFalse(outcome.newlyMemorized)
        assertEquals(1, outcome.progress!!.correctStreak)
    }

    @Test
    fun `failing moves down a level and brings it back tomorrow`() {
        val v = verse(status = MemoryStatus.LEARNING, level = 3, correctStreak = 0, nextReviewAt = formatIsoTimestamp(now - day))
        val outcome = play(v, passed = false, at = now)
        assertTrue(outcome.counted)
        assertEquals(2, outcome.progress!!.level)
        assertEquals(startOfDay(now, 1, utc), parseIsoTimestamp(outcome.progress!!.nextReviewAt!!))
        assertFalse(outcome.movedUp)
    }

    @Test
    fun `a Memorized passage that fails its review is Learning again at level 4`() {
        val v = verse(status = MemoryStatus.MEMORIZED, level = 5, correctStreak = 2, memorizedAt = "2026-10-01T00:00:00Z", nextReviewAt = formatIsoTimestamp(now - day))
        val outcome = play(v, passed = false, at = now)
        assertEquals(MemoryStatus.LEARNING.name, outcome.progress!!.status)
        assertEquals(4, outcome.progress!!.level)
        assertNull(outcome.progress!!.memorizedAt)
    }

    @Test
    fun `practicing before it's due is extra practice that changes nothing`() {
        val v = verse(status = MemoryStatus.LEARNING, level = 2, nextReviewAt = formatIsoTimestamp(now + 3 * day))
        val pass = play(v, passed = true, at = now)
        val fail = play(v, passed = false, at = now)
        assertFalse(pass.counted)
        assertNull(pass.progress)
        assertFalse(fail.counted)
        assertNull(fail.progress)
    }

    @Test
    fun `an easier game never counts, and a harder one moves up but never down`() {
        val v = verse(status = MemoryStatus.LEARNING, level = 3, nextReviewAt = formatIsoTimestamp(now - day))
        assertFalse(play(v, passed = true, at = now, game = PracticeGame.FILL_THE_GAPS).counted)
        val harder = play(v, passed = true, at = now, game = PracticeGame.TYPE_FROM_MEMORY)
        assertTrue(harder.counted)
        assertEquals(4, harder.progress!!.level)
        assertFalse(play(v, passed = false, at = now, game = PracticeGame.TYPE_FROM_MEMORY).counted)
        assertFalse(play(v, passed = true, at = now, game = PracticeGame.WHICH_REFERENCE).counted)
    }

    @Test
    fun `Mixed adds one Which reference bonus round, then the summary`() {
        val verses = listOf(verse(1), verse(2, MemoryStatus.LEARNING, level = 2), verse(3))
        val pages = buildPracticePages(verses, PracticeChoice(null), Random(1)) { true }
        assertEquals(5, pages.size)
        assertEquals(PracticePage.Game(1, PracticeGame.READ_AND_LISTEN), pages[0])
        assertEquals(PracticePage.Game(2, PracticeGame.PUT_IN_ORDER), pages[1])
        // The bonus goes to a passage past the first level when there is one.
        assertEquals(PracticePage.Game(2, PracticeGame.WHICH_REFERENCE, bonus = true), pages[3])
        assertEquals(PracticePage.Summary, pages.last())
    }

    @Test
    fun `one chosen game is played on every passage, with no bonus`() {
        val verses = listOf(verse(1), verse(2, MemoryStatus.LEARNING, level = 4))
        val pages = buildPracticePages(verses, PracticeChoice(PracticeGame.FIRST_LETTERS), Random(1)) { true }
        assertEquals(
            listOf(PracticePage.Game(1, PracticeGame.FIRST_LETTERS), PracticePage.Game(2, PracticeGame.FIRST_LETTERS), PracticePage.Summary),
            pages
        )
        assertTrue(buildPracticePages(emptyList(), PracticeChoice(null), Random(1)) { true }.isEmpty())
    }

    @Test
    fun `no bonus round without enough choices for it`() {
        val pages = buildPracticePages(listOf(verse(1), verse(2)), PracticeChoice(null), Random(1)) { false }
        assertEquals(3, pages.size)
    }

    @Test
    fun `the streak counts days in a row and breaks after a missed day`() {
        val none = PracticeStreak(null, 0)
        val first = none.practiced(today = "2026-326", yesterday = "2026-325")
        assertEquals(PracticeStreak("2026-326", 1), first)
        assertEquals(first, first.practiced("2026-326", "2026-325"))
        val second = first.practiced("2026-327", "2026-326")
        assertEquals(2, second.days)
        assertEquals(2, second.current("2026-328", "2026-327"))
        assertEquals(0, second.current("2026-329", "2026-328"))
        assertEquals(1, second.practiced("2026-330", "2026-329").days)
    }

    @Test
    fun `reference decoys come from the list first, then the same chapter without overlapping`() {
        val target = MemoryPassage(43, 3, 16, 17)
        val others = listOf(MemoryPassage(19, 23, 1, 1), target)
        val decoys = referenceDecoys(target, others, chapterVerses = 36, random = Random(3)) { true }
        assertEquals(REFERENCE_DECOYS, decoys.size)
        assertEquals(MemoryPassage(19, 23, 1, 1), decoys.first())
        decoys.drop(1).forEach {
            assertEquals(2, it.verseEnd - it.verseStart + 1)
            assertTrue(it.verseEnd < 16 || it.verseStart > 17)
        }
        assertFalse(target in decoys)
        // A two-verse chapter with nothing else on the list has no room for decoys.
        assertTrue(referenceDecoys(MemoryPassage(19, 117, 1, 1), emptyList(), 2, Random(1)) { true }.size < REFERENCE_DECOYS)
    }

    @Test
    fun `a passed game leads to the passage's next game in Mixed, and to the next passage otherwise`() {
        assertEquals(PracticeGame.entries.filter { it != PracticeGame.WHICH_REFERENCE }, PRACTICE_LADDER)
        assertEquals(PracticeGame.FILL_THE_GAPS, nextGameAfterPass(PracticeGame.READ_AND_LISTEN, mixed = true))
        assertEquals(PracticeGame.TYPE_FROM_MEMORY, nextGameAfterPass(PracticeGame.FADING_WORDS, mixed = true))
        // The top of the ladder, a bonus round, and one game for all go to the next passage (#416).
        assertNull(nextGameAfterPass(PracticeGame.TYPE_FROM_MEMORY, mixed = true))
        assertNull(nextGameAfterPass(PracticeGame.WHICH_REFERENCE, mixed = true))
        assertNull(nextGameAfterPass(PracticeGame.FILL_THE_GAPS, mixed = false))
    }

    @Test
    fun `every pass moves on by itself except Type it from memory, whose marks are worth reading`() {
        PracticeGame.entries.forEach { assertEquals(it != PracticeGame.TYPE_FROM_MEMORY, movesOnAfterPass(it)) }
    }
}
