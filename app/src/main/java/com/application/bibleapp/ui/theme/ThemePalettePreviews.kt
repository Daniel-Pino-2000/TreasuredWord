package com.application.bibleapp.ui.theme

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

/**
 * Every color theme (#244), for `@PreviewParameter` on previews of screens that can't be reached
 * on a device without an account or a server answer (the Bible Helper), so they can still be
 * checked in each theme. Light/dark comes from each preview's own uiMode.
 */
class ThemePalettePreviewProvider : PreviewParameterProvider<ThemePalette> {
    override val values: Sequence<ThemePalette> = ThemePalette.entries.asSequence()
}
