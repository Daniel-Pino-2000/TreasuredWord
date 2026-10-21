package com.application.bibleapp.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.application.bibleapp.components.GroupedDivider
import com.application.bibleapp.components.GroupedRadioRow
import com.application.bibleapp.components.GroupedSection
import com.application.bibleapp.ui.theme.ReadingStyle
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.VerseTextScale
import com.application.bibleapp.ui.theme.scaledBy
import com.application.bibleapp.viewmodel.BibleViewModel

@Composable
fun AppearanceSettingsView(
    bibleViewModel: BibleViewModel,
    modifier: Modifier = Modifier
) {
    val themeMode by bibleViewModel.themeMode.collectAsState()
    val verseTextScale by bibleViewModel.verseTextScale.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl)
    ) {
        // Radio rows rather than the old chip row: every option gets a full-width target and
        // nothing has to scroll sideways to fit a narrow phone.
        GroupedSection(title = "Theme") {
            ThemeMode.entries.forEachIndexed { index, mode ->
                if (index > 0) GroupedDivider()
                GroupedRadioRow(
                    title = if (mode == ThemeMode.SYSTEM) "Match system" else mode.label,
                    selected = themeMode == mode,
                    onClick = { bibleViewModel.setThemeMode(mode) }
                )
            }
        }

        GroupedSection(title = "Verse text size") {
            VerseTextScale.entries.forEachIndexed { index, scale ->
                if (index > 0) GroupedDivider()
                GroupedRadioRow(
                    title = scale.label,
                    selected = verseTextScale == scale,
                    onClick = { bibleViewModel.setVerseTextScale(scale) }
                )
            }
        }

        // Below the size options, not above them — growing the preview above would push the
        // option you just tapped out from under your finger.
        GroupedSection(title = "Preview") {
            Text(
                text = "1 In the beginning God created the heavens and the earth.",
                style = ReadingStyle.VerseText.scaledBy(verseTextScale.multiplier),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(Spacing.lg)
            )
        }
    }
}
