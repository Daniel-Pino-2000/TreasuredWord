package com.application.bibleapp.data.model

import com.application.bibleapp.data.remote.MemoryProgressDto
import com.application.bibleapp.data.remote.MemoryVerseResponseDto
import com.application.bibleapp.data.remote.VerseLocationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Memorize list's rules that don't need a database (#362). */
class MemoryVerseTest {

    private fun john3(vararg verses: Int) = verses.map { VerseLocationDto(43, 3, it) }

    private fun verse(id: Long, createdAt: String, nextReviewAt: String? = null) = MemoryVerse(
        localId = id, remoteId = null, versionId = "kjv", bookId = 43, chapter = 3, verseStart = 16, verseEnd = 16,
        nextReviewAt = nextReviewAt, createdAt = createdAt, updatedAt = createdAt
    )

    @Test
    fun `one verse or consecutive verses make a passage, in any selection order`() {
        assertEquals(MemorySelection.Passage(MemoryPassage(43, 3, 16, 16)), memorySelectionOf(john3(16)))
        assertEquals(MemorySelection.Passage(MemoryPassage(43, 3, 16, 18)), memorySelectionOf(john3(18, 16, 17)))
        assertEquals(MemorySelection.Passage(MemoryPassage(43, 3, 1, 6)), memorySelectionOf(john3(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun `seven verses is too long`() {
        assertEquals(MemorySelection.TooLong, memorySelectionOf(john3(1, 2, 3, 4, 5, 6, 7)))
    }

    @Test
    fun `a gap or a second chapter isn't one passage`() {
        assertEquals(MemorySelection.NotOnePassage, memorySelectionOf(john3(16, 18)))
        assertEquals(MemorySelection.NotOnePassage, memorySelectionOf(john3(36) + VerseLocationDto(43, 4, 1)))
        assertEquals(MemorySelection.Empty, memorySelectionOf(emptyList()))
    }

    @Test
    fun `newest puts the latest added first`() {
        val older = verse(1, "2026-11-01T08:00:00.000Z")
        val newer = verse(2, "2026-11-10T08:00:00.000Z")

        assertEquals(listOf(2L, 1L), listOf(older, newer).sortedFor(MemorySort.NEWEST).map { it.localId })
    }

    @Test
    fun `due puts never-practiced and overdue passages before ones due later`() {
        val dueNextWeek = verse(1, "2026-11-01T08:00:00.000Z", nextReviewAt = "2026-11-26T08:00:00.000Z")
        val neverPracticed = verse(2, "2026-11-18T08:00:00.000Z")
        val overdue = verse(3, "2026-11-02T08:00:00.000Z", nextReviewAt = "2026-11-05T08:00:00.000Z")

        assertEquals(
            listOf(3L, 2L, 1L),
            listOf(dueNextWeek, neverPracticed, overdue).sortedFor(MemorySort.DUE).map { it.localId }
        )
    }

    @Test
    fun `a passage covers every verse from its first to its last`() {
        assertEquals(john3(16, 17, 18), verse(1, "2026-11-01T08:00:00.000Z").copy(verseEnd = 18).verses)
    }

    @Test
    fun `a copy matches the server only when everything is the same`() {
        val dto = MemoryVerseResponseDto(
            id = "m1", versionId = "kjv", bookId = 43, chapter = 3, verseStart = 16, verseEnd = 16,
            progress = MemoryProgressDto(status = "LEARNING", level = 2, progressAt = "2026-11-18T08:00:00.000Z"),
            createdAt = "2026-11-01T08:00:00.000Z", updatedAt = "2026-11-18T08:00:01.000Z"
        )
        val local = verse(1, dto.createdAt).copy(
            remoteId = "m1", status = MemoryStatus.LEARNING, level = 2, progressAt = "2026-11-18T08:00:00.000Z", updatedAt = dto.updatedAt
        )

        assertTrue(local.matchesServer(dto))
        assertFalse(local.copy(level = 1).matchesServer(dto))
    }

    @Test
    fun `an unknown stored status reads as new`() {
        assertEquals(MemoryStatus.NEW, MemoryStatus.fromStored("SOMETHING"))
        assertEquals(MemoryStatus.MEMORIZED, MemoryStatus.fromStored("MEMORIZED"))
    }
}
