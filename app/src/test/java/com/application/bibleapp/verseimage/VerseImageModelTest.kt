package com.application.bibleapp.verseimage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerseImageModelTest {

    private fun content(vararg texts: String) = VerseImageContent(
        lines = texts.mapIndexed { index, text -> VerseImageLine(16 + index, text) },
        reference = "John 3:16",
        versionLabel = "KJV"
    )

    @Test
    fun fitTextSizePicksTheLargestSizeThatFits() {
        // Fits from 40 down: 76, 74 … 42 are too big.
        assertEquals(40f, fitTextSize(max = 76f, min = 30f, step = 2f) { it <= 40f })
    }

    @Test
    fun fitTextSizeKeepsTheMaximumWhenEverythingFits() {
        assertEquals(76f, fitTextSize(max = 76f, min = 30f, step = 2f) { true })
    }

    @Test
    fun fitTextSizeIsNullWhenEvenTheSmallestDoesNotFit() {
        assertNull(fitTextSize(max = 76f, min = 30f, step = 2f) { false })
    }

    @Test
    fun fitTextSizeTriesTheMinimumItself() {
        val tried = mutableListOf<Float>()
        fitTextSize(max = 34f, min = 30f, step = 2f) { tried += it; false }
        assertEquals(listOf(34f, 32f, 30f), tried)
    }

    @Test
    fun creditLineJoinsReferenceAndTranslation() {
        assertEquals("John 3:16 · KJV", content("For God so loved the world").creditLine)
        assertEquals("Juan 3:16-18", verseImageCreditLine("Juan 3:16-18", ""))
    }

    @Test
    fun aSingleVerseHasNoVerseNumbers() {
        assertFalse(content("For God so loved the world").hasSeveralVerses)
        assertTrue(content("For God…", "For God sent not…").hasSeveralVerses)
    }

    @Test
    fun tooLongCountsTheVerseTextOnly() {
        val atLimit = content("a".repeat(MAX_VERSE_IMAGE_CHARS))
        assertFalse(atLimit.isTooLongForImage)
        val over = content("a".repeat(MAX_VERSE_IMAGE_CHARS - 10), "b".repeat(11))
        assertTrue(over.isTooLongForImage)
    }

    @Test
    fun storedFormatFallsBackToSquare() {
        assertEquals(VerseImageFormat.STORY, VerseImageFormat.fromStored("STORY"))
        assertEquals(VerseImageFormat.SQUARE, VerseImageFormat.fromStored(null))
        assertEquals(VerseImageFormat.SQUARE, VerseImageFormat.fromStored("POSTER"))
    }
}
