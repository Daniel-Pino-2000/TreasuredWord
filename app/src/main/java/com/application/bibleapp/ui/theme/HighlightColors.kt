package com.application.bibleapp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Curated highlight swatches — a fixed palette rather than a full color picker, so the reading
 * view stays visually calm (see docs/UI_Integration_Roadmap.md Phase B). The server contract
 * accepts any ARGB int; this is a client-side UI choice, not a protocol constraint.
 *
 * Swatches are solid in the picker (clear to tell apart) but stored/rendered at
 * [HIGHLIGHT_BACKGROUND_ALPHA] so highlighted verse text stays legible underneath — the alpha is
 * baked into the ARGB int once, at creation time (see [toHighlightArgb]), not reapplied on every
 * render.
 */
object HighlightColors {
    val Yellow = Color(0xFFE8C24A)
    val Green = Color(0xFF8FAE6B)
    val Blue = Color(0xFF7FA8C9)
    val Pink = Color(0xFFD98CA0)
    val Purple = Color(0xFFA98FC9)

    val palette: List<Color> = listOf(Yellow, Green, Blue, Pink, Purple)
}

/** How translucent a saved highlight's background renders — legible against verse text in both themes. */
private const val HIGHLIGHT_BACKGROUND_ALPHA = 0.35f

/** Bakes [HIGHLIGHT_BACKGROUND_ALPHA] into this swatch's stored ARGB int, once, at creation time. */
fun Color.toHighlightArgb(): Int = copy(alpha = HIGHLIGHT_BACKGROUND_ALPHA).toArgb()
