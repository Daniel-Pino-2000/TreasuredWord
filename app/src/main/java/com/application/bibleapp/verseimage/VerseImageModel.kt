package com.application.bibleapp.verseimage

import androidx.annotation.StringRes
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.VerseImageStyle

/** One verse on a verse image: its number and text. */
data class VerseImageLine(val number: Int, val text: String)

/**
 * What a verse image shows (#254): the verses, their reference and translation, and the
 * translation's license notice when it has one (the NET Bible asks for its notice to travel with
 * the text, #98). [label] is a small heading above the verse, e.g. "Verse of the day".
 */
data class VerseImageContent(
    val lines: List<VerseImageLine>,
    val reference: String,
    val versionLabel: String,
    val notice: String? = null,
    val label: String? = null
) {
    val hasSeveralVerses: Boolean get() = lines.size > 1

    /** "John 3:16 · KJV", under the verse. */
    val creditLine: String get() = verseImageCreditLine(reference, versionLabel)

    /** Past [MAX_VERSE_IMAGE_CHARS] the text would be too small to read on a phone, so the editor
     *  offers to share it as text instead. */
    val isTooLongForImage: Boolean
        get() = lines.sumOf { it.text.length } > MAX_VERSE_IMAGE_CHARS
}

/** What opens the verse image editor: the image's content, and the same passage as text for
 *  the "share as text instead" fallback when it's too long. */
data class VerseImageRequest(val content: VerseImageContent, val shareText: String)

/** About ten average verses; at that length a square image's text is at the smallest size the
 *  renderer allows. */
const val MAX_VERSE_IMAGE_CHARS = 600

fun verseImageCreditLine(reference: String, versionLabel: String): String =
    if (versionLabel.isBlank()) reference else "$reference · $versionLabel"

/** The image sizes, in pixels: 1080 wide like the big social apps expect. */
enum class VerseImageFormat(val width: Int, val height: Int, @StringRes val labelRes: Int) {
    SQUARE(1080, 1080, R.string.verse_image_size_square),
    PORTRAIT(1080, 1350, R.string.verse_image_size_portrait),
    STORY(1080, 1920, R.string.verse_image_size_story);

    companion object {
        fun fromStored(name: String?): VerseImageFormat = entries.firstOrNull { it.name == name } ?: SQUARE
    }
}

/** The editor's choices. Remembered between images (BibleRepository). */
data class VerseImageOptions(
    val style: VerseImageStyle = VerseImageStyle.PARCHMENT,
    val format: VerseImageFormat = VerseImageFormat.SQUARE,
    val showVerseNumbers: Boolean = true,
    val centered: Boolean = true,
    val showMark: Boolean = true,
    /** The content's label, e.g. "Verse of the Day" (#256), when it has one. */
    val showLabel: Boolean = true
)

/**
 * The largest text size from [max] down to [min], in [step]s, at which [fits] says the text fits,
 * or null if it doesn't even fit at [min]. Kept apart from the drawing so it can be unit-tested.
 */
fun fitTextSize(max: Float, min: Float, step: Float, fits: (Float) -> Boolean): Float? {
    require(step > 0f && max >= min)
    var size = max
    while (size >= min) {
        if (fits(size)) return size
        size -= step
    }
    return null
}
