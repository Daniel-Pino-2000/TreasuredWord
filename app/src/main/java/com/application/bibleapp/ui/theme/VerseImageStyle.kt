package com.application.bibleapp.ui.theme

import androidx.annotation.StringRes
import com.application.bibleapp.R

/** The ornament a [VerseImageStyle] draws around the verse, all drawn in code (#254). */
enum class VerseImageDecoration {
    /** A large opening quotation mark above the text. */
    QUOTE_MARK,

    /** A short line above and below the text. */
    DIVIDERS,

    /** A thin rounded frame inset from the edges. */
    FRAME,

    /** Small brackets in the four corners. */
    CORNERS,

    /** A soft lighter glow behind the text. */
    GLOW
}

/**
 * The looks a shared verse image can have (#254), one per color theme and named after it. These
 * are fixed colors, not [ThemePalette] roles: the image is a picture that leaves the app, so it
 * has to look the same whatever theme or mode the phone is in. A [backgroundEnd] different from
 * [background] draws a top-to-bottom gradient.
 *
 * [text] is the verse, [accent] the verse numbers and the decoration, [secondary] the reference,
 * license notice and the TreasuredWord mark. VerseImageStyleContrastTest holds each of them to
 * WCAG AA text contrast on both ends of the background. Saved by [name], so never rename one.
 */
enum class VerseImageStyle(
    @StringRes val labelRes: Int,
    val background: Int,
    val backgroundEnd: Int,
    val text: Int,
    val accent: Int,
    val secondary: Int,
    val decoration: VerseImageDecoration,
) {
    PARCHMENT(
        R.string.palette_parchment,
        background = 0xFFF4EBD9.toInt(), backgroundEnd = 0xFFEADCC1.toInt(),
        text = 0xFF3E2F1C.toInt(), accent = 0xFF805A2B.toInt(), secondary = 0xFF6B4E2C.toInt(),
        decoration = VerseImageDecoration.QUOTE_MARK
    ),
    INK(
        R.string.palette_ink,
        background = 0xFF121212.toInt(), backgroundEnd = 0xFF121212.toInt(),
        text = 0xFFF2F2F2.toInt(), accent = 0xFFBDBDBD.toInt(), secondary = 0xFFBDBDBD.toInt(),
        decoration = VerseImageDecoration.DIVIDERS
    ),
    GRAPHITE(
        R.string.palette_graphite,
        background = 0xFF2E333B.toInt(), backgroundEnd = 0xFF1C1F24.toInt(),
        text = 0xFFE6E8EB.toInt(), accent = 0xFFB6C0CF.toInt(), secondary = 0xFFB6C0CF.toInt(),
        decoration = VerseImageDecoration.GLOW
    ),
    SAGE(
        R.string.palette_sage,
        background = 0xFFDCE6D5.toInt(), backgroundEnd = 0xFFDCE6D5.toInt(),
        text = 0xFF1E2B19.toInt(), accent = 0xFF3F5A36.toInt(), secondary = 0xFF3F5A36.toInt(),
        decoration = VerseImageDecoration.FRAME
    ),
    SLATE(
        R.string.palette_slate,
        background = 0xFF223F5E.toInt(), backgroundEnd = 0xFF11212F.toInt(),
        text = 0xFFEEF3F9.toInt(), accent = 0xFF9CC2EA.toInt(), secondary = 0xFFC9DCF0.toInt(),
        decoration = VerseImageDecoration.QUOTE_MARK
    ),
    ROSE(
        R.string.palette_rose,
        background = 0xFFF6E3E8.toInt(), backgroundEnd = 0xFFF6E3E8.toInt(),
        text = 0xFF3A2129.toInt(), accent = 0xFF7E3F57.toInt(), secondary = 0xFF6F3A4E.toInt(),
        decoration = VerseImageDecoration.CORNERS
    );

    val hasGradient: Boolean get() = background != backgroundEnd

    companion object {
        /** The stored style, or the one named after the app's color theme when there's none yet,
         *  so a first image matches what the user already chose for the app. */
        fun fromStored(name: String?, palette: ThemePalette): VerseImageStyle =
            entries.firstOrNull { it.name == name } ?: forPalette(palette)

        fun forPalette(palette: ThemePalette): VerseImageStyle = when (palette) {
            ThemePalette.PARCHMENT -> PARCHMENT
            ThemePalette.INK -> INK
            ThemePalette.GRAPHITE -> GRAPHITE
            ThemePalette.SAGE -> SAGE
            ThemePalette.SLATE -> SLATE
            ThemePalette.ROSE -> ROSE
        }
    }
}
