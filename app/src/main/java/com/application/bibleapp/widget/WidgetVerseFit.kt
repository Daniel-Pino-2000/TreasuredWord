package com.application.bibleapp.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/*
 * How the daily verse fits its widget (#245, #272). Widgets are drawn by the launcher, but the
 * widget is composed in the app's own process, on the same phone, with the same font, so the
 * verse is laid out here exactly as the widget's TextView will lay it out. The one guess left is
 * the room the verse gets, from the size the launcher reports and the rows around it.
 */

/** A verse laid out at one size: where each line ends in the text, each line's bottom, and the
 *  height of one more line (to count how many lines an area holds). */
internal class MeasuredVerse(val lineEnds: List<Int>, val lineBottomsDp: List<Float>, val lineHeightDp: Float) {
    val lineCount: Int get() = lineEnds.size
    val heightDp: Float get() = lineBottomsDp.lastOrNull() ?: 0f

    /** How many lines of this size fit in [heightDp] — more than [lineCount] if there's room. */
    fun linesThatFit(heightDp: Float): Int {
        val fitting = lineBottomsDp.count { it <= heightDp + EPSILON_DP }
        if (fitting < lineCount) return fitting.coerceAtLeast(1)
        return lineCount + floor((heightDp - this.heightDp) / lineHeightDp).toInt().coerceAtLeast(0)
    }
}

/** Lays [text] out like the widget's verse TextView would, at [sizeSp] in [widthDp]. */
internal fun interface VerseMeasurer {
    fun measure(text: String, sizeSp: Int, widthDp: Float): MeasuredVerse
}

/** What the widget shows: whether the title row fits, the verse's size and the most lines it may
 *  take, and the verse, cut at a word with "…" only if it can't fit at the smallest size. With
 *  [autoSize] (Android 8+) the launcher picks the size from [WIDGET_VERSE_MIN_SP] up to
 *  [WIDGET_VERSE_MAX_SP] itself, and [fontSizeSp] is the smallest. */
internal data class WidgetVerseFit(
    val showTitle: Boolean,
    val fontSizeSp: Int,
    val maxLines: Int,
    val text: String,
    val autoSize: Boolean
)

internal fun fitWidgetVerse(
    text: String,
    size: DpSize,
    fontScale: Float,
    autoSize: Boolean,
    measurer: VerseMeasurer
): WidgetVerseFit {
    val showTitle = size.height >= TITLE_MIN_HEIGHT
    val width = (size.width.value - 2 * WIDGET_PADDING_HORIZONTAL_DP).coerceAtLeast(1f)
    val height = (size.height.value - reservedHeight(showTitle, fontScale)).coerceAtLeast(1f)

    // Android 7: the largest size that holds the whole verse. Android 8+: the launcher grows it
    // from the smallest, as long as it holds all of it there.
    val sizes = if (autoSize) listOf(WIDGET_VERSE_MIN_SP) else (WIDGET_VERSE_MAX_SP downTo WIDGET_VERSE_MIN_SP).toList()
    for (sp in sizes) {
        val measured = measurer.measure(text, sp, width)
        if (measured.heightDp <= height + EPSILON_DP) {
            // With autosize, maxLines is what the area holds at the smallest size, so it never
            // stops the verse from growing (a bigger size holds fewer lines, not more).
            val maxLines = if (autoSize) measured.linesThatFit(height) else measured.lineCount
            return WidgetVerseFit(showTitle, sp, maxLines.coerceAtLeast(1), text, autoSize)
        }
    }

    // Too long even at the smallest size: cut at a word, so it ends inside the lines that fit.
    val measured = measurer.measure(text, WIDGET_VERSE_MIN_SP, width)
    val lines = measured.linesThatFit(height)
    var cut = fitVerse(text, measured.lineEnds[lines - 1])
    while (cut.length > 2 && measurer.measure(cut, WIDGET_VERSE_MIN_SP, width).lineCount > lines) {
        cut = fitVerse(text, cut.length - 1)
    }
    return WidgetVerseFit(showTitle, WIDGET_VERSE_MIN_SP, lines, cut, autoSize)
}

/** [text] cut at a word to at most [maxChars], ending in "…" when it was cut. */
internal fun fitVerse(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.take((maxChars - 1).coerceAtLeast(1))
    val atWord = cut.substringBeforeLast(' ', cut).ifBlank { cut }
    return atWord.trimEnd(' ', ',', ';', ':', '.', '—', '-') + "…"
}

/** The widget's height in dp that isn't the verse's: vertical padding, the reference line and its
 *  gap, and the title row when shown (its share button, #256, makes it [TITLE_ROW_HEIGHT_DP]
 *  tall, and the top padding smaller). Matches DailyVerseWidget's layout. */
private fun reservedHeight(showTitle: Boolean, fontScale: Float): Float =
    4f + 18f * fontScale + if (showTitle) 4f + 12f + 4f + maxOf(16f * fontScale, TITLE_ROW_HEIGHT_DP) else 24f

/**
 * Measures with [StaticLayout] the way the widget's TextView draws: the serif font, the size in
 * sp converted like a TextView converts it (nonlinear font scaling on Android 14+), and the
 * TextView's own line breaking. [includePad] is false for the Android 8+ layout
 * (widget_daily_verse_text.xml turns font padding off) and true for Glance's own text on Android 7.
 */
internal class StaticLayoutVerseMeasurer(context: Context, private val includePad: Boolean) : VerseMeasurer {
    private val metrics = context.resources.displayMetrics
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SERIF }

    // Layout.BREAK_STRATEGY_HIGH_QUALITY is LineBreaker's constant of the same value, which lint
    // asks for but only exists from Android 10; the app runs from Android 7.
    @SuppressLint("WrongConstant")
    override fun measure(text: String, sizeSp: Int, widthDp: Float): MeasuredVerse {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat(), metrics)
        val widthPx = (widthDp * metrics.density).toInt().coerceAtLeast(1)
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
            .setIncludePad(includePad)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUseLineSpacingFromFallbacks(true)
        val layout = builder.build()
        val density = metrics.density
        val lineHeight = (paint.fontMetricsInt.let { it.descent - it.ascent }) / density
        return MeasuredVerse(
            lineEnds = List(layout.lineCount) { layout.getLineEnd(it) },
            lineBottomsDp = List(layout.lineCount) { layout.getLineBottom(it) / density },
            lineHeightDp = lineHeight
        )
    }
}

/** The verse's size range, as res/values/dimens_widget.xml's widget_verse_min/max_text_size:
 *  the launcher picks within it on Android 8+, [fitWidgetVerse] on Android 7 (#272). */
internal const val WIDGET_VERSE_MIN_SP = 12
internal const val WIDGET_VERSE_MAX_SP = 22

internal const val WIDGET_PADDING_HORIZONTAL_DP = 16f

/** The title row's height in dp: "Share image"'s tap target (#256), centered on its 20dp icon. */
internal const val TITLE_ROW_HEIGHT_DP = 36f

private val TITLE_MIN_HEIGHT = 100.dp

/** Rounding slack, so a verse that fills its area exactly isn't counted as a line short. */
private const val EPSILON_DP = 0.5f
