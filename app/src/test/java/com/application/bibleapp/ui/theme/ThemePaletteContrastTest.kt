package com.application.bibleapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every color theme (#244), light and dark, must pass WCAG AA on the color pairs the app really
 * draws: 4.5:1 for text, 3:1 for control borders and icons. Fails list every pair that misses, so
 * a palette tweak can't quietly make something unreadable.
 */
class ThemePaletteContrastTest {

    private data class Pair(val what: String, val fg: Color, val bg: Color, val min: Double)

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun pairsFor(c: ColorScheme, isDark: Boolean): List<Pair> {
        val pairs = mutableListOf<Pair>()
        // Where text sits: the page, cards and grouped panels (surface), bottom sheets
        // (surfaceContainerLow), tinted panels and the chapter buttons (surfaceVariant), and the
        // tab bar and chapter row, which tint surface by their tonal elevation (MainBottomBar).
        val tabBar = c.surfaceColorAtElevation(NavigationBarDefaults.Elevation)
        val chapterRow = c.surfaceColorAtElevation(NavigationBarDefaults.Elevation + 2.dp)
        val textGrounds = listOf(
            "page" to c.background, "card" to c.surface, "sheet" to c.surfaceContainerLow,
            "tinted panel" to c.surfaceVariant, "tab bar" to tabBar, "chapter row" to chapterRow
        )
        for ((name, ground) in textGrounds) {
            pairs += Pair("text on $name", c.onSurface, ground, TEXT)
            pairs += Pair("secondary text on $name", c.onSurfaceVariant, ground, TEXT)
        }
        for ((name, ground) in listOf("page" to c.background, "card" to c.surface, "sheet" to c.surfaceContainerLow)) {
            pairs += Pair("accent text on $name", c.primary, ground, TEXT)
            pairs += Pair("red text on $name", c.error, ground, TEXT)
            pairs += Pair("control border on $name", c.outline, ground, NON_TEXT)
        }
        // Dialogs (AlertDialog) sit on surfaceContainerHigh and menus on surfaceContainer, with
        // the title, body and text buttons in onSurface, onSurfaceVariant and primary.
        for ((name, ground) in listOf("dialog" to c.surfaceContainerHigh, "menu" to c.surfaceContainer)) {
            pairs += Pair("text on $name", c.onSurface, ground, TEXT)
            pairs += Pair("secondary text on $name", c.onSurfaceVariant, ground, TEXT)
            pairs += Pair("accent text on $name", c.primary, ground, TEXT)
        }
        // Remove/Delete buttons in dialogs (removing a translation, discarding a note) are red.
        pairs += Pair("red text on dialog", c.error, c.surfaceContainerHigh, TEXT)
        pairs += Pair("chapter arrow on its button", c.primary, c.surfaceVariant, NON_TEXT)
        pairs += Pair(
            "verse of the day reference",
            c.onPrimaryContainer.copy(alpha = 0.75f).compositeOver(c.primaryContainer), c.primaryContainer, TEXT
        )
        pairs += Pair("button label", c.onPrimary, c.primary, TEXT)
        pairs += Pair("text on accent tint (banners)", c.onPrimaryContainer, c.primaryContainer, TEXT)
        pairs += Pair("accent button on accent tint (banners)", c.primary, c.primaryContainer, TEXT)
        pairs += Pair("selected tab icon and chips", c.onSecondaryContainer, c.secondaryContainer, TEXT)
        pairs += Pair("snackbar text", c.inverseOnSurface, c.inverseSurface, TEXT)
        pairs += Pair("snackbar Undo", c.inversePrimary, c.inverseSurface, TEXT)
        pairs += Pair("icon on the red swipe-to-delete", c.onError, c.error, NON_TEXT)
        pairs += Pair("progress bar against its track", c.primary, c.outlineVariant, NON_TEXT)
        // Saved highlights are stored at 35% alpha (toHighlightArgb) and drawn over the page in
        // the reader and over cards in Home and Library, weaker in dark mode (highlightFill,
        // #246). Verse numbers on them use onSurface; red letters on them use
        // wordsOfJesusOnHighlight, and on a selected verse wordsOfJesusOnSelection.
        val redOnHighlight = wordsOfJesusOnHighlight(c, isDark)
        val redOnSelection = wordsOfJesusOnSelection(c, isDark)
        val scrim = selectionScrim(c)
        pairs += Pair("text on a selected verse", c.onSurface, scrim.compositeOver(c.background), TEXT)
        pairs += Pair("red text on a selected verse", redOnSelection, scrim.compositeOver(c.background), TEXT)
        for (swatch in HighlightColors.palette) {
            val fill = highlightFill(swatch.toHighlightArgb(), isDark)
            for ((name, base) in listOf("page" to c.background, "card" to c.surface)) {
                val highlighted = fill.compositeOver(base)
                val selected = scrim.compositeOver(highlighted)
                pairs += Pair("verse on highlight ${swatch.hex()} ($name)", c.onSurface, highlighted, TEXT)
                pairs += Pair("red text on highlight ${swatch.hex()} ($name)", redOnHighlight, highlighted, TEXT)
                pairs += Pair("verse on selected highlight ${swatch.hex()} ($name)", c.onSurface, selected, TEXT)
                pairs += Pair("red text on selected highlight ${swatch.hex()} ($name)", redOnSelection, selected, TEXT)
            }
        }
        return pairs
    }

    @Test
    fun `every theme passes WCAG AA in light and dark`() {
        val failures = ThemePalette.entries.flatMap { palette ->
            listOf(false, true).flatMap { isDark ->
                pairsFor(palette.colorScheme(isDark), isDark)
                    .filter { contrast(it.fg, it.bg) < it.min }
                    .map {
                        "${palette.name} ${if (isDark) "dark" else "light"}: ${it.what} is " +
                            "%.2f:1, needs %.1f:1".format(contrast(it.fg, it.bg), it.min)
                    }
            }
        }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    @Test
    fun `the check mark on a selected swatch reaches 3 to 1 on every highlight color`() {
        // The swatches are drawn solid and don't change with the theme (#248).
        val failures = HighlightColors.palette
            .map { it to contrast(HighlightColors.CheckMark, it) }
            .filter { (_, ratio) -> ratio < NON_TEXT }
            .map { (swatch, ratio) -> "check mark on ${swatch.hex()} is %.2f:1".format(ratio) }
        assertTrue(failures.joinToString("\n", prefix = "\n"), failures.isEmpty())
    }

    @Test
    fun `red letters on a highlight still read as red`() {
        // Made lighter or darker, never mixed toward the gray-ish text color (#246).
        for (palette in ThemePalette.entries) for (isDark in listOf(false, true)) {
            val red = wordsOfJesusOnHighlight(palette.colorScheme(isDark), isDark)
            assertTrue("${palette.name} dark=$isDark: ${red.hex()}", red.red > red.green + 0.1f && red.red > red.blue + 0.1f)
        }
    }

    @Test
    fun `dark mode draws highlights weaker without changing the stored color`() {
        val stored = HighlightColors.Yellow.toHighlightArgb()
        assertEquals(Color(stored), highlightFill(stored, isDark = false))
        val dark = highlightFill(stored, isDark = true)
        assertTrue(dark.alpha < Color(stored).alpha)
        assertEquals(Color(stored).copy(alpha = 1f), dark.copy(alpha = 1f))
    }

    @Test
    fun `an unknown or missing stored theme falls back to Parchment`() {
        assertEquals(ThemePalette.PARCHMENT, ThemePalette.fromStored(null))
        assertEquals(ThemePalette.PARCHMENT, ThemePalette.fromStored("NOT_A_THEME"))
        assertEquals(ThemePalette.SLATE, ThemePalette.fromStored("SLATE"))
    }

    @Test
    fun `each theme's light and dark backgrounds really are light and dark`() {
        for (palette in ThemePalette.entries) {
            assertTrue("${palette.name} light", palette.colorScheme(false).background.luminance() > 0.5f)
            assertTrue("${palette.name} dark", palette.colorScheme(true).background.luminance() < 0.1f)
        }
    }

    private fun Color.hex() = "#%06X".format(toArgb() and 0xFFFFFF)

    private companion object {
        const val TEXT = 4.5
        const val NON_TEXT = 3.0
    }
}
