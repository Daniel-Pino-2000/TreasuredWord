package com.application.bibleapp.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the daily verse widget (#245) fits a verse to its size, which it can't measure. */
class DailyVerseWidgetLayoutTest {

    private val verse = "For God so loved the world, that he gave his only begotten Son, that whosoever " +
        "believeth in him should not perish, but have everlasting life."

    @Test
    fun `a verse that fits is left whole`() {
        assertEquals(verse, fitVerse(verse, verse.length))
    }

    @Test
    fun `a long verse is cut at a word and ends in an ellipsis`() {
        val fitted = fitVerse(verse, 40)
        assertTrue(fitted, fitted.endsWith("…"))
        assertTrue(fitted, fitted.length <= 40)
        assertTrue(fitted, verse.startsWith(fitted.removeSuffix("…")))
        // Ends on a whole word, without trailing punctuation before the ellipsis.
        assertEquals("For God so loved the world, that he…", fitted)
    }

    @Test
    fun `a single long word is still cut`() {
        assertEquals("abcd…", fitVerse("abcdefghij", 5))
    }

    @Test
    fun `a small widget drops the title and still shows a line`() {
        val small = widgetTextLayout(DpSize(110.dp, 60.dp), fontScale = 1f)
        assertFalse(small.showTitle)
        assertTrue(small.maxLines >= 1)
    }

    @Test
    fun `a bigger widget shows more of the verse`() {
        val default = widgetTextLayout(DpSize(250.dp, 110.dp), fontScale = 1f)
        val large = widgetTextLayout(DpSize(320.dp, 300.dp), fontScale = 1f)
        assertTrue(default.showTitle)
        assertTrue(large.maxLines > default.maxLines)
        assertTrue(large.maxChars > default.maxChars)
    }

    @Test
    fun `a larger font size fits fewer lines`() {
        val normal = widgetTextLayout(DpSize(250.dp, 200.dp), fontScale = 1f)
        val big = widgetTextLayout(DpSize(250.dp, 200.dp), fontScale = 2f)
        assertTrue(big.maxLines < normal.maxLines)
        assertTrue(big.maxChars < normal.maxChars)
    }
}
