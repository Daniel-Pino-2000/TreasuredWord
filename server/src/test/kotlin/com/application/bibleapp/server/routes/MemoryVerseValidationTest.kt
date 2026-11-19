package com.application.bibleapp.server.routes

import com.application.bibleapp.server.models.CreateMemoryVerseRequest
import com.application.bibleapp.server.models.MemoryProgress
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Memorize list's rules that don't need the database (#362). */
class MemoryVerseValidationTest {

    private val passage = CreateMemoryVerseRequest(versionId = "kjv", bookId = 43, chapter = 3, verseStart = 16, verseEnd = 16)

    @Test
    fun `newer progress replaces older and nothing else does`() {
        val morning = Instant.parse("2026-11-19T08:00:00Z")
        val noon = Instant.parse("2026-11-19T12:00:00Z")
        assertTrue(memoryProgressReplaces(noon, morning))
        assertTrue(memoryProgressReplaces(morning, stored = null))
        assertFalse(memoryProgressReplaces(morning, noon))
        // The same write sent twice changes nothing the second time.
        assertFalse(memoryProgressReplaces(noon, noon))
        assertFalse(memoryProgressReplaces(incoming = null, stored = null))
    }

    @Test
    fun `a passage of one to six verses is accepted`() {
        assertNull(validateMemoryVerse(passage))
        assertNull(validateMemoryVerse(passage.copy(verseStart = 1, verseEnd = MAX_MEMORY_PASSAGE_VERSES)))
    }

    @Test
    fun `seven verses, a backwards range or a bad book is refused`() {
        assertNotNull(validateMemoryVerse(passage.copy(verseStart = 1, verseEnd = 7)))
        assertNotNull(validateMemoryVerse(passage.copy(verseStart = 17, verseEnd = 16)))
        assertNotNull(validateMemoryVerse(passage.copy(bookId = 67)))
        assertNotNull(validateMemoryVerse(passage.copy(versionId = " ")))
    }

    @Test
    fun `progress must have a known status, a level of 0 to 5 and real timestamps`() {
        assertNull(validateMemoryProgress(MemoryProgress(status = "MEMORIZED", level = 5, memorizedAt = "2026-11-19T08:00:00Z")))
        assertNotNull(validateMemoryProgress(MemoryProgress(status = "DONE")))
        assertNotNull(validateMemoryProgress(MemoryProgress(level = 6)))
        assertNotNull(validateMemoryProgress(MemoryProgress(correctStreak = -1)))
        assertEquals("timestamps must be ISO-8601", validateMemoryProgress(MemoryProgress(progressAt = "yesterday")))
    }
}
