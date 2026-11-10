package com.application.bibleapp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.application.bibleapp.verseimage.VERSE_IMAGE_GLOW_ALPHA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every verse image style (#254) must keep its text readable (WCAG AA, 4.5:1) everywhere on the
 * image: the verse, the verse numbers in the accent color, and the reference, notice and mark in
 * the secondary color, on both ends of a gradient and under a glow at its brightest.
 */
class VerseImageStyleContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun grounds(style: VerseImageStyle): List<Pair<String, Color>> {
        val ends = listOf("top" to Color(style.background), "bottom" to Color(style.backgroundEnd))
        if (style.decoration != VerseImageDecoration.GLOW) return ends
        val glow = Color(red = 1f, green = 1f, blue = 1f, alpha = VERSE_IMAGE_GLOW_ALPHA / 255f)
        return ends + ends.map { (name, color) -> "$name under the glow" to glow.compositeOver(color) }
    }

    @Test
    fun everyStyleKeepsItsTextReadable() {
        val failures = VerseImageStyle.entries.flatMap { style ->
            grounds(style).flatMap { (ground, color) ->
                listOf(
                    "verse" to Color(style.text),
                    "verse numbers" to Color(style.accent),
                    "reference and mark" to Color(style.secondary)
                ).mapNotNull { (what, fg) ->
                    val ratio = contrast(fg, color)
                    if (ratio < TEXT) "${style.name}: $what on $ground is ${"%.2f".format(ratio)}:1" else null
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun whiteTextReadsOnAnyPhoto() {
        // The worst case is a pure white photo under the dark layer (#257).
        val scrim = Color(red = 0f, green = 0f, blue = 0f, alpha = VerseImagePhotoLook.SCRIM_ALPHA / 255f)
        val ground = scrim.compositeOver(Color.White)
        val ratio = contrast(Color(VerseImagePhotoLook.TEXT), ground)
        assertTrue("white on a white photo is ${"%.2f".format(ratio)}:1", ratio >= TEXT)
    }

    @Test
    fun everyColorThemeHasItsStyle() {
        ThemePalette.entries.forEach { palette ->
            assertEquals(palette.name, VerseImageStyle.forPalette(palette).name)
        }
    }

    @Test
    fun anUnknownStoredStyleFallsBackToTheAppTheme() {
        assertEquals(VerseImageStyle.SAGE, VerseImageStyle.fromStored("GONE", ThemePalette.SAGE))
        assertEquals(VerseImageStyle.INK, VerseImageStyle.fromStored(null, ThemePalette.INK))
        assertEquals(VerseImageStyle.ROSE, VerseImageStyle.fromStored("ROSE", ThemePalette.SAGE))
    }

    private companion object {
        const val TEXT = 4.5
    }
}
