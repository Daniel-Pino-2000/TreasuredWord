package com.application.bibleapp.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The daily verse widget's fitting with the real font (#272), on the device running the test. */
@RunWith(AndroidJUnit4::class)
class DailyVerseWidgetMeasureTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val philippians = "³ Let nothing be done through strife or vainglory; but in lowliness of mind let each " +
        "esteem other better than themselves. ⁴ Look not every man on his own things, but every man also on the " +
        "things of others."

    /** The default 3×2 on the Pixel launcher, at the S23's size; where #272 cut this verse. */
    private val default = DpSize(237.dp, 193.dp)

    @Test
    fun aTypicalDailyVerseIsWholeAtTheDefaultSize() {
        val fit = fitWidgetVerse(philippians, default, 1f, autoSize = true, StaticLayoutVerseMeasurer(context, includePad = false))
        assertEquals(philippians, fit.text)
        val android7 = fitWidgetVerse(philippians, default, 1f, autoSize = false, StaticLayoutVerseMeasurer(context, includePad = true))
        assertEquals(philippians, android7.text)
    }

    @Test
    fun aLongPassageEndsAtAWordWithinItsLines() {
        val long = List(6) { philippians }.joinToString(" ")
        val measurer = StaticLayoutVerseMeasurer(context, includePad = false)
        val fit = fitWidgetVerse(long, default, 1f, autoSize = true, measurer)
        assertTrue(fit.text, fit.text.endsWith("…"))
        val kept = fit.text.removeSuffix("…")
        assertTrue(long.startsWith(kept))
        assertTrue(fit.text, long[kept.length] in " ,;:.")
        assertTrue(measurer.measure(fit.text, WIDGET_VERSE_MIN_SP, default.width.value - 32f).lineCount <= fit.maxLines)
    }
}
