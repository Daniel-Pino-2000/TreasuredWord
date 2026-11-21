package com.application.bibleapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The play button drawn over a video's own thumbnail (#365). Fixed colors rather than theme
 * roles, because it sits on someone else's artwork, which looks the same in every theme: a dark
 * disc keeps the white triangle visible on any thumbnail, as YouTube's own players do.
 */
object VideoThumbnailColors {
    val PlayDisc = Color(0x8C000000)
    val PlayIcon = Color.White
}
