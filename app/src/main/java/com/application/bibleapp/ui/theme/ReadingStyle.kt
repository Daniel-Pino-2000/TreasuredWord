package com.application.bibleapp.ui.theme

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.application.bibleapp.R

/**
 * Dedicated text styles for the reading surface (verse body, headings, footnotes) —
 * kept separate from the UI [Typography] scale because reading text needs its own
 * family (serif, vs. the sans used for chrome) and looser line-height than any M3
 * slot is meant to carry.
 */
object ReadingStyle {
    /** Chapter title at the top of the reading surface (e.g. "Genesis 1"). Sits above
     *  [SectionHeading] in the hierarchy — larger and bolder — since it's the one
     *  heading every chapter gets, regardless of what the translation's own markup has. */
    val ChapterTitle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    )

    /** Verse body text. ~1.7x line-height for long-form reading comfort (the 1.6-1.8
     *  range recommended for scripture-length body copy).
     *
     *  [lineHeightStyle]/[platformStyle] pin every line to the same, uniform leading
     *  regardless of what's mixed into it — this style renders poem lines (each its
     *  own [androidx.compose.ui.text.ParagraphStyle]) where only *some* lines carry a
     *  superscript verse number or footnote marker. Without pinning it, those taller
     *  spans nudge just their own line's spacing, so consecutive poem lines end up
     *  visibly uneven; with it, every line reserves the same leading and the poem
     *  reads as a clean, regular block instead of a ragged one. */
    val VerseText = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 31.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Proportional,
            trim = LineHeightStyle.Trim.None
        ),
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )

    /** Verse number marker — deliberately small/light so it stays subordinate to the text. */
    val VerseNumber = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    )

    /** Section heading preceding a passage (e.g. "The Sermon on the Mount"). Sized
     *  clearly above [VerseText] (not just bolder) so it reads as a real break
     *  between passages rather than an emphasized verse. */
    val SectionHeading = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp
    )

    /** Hebrew subtitle / psalm superscription. */
    val HebrewSubtitle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontStyle = FontStyle.Italic,
        fontSize = 15.sp,
        lineHeight = 22.sp
    )

    /** Footnote bottom sheet body. */
    val FootnoteBody = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 15.sp,
        lineHeight = 23.sp
    )
}

/** Adjustable verse text size, persisted via [com.application.bibleapp.data.repository.BibleRepository].
 *  The old 0.85x "Small" tier was dropped and every remaining tier shifted down a slot — what used
 *  to be the 1x/1.15x/1.3x DEFAULT/LARGE/EXTRA_LARGE options are now SMALL/DEFAULT/LARGE, so the
 *  constant names still line up with their own labels (and [DEFAULT] — the fallback both here and
 *  in [com.application.bibleapp.data.repository.BibleRepository.loadVerseTextScale] — is still the
 *  option actually labeled "Default", not the smallest one). */
enum class VerseTextScale(val multiplier: Float, @StringRes val labelRes: Int) {
    SMALL(1f, R.string.text_size_small),
    DEFAULT(1.15f, R.string.text_size_default),
    LARGE(1.3f, R.string.text_size_large);

    companion object {
        fun fromMultiplier(value: Float): VerseTextScale =
            entries.minByOrNull { kotlin.math.abs(it.multiplier - value) } ?: DEFAULT
    }
}

/** Scales font size and line height together, preserving other style attributes. */
fun TextStyle.scaledBy(factor: Float): TextStyle = copy(
    fontSize = fontSize * factor,
    lineHeight = if (lineHeight != TextUnit.Unspecified) lineHeight * factor else lineHeight
)
