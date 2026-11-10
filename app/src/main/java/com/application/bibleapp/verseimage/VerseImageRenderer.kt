package com.application.bibleapp.verseimage

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.application.bibleapp.ui.theme.VerseImageDecoration
import com.application.bibleapp.ui.theme.VerseImagePhotoLook
import com.application.bibleapp.ui.theme.VerseImageStyle

/** The app's name in the corner of a verse image. A name, so never translated. */
private const val MARK = "TreasuredWord"

/** Peak opacity of [VerseImageDecoration.GLOW]'s white glow; VerseImageStyleContrastTest
 *  checks the text against the background with it at full strength. */
const val VERSE_IMAGE_GLOW_ALPHA = 0x1F

/**
 * Draws a verse image (#254) with Android's own Canvas and StaticLayout rather than by capturing
 * a composable, so the very same code can draw it anywhere: the editor's preview (which is the
 * saved image, not a look-alike), a share, or a background job with no screen at all. Sizes are
 * designed for 1080 px wide and scale with [width].
 *
 * Text is set in the system serif, which falls back to the phone's own fonts for Korean, Chinese
 * and the other scripts the app is translated into. The verse is as large as fits; see
 * [fitTextSize]. Call it off the main thread: a story-size image is a few dozen milliseconds.
 */
object VerseImageRenderer {

    /**
     * [forWallpaper] (#255) keeps the verse smaller and in the upper part of the screen, below the
     * clock and above where the home screen's icons and dock sit, and leaves out the mark, which
     * would end up behind the dock.
     *
     * [photo] (#257) replaces the style's background: cropped to fill the image, under a dark
     * layer, with white text and no decoration, so the verse reads on any photo.
     */
    fun render(
        content: VerseImageContent,
        options: VerseImageOptions,
        width: Int = options.format.width,
        height: Int = options.format.height,
        forWallpaper: Boolean = false,
        photo: Bitmap? = null
    ): Bitmap {
        val bitmap = createBitmap(width, height)
        draw(Canvas(bitmap), content, options, width, height, forWallpaper, photo)
        return bitmap
    }

    /** The colors and decoration an image is drawn with: its style's, or white on a photo. */
    private class Look(val text: Int, val accent: Int, val secondary: Int, val decoration: VerseImageDecoration?)

    private fun draw(
        canvas: Canvas,
        content: VerseImageContent,
        options: VerseImageOptions,
        width: Int,
        height: Int,
        forWallpaper: Boolean,
        photo: Bitmap?
    ) {
        val style = options.style
        val look = if (photo != null) {
            Look(VerseImagePhotoLook.TEXT, VerseImagePhotoLook.TEXT, VerseImagePhotoLook.TEXT, decoration = null)
        } else {
            Look(style.text, style.accent, style.secondary, style.decoration)
        }
        val scale = width / 1080f
        val w = width.toFloat()
        val h = height.toFloat()

        if (photo != null) {
            drawPhoto(canvas, photo, w, h)
        } else {
            drawBackground(canvas, style, w, h)
            drawFrameDecoration(canvas, style, w, h, scale)
        }

        val margin = 108f * scale
        val contentWidth = (w - 2 * margin).toInt()
        val alignment = if (options.centered) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL

        // Everything around the verse, measured first so the verse gets the rest of the height.
        val label = content.label?.takeIf { options.showLabel }?.let { text ->
            layout(text, sansPaint(look.accent, 32f * scale, medium = true, letterSpacing = 0.06f), contentWidth, alignment)
        }
        val credit = layout(
            content.creditLine, sansPaint(look.secondary, 36f * scale, medium = true), contentWidth, alignment
        )
        val notice = content.notice?.takeIf { it.isNotBlank() }?.let { text ->
            layout(text, sansPaint(look.secondary, 26f * scale), contentWidth, alignment, maxLines = 4)
        }
        val quoteSize = 190f * scale
        val quoteHeight = if (look.decoration == VerseImageDecoration.QUOTE_MARK) quoteSize * 0.5f else 0f
        val dividerSpace = if (look.decoration == VerseImageDecoration.DIVIDERS) 56f * scale else 0f
        val gapLabel = 28f * scale
        val gapCredit = 44f * scale
        val gapNotice = 20f * scale

        val fixedHeight = (label?.height?.plus(gapLabel) ?: 0f).toFloat() +
            quoteHeight +
            dividerSpace * 2 +
            gapCredit + credit.height +
            (notice?.height?.plus(gapNotice) ?: 0f).toFloat()
        // The band the block is centered in: the whole image inside the margins (the mark sits in
        // the bottom one), or a wallpaper's upper part.
        val bandTop = if (forWallpaper) h * WALLPAPER_BAND_TOP else margin
        // A little more room at the bottom when the mark is shown, so a full image's reference
        // never runs into it.
        val bandBottom = when {
            forWallpaper -> h * WALLPAPER_BAND_BOTTOM
            options.showMark -> h - margin - 28f * scale
            else -> h - margin
        }
        val availableHeight = bandBottom - bandTop - fixedHeight

        val verseText = verseText(content, options, look.accent)
        val versePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = look.text
            typeface = Typeface.SERIF
        }
        // A tall image (story, wallpaper) has room for a larger verse than a square one.
        val maxSize = when {
            forWallpaper -> 64f * scale
            h / w >= 1.6f -> 95f * scale
            else -> 76f * scale
        }
        val fitted = fitTextSize(max = maxSize, min = 30f * scale, step = 2f * scale) { candidate ->
            versePaint.textSize = candidate
            layout(verseText, versePaint, contentWidth, alignment).height <= availableHeight
        }
        versePaint.textSize = fitted ?: (30f * scale)
        val verse = if (fitted != null) {
            layout(verseText, versePaint, contentWidth, alignment)
        } else {
            // Too long even at the smallest size (the editor stops before this): cut it with "…"
            // rather than drawing over the reference. Only here: the last line gets no extra
            // spacing, so a line count worked out from the spacing would cut a text that fits.
            val lineHeight = versePaint.fontSpacing * LINE_SPACING
            val maxLines = (availableHeight / lineHeight).toInt().coerceAtLeast(1)
            layout(verseText, versePaint, contentWidth, alignment, maxLines = maxLines)
        }

        val blockHeight = fixedHeight + verse.height
        var y = bandTop + (bandBottom - bandTop - blockHeight) / 2f
        val centerX = w / 2f

        label?.let {
            drawLayout(canvas, it, margin, y)
            y += it.height + gapLabel
        }
        if (look.decoration == VerseImageDecoration.QUOTE_MARK) {
            val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = look.accent
                typeface = Typeface.SERIF
                textSize = quoteSize
                textAlign = if (options.centered) Paint.Align.CENTER else Paint.Align.LEFT
            }
            // The glyph sits high in its line, so its baseline goes a little below the reserved band.
            canvas.drawText("“", if (options.centered) centerX else margin, y + quoteSize * 0.72f, quotePaint)
            y += quoteHeight
        }
        if (dividerSpace > 0f) {
            drawDivider(canvas, look.accent, if (options.centered) centerX else margin, y + dividerSpace / 2f, scale, options.centered)
            y += dividerSpace
        }
        drawLayout(canvas, verse, margin, y)
        y += verse.height
        if (dividerSpace > 0f) {
            drawDivider(canvas, look.accent, if (options.centered) centerX else margin, y + dividerSpace / 2f, scale, options.centered)
            y += dividerSpace
        }
        y += gapCredit
        drawLayout(canvas, credit, margin, y)
        y += credit.height
        notice?.let {
            y += gapNotice
            drawLayout(canvas, it, margin, y)
        }

        if (options.showMark && !forWallpaper) {
            val markPaint = sansPaint(look.secondary, 28f * scale, medium = true, letterSpacing = 0.04f).apply {
                textAlign = Paint.Align.CENTER
            }
            // Between the text block's margin and the frame/corner decorations (48 px in), so it
            // touches neither.
            canvas.drawText(MARK, centerX, h - 74f * scale, markPaint)
        }
    }

    /** The verses as one paragraph, each number a small superscript in the accent color. */
    private fun verseText(content: VerseImageContent, options: VerseImageOptions, numberColor: Int): CharSequence {
        val numbered = options.showVerseNumbers && content.hasSeveralVerses
        val builder = SpannableStringBuilder()
        content.lines.forEachIndexed { index, line ->
            if (index > 0) builder.append(' ')
            if (numbered) {
                val start = builder.length
                builder.append(line.number.toString())
                val end = builder.length
                builder.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(RelativeSizeSpan(0.6f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(numberColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.append(' ')
            }
            builder.append(line.text.trim())
        }
        return builder
    }

    /** [photo] scaled to fill the image and centered (the edges that don't fit are cut), under
     *  the dark layer that keeps white text readable on it. */
    private fun drawPhoto(canvas: Canvas, photo: Bitmap, w: Float, h: Float) {
        val fill = maxOf(w / photo.width, h / photo.height)
        val drawnWidth = photo.width * fill
        val drawnHeight = photo.height * fill
        val left = (w - drawnWidth) / 2f
        val top = (h - drawnHeight) / 2f
        canvas.drawBitmap(
            photo, null, RectF(left, top, left + drawnWidth, top + drawnHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        canvas.drawColor((VerseImagePhotoLook.SCRIM_ALPHA shl 24) or 0x000000)
    }

    private fun drawBackground(canvas: Canvas, style: VerseImageStyle, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (style.hasGradient) {
            paint.shader = LinearGradient(0f, 0f, 0f, h, style.background, style.backgroundEnd, Shader.TileMode.CLAMP)
        } else {
            paint.color = style.background
        }
        canvas.drawRect(0f, 0f, w, h, paint)
        if (style.decoration == VerseImageDecoration.GLOW) {
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    w / 2f, h / 2f, maxOf(w, h) * 0.6f,
                    (VERSE_IMAGE_GLOW_ALPHA shl 24) or 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, glow)
        }
    }

    /** The decorations that hug the edges rather than the text. */
    private fun drawFrameDecoration(canvas: Canvas, style: VerseImageStyle, w: Float, h: Float, scale: Float) {
        val inset = 48f * scale
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.accent
            this.style = Paint.Style.STROKE
            strokeWidth = 3f * scale
            strokeCap = Paint.Cap.ROUND
        }
        when (style.decoration) {
            VerseImageDecoration.FRAME -> {
                canvas.drawRoundRect(RectF(inset, inset, w - inset, h - inset), 28f * scale, 28f * scale, stroke)
            }
            VerseImageDecoration.CORNERS -> {
                val length = 72f * scale
                // top-left, top-right, bottom-left, bottom-right
                canvas.drawLine(inset, inset, inset + length, inset, stroke)
                canvas.drawLine(inset, inset, inset, inset + length, stroke)
                canvas.drawLine(w - inset, inset, w - inset - length, inset, stroke)
                canvas.drawLine(w - inset, inset, w - inset, inset + length, stroke)
                canvas.drawLine(inset, h - inset, inset + length, h - inset, stroke)
                canvas.drawLine(inset, h - inset, inset, h - inset - length, stroke)
                canvas.drawLine(w - inset, h - inset, w - inset - length, h - inset, stroke)
                canvas.drawLine(w - inset, h - inset, w - inset, h - inset - length, stroke)
            }
            else -> Unit
        }
    }

    private fun drawDivider(canvas: Canvas, color: Int, x: Float, y: Float, scale: Float, centered: Boolean) {
        val half = 44f * scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 2.5f * scale
            strokeCap = Paint.Cap.ROUND
        }
        if (centered) canvas.drawLine(x - half, y, x + half, y, paint) else canvas.drawLine(x, y, x + half * 2, y, paint)
    }

    private fun sansPaint(color: Int, size: Float, medium: Boolean = false, letterSpacing: Float = 0f) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (medium) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.SANS_SERIF
            this.letterSpacing = letterSpacing
        }

    // Layout.BREAK_STRATEGY_HIGH_QUALITY is the same value as LineBreaker's, which lint asks for
    // but only exists from API 29 (minSdk is 24).
    @SuppressLint("WrongConstant")
    private fun layout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment,
        maxLines: Int = Int.MAX_VALUE
    ): StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(alignment)
        .setLineSpacing(0f, LINE_SPACING)
        .setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .setMaxLines(maxLines)
        .setEllipsize(if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END)
        .build()

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.withTranslation(x, y) { layout.draw(this) }
    }

    private const val LINE_SPACING = 1.32f

    /** A wallpaper's verse sits between these fractions of the screen height (#255). */
    private const val WALLPAPER_BAND_TOP = 0.2f
    private const val WALLPAPER_BAND_BOTTOM = 0.58f
}
