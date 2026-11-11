package com.application.bibleapp.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the daily verse widget (#245, #272) fits a verse to its size. The real measuring is
 *  StaticLayout's (DailyVerseWidgetMeasureTest, on a device); here a monospaced stand-in. */
class DailyVerseWidgetLayoutTest {

    private val verse = "For God so loved the world, that he gave his only begotten Son, that whosoever " +
        "believeth in him should not perish, but have everlasting life."

    /** Philippians 2:3-4 (KJV), the daily verse #272 was seen with: cut after four lines at 3×2. */
    private val philippians = "³ Let nothing be done through strife or vainglory; but in lowliness of mind let each " +
        "esteem other better than themselves. ⁴ Look not every man on his own things, but every man also on the " +
        "things of others."

    /** Every character 0.42 em wide and lines 1.36 em tall, the serif font's averages measured on
     *  the default widget (#272), wrapped at spaces like a TextView. */
    private val monospaced = VerseMeasurer { text, sizeSp, widthDp ->
        val perLine = (widthDp / (sizeSp * 0.42f)).toInt().coerceAtLeast(1)
        val lineHeight = sizeSp * 1.36f
        val ends = mutableListOf<Int>()
        var start = 0
        while (start < text.length) {
            var end = (start + perLine).coerceAtMost(text.length)
            if (end < text.length) {
                val space = text.lastIndexOf(' ', end)
                if (space > start) end = space + 1
            }
            ends += end
            start = end
        }
        MeasuredVerse(ends, ends.indices.map { (it + 1) * lineHeight }, lineHeight)
    }

    private fun fit(text: String, size: DpSize, autoSize: Boolean, fontScale: Float = 1f) =
        fitWidgetVerse(text, size, fontScale, autoSize, monospaced)

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
        val small = fit(verse, DpSize(110.dp, 60.dp), autoSize = false)
        assertFalse(small.showTitle)
        assertTrue(small.maxLines >= 1)
    }

    @Test
    fun `a verse that fits at the default size is never cut`() {
        listOf(false, true).forEach { autoSize ->
            assertEquals("autoSize=$autoSize", philippians, fit(philippians, DEFAULT, autoSize).text)
        }
    }

    @Test
    fun `on Android 7 a short verse is set larger than a long one, and a big widget larger still`() {
        val short = fit("Jesus wept.", DEFAULT, autoSize = false)
        val long = fit(philippians, DEFAULT, autoSize = false)
        val longInBig = fit(philippians, DpSize(320.dp, 300.dp), autoSize = false)
        assertTrue(short.fontSizeSp > long.fontSizeSp)
        assertTrue(longInBig.fontSizeSp > long.fontSizeSp)
        assertEquals(philippians, longInBig.text)
    }

    @Test
    fun `on Android 8 and up the launcher sizes it, from the smallest size`() {
        val auto = fit(philippians, DEFAULT, autoSize = true)
        assertEquals(WIDGET_VERSE_MIN_SP, auto.fontSizeSp)
        // maxLines is what the area holds at that size, not the verse's own line count, so the
        // launcher can still grow a short verse.
        val shortAuto = fit("Jesus wept.", DEFAULT, autoSize = true)
        assertTrue(shortAuto.maxLines > 1)
    }

    @Test
    fun `a passage too long for the smallest size is cut at a word and fits`() {
        val long = List(6) { philippians }.joinToString(" ")
        listOf(false, true).forEach { autoSize ->
            val fitted = fit(long, DEFAULT, autoSize)
            assertEquals(WIDGET_VERSE_MIN_SP, fitted.fontSizeSp)
            assertTrue(fitted.text, fitted.text.endsWith("…"))
            val kept = fitted.text.removeSuffix("…")
            assertTrue(fitted.text, long.startsWith(kept))
            // Ends where a word did, not partway through one.
            assertTrue(fitted.text, long[kept.length] in " ,;:.")
            // And the cut text, ellipsis included, fits the lines it's given.
            val measured = monospaced.measure(fitted.text, WIDGET_VERSE_MIN_SP, DEFAULT.width.value - 32f)
            assertTrue(measured.lineCount <= fitted.maxLines)
        }
    }

    @Test
    fun `a larger font size leaves fewer lines`() {
        val long = List(6) { philippians }.joinToString(" ")
        val normal = fit(long, DpSize(250.dp, 200.dp), autoSize = true)
        val big = fit(long, DpSize(250.dp, 200.dp), autoSize = true, fontScale = 2f)
        assertTrue(big.maxLines < normal.maxLines)
        assertTrue(big.text.length < normal.text.length)
    }

    private companion object {
        /** The default 3×2 on the Pixel launcher, on an emulator the S23's size (#272). */
        val DEFAULT = DpSize(237.dp, 193.dp)
    }
}
