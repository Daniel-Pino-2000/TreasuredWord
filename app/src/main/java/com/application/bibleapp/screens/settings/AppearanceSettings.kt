package com.application.bibleapp.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.application.bibleapp.components.GroupedChoiceRow
import com.application.bibleapp.components.GroupedDivider
import com.application.bibleapp.components.GroupedSection
import com.application.bibleapp.ui.theme.ReadingStyle
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.VerseTextScale
import com.application.bibleapp.ui.theme.scaledBy
import com.application.bibleapp.viewmodel.BibleViewModel

@Composable
internal fun AppearanceSettings(bibleViewModel: BibleViewModel) {
    val themeMode by bibleViewModel.themeMode.collectAsState()
    val verseTextScale by bibleViewModel.verseTextScale.collectAsState()

    GroupedSection(title = "Appearance") {
        GroupedChoiceRow(
            title = "Theme",
            icon = Icons.Outlined.Palette,
            options = ThemeMode.entries,
            selected = themeMode,
            optionLabel = { it.label },
            onSelect = { bibleViewModel.setThemeMode(it) }
        )
        GroupedDivider()
        GroupedChoiceRow(
            title = "Verse text size",
            icon = Icons.Outlined.FormatSize,
            options = VerseTextScale.entries,
            selected = verseTextScale,
            optionLabel = { it.label },
            onSelect = { bibleViewModel.setVerseTextScale(it) }
        )
        // Below the size bar, not above it — a preview that grows above would push the bar
        // you just tapped out from under your finger.
        Text(
            text = "1 In the beginning God created the heavens and the earth.",
            style = ReadingStyle.VerseText.scaledBy(verseTextScale.multiplier),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg)
        )
    }
}
