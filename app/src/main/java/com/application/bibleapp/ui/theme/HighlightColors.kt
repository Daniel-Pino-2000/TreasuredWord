package com.application.bibleapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * Curated highlight swatches — a fixed palette rather than a full color picker, so the reading
 * view stays visually calm (see docs/UI_Integration_Roadmap.md Phase B). The server contract
 * accepts any ARGB int; this is a client-side UI choice, not a protocol constraint.
 *
 * Swatches are solid in the picker (clear to tell apart) but stored at
 * [HIGHLIGHT_BACKGROUND_ALPHA] so highlighted verse text stays legible underneath — the alpha is
 * baked into the ARGB int once, at creation time (see [toHighlightArgb]). Dark mode draws them
 * weaker still (see [highlightFill]) without changing what's stored or synced (#246).
 */
object HighlightColors {
    val Yellow = Color(0xFFE8C24A)
    val Green = Color(0xFF8FAE6B)
    val Blue = Color(0xFF7FA8C9)
    val Pink = Color(0xFFD98CA0)
    val Purple = Color(0xFFA98FC9)

    val palette: List<Color> = listOf(Yellow, Green, Blue, Pink, Purple)

    /** The check mark on a selected swatch. The swatches don't change with the theme, so neither
     *  does this: dark, because white reached only 1.7–2.8:1 on them (#248). */
    val CheckMark = Color(0xFF1F1F1F)
}

/** How translucent a saved highlight's background is stored — legible against verse text in light mode. */
private const val HIGHLIGHT_BACKGROUND_ALPHA = 0.35f

/** How strong a highlight is drawn in dark mode. 35% of a bright swatch over a dark page lands
 *  at a mid-tone where red letters and the selection shading couldn't reach 4.5:1 (#246). */
private const val DARK_HIGHLIGHT_ALPHA = 0.24f

/** The neutral shading over verses being selected, as a share of the text color. */
const val SELECTION_SCRIM_ALPHA = 0.18f

/** Bakes [HIGHLIGHT_BACKGROUND_ALPHA] into this swatch's stored ARGB int, once, at creation time. */
fun Color.toHighlightArgb(): Int = copy(alpha = HIGHLIGHT_BACKGROUND_ALPHA).toArgb()

/**
 * The color to draw a stored highlight with: as stored in light mode, and scaled down to
 * [DARK_HIGHLIGHT_ALPHA]'s strength in dark mode (#246). Only the drawing changes, so stored and
 * synced colors stay the same on every phone, updated or not. Scaled rather than replaced, so a
 * color from another client with a different alpha keeps its relative strength.
 */
fun highlightFill(storedArgb: Int, isDark: Boolean): Color {
    val stored = Color(storedArgb)
    return if (isDark) stored.copy(alpha = stored.alpha * (DARK_HIGHLIGHT_ALPHA / HIGHLIGHT_BACKGROUND_ALPHA)) else stored
}

/** [highlightFill] for the app's current light/dark choice (inside BibleAppTheme,
 *  [isSystemInDarkTheme] returns the app's setting, not the phone's — #24). */
@Composable
@ReadOnlyComposable
fun highlightFill(storedArgb: Int): Color = highlightFill(storedArgb, isSystemInDarkTheme())

/** The shading over verses being selected — neutral, so it never reads as another highlight color. */
fun selectionScrim(scheme: ColorScheme): Color = scheme.onSurface.copy(alpha = SELECTION_SCRIM_ALPHA)

/** WCAG contrast ratio between two opaque colors. */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * Red letters (words of Jesus) inside a highlighted verse (#246). The theme's `error` red reached
 * only 2.7–4.4:1 on some highlights, so it's made lighter (dark mode) or darker (light mode), with
 * its hue and saturation kept so it still reads as red, just enough to reach 4.5:1 (with a little
 * margin) on every swatch, on the page and on cards. Mixing it toward the text color instead
 * turned it almost white in dark mode. Computed per theme, so a palette tweak can't quietly break
 * it; ThemePaletteContrastTest checks it.
 */
fun wordsOfJesusOnHighlight(scheme: ColorScheme, isDark: Boolean): Color =
    readableRed(scheme, isDark, highlightGrounds(scheme, isDark))

/**
 * Red letters while their verse is selected, highlighted or not (#246). The selection shading
 * lightens a dark page, leaving red less room than a highlight does (3.7–4.3:1 in light mode, far
 * less over a dark-mode highlight), so this one is paler; it only lasts while selecting.
 */
fun wordsOfJesusOnSelection(scheme: ColorScheme, isDark: Boolean): Color {
    val scrim = selectionScrim(scheme)
    val grounds = (listOf(scheme.background, scheme.surface) + highlightGrounds(scheme, isDark))
        .map { scrim.compositeOver(it) }
    return readableRed(scheme, isDark, grounds)
}

/** Every highlight color as drawn over the page and over a card. */
private fun highlightGrounds(scheme: ColorScheme, isDark: Boolean): List<Color> =
    HighlightColors.palette.flatMap { swatch ->
        val fill = highlightFill(swatch.toHighlightArgb(), isDark)
        listOf(scheme.background, scheme.surface).map { fill.compositeOver(it) }
    }

/** The theme's red, made just light/dark enough to reach the target on all [grounds]. */
private fun readableRed(scheme: ColorScheme, isDark: Boolean, grounds: List<Color>): Color {
    fun passes(color: Color) = grounds.all { contrastRatio(color, it) >= RED_ON_HIGHLIGHT_TARGET }
    if (passes(scheme.error)) return scheme.error
    val (hue, saturation, lightness) = scheme.error.toHsl()
    val target = if (isDark) 1f else 0f
    for (step in 1..LIGHTNESS_STEPS) {
        val candidate = Color.hsl(hue, saturation, lightness + (target - lightness) * step / LIGHTNESS_STEPS)
        if (passes(candidate)) return candidate
    }
    return scheme.onSurface
}

private const val LIGHTNESS_STEPS = 50
private const val RED_ON_HIGHLIGHT_TARGET = 4.6

/** Hue (0–360), saturation and lightness (0–1) of an opaque color. */
private fun Color.toHsl(): Triple<Float, Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta == 0f) return Triple(0f, 0f, lightness)
    val saturation = delta / (1f - kotlin.math.abs(2f * lightness - 1f))
    val hue = when (max) {
        red -> 60f * (((green - blue) / delta).mod(6f))
        green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    return Triple(hue, saturation.coerceIn(0f, 1f), lightness)
}
