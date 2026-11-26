package com.application.bibleapp.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** How large a practice game shows its passage (#416). */
class PracticeVerseSizeTest {

    @Test
    fun `short passages are largest, long ones smallest, and landscape a step smaller`() {
        assertEquals(VerseSize.LARGE, verseSizeFor(SHORT_PASSAGE_CHARS, compactHeight = false))
        assertEquals(VerseSize.MEDIUM, verseSizeFor(SHORT_PASSAGE_CHARS + 1, compactHeight = false))
        assertEquals(VerseSize.MEDIUM, verseSizeFor(MEDIUM_PASSAGE_CHARS, compactHeight = false))
        assertEquals(VerseSize.SMALL, verseSizeFor(MEDIUM_PASSAGE_CHARS + 1, compactHeight = false))
        assertEquals(VerseSize.MEDIUM, verseSizeFor(SHORT_PASSAGE_CHARS, compactHeight = true))
        assertEquals(VerseSize.SMALL, verseSizeFor(SHORT_PASSAGE_CHARS + 1, compactHeight = true))
    }
}
