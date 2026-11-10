package com.application.bibleapp.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.components.GroupedChoiceRow
import com.application.bibleapp.components.GroupedDivider
import com.application.bibleapp.components.GroupedRow
import com.application.bibleapp.components.GroupedSection
import com.application.bibleapp.ui.theme.ReadingStyle
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.ThemePalette
import com.application.bibleapp.ui.theme.VerseTextScale
import com.application.bibleapp.ui.theme.scaledBy
import com.application.bibleapp.viewmodel.BibleViewModel

@Composable
internal fun AppearanceSettings(bibleViewModel: BibleViewModel) {
    val themeMode by bibleViewModel.themeMode.collectAsState()
    val themePalette by bibleViewModel.themePalette.collectAsState()
    val verseTextScale by bibleViewModel.verseTextScale.collectAsState()
    val textSizeSample by bibleViewModel.textSizeSample.collectAsState()

    GroupedSection(title = stringResource(R.string.settings_section_appearance)) {
        GroupedChoiceRow(
            title = stringResource(R.string.settings_theme),
            icon = Icons.Outlined.DarkMode,
            options = ThemeMode.entries,
            selected = themeMode,
            optionLabel = { stringResource(it.labelRes) },
            onSelect = { bibleViewModel.setThemeMode(it) }
        )
        GroupedDivider()
        PaletteChoiceRow(selected = themePalette, onSelect = { bibleViewModel.setThemePalette(it) })
        GroupedDivider()
        GroupedChoiceRow(
            title = stringResource(R.string.settings_verse_text_size),
            icon = Icons.Outlined.FormatSize,
            options = VerseTextScale.entries,
            selected = verseTextScale,
            optionLabel = { stringResource(it.labelRes) },
            onSelect = { bibleViewModel.setVerseTextScale(it) }
        )
        // Below the size bar, not above it — a preview that grows above would push the bar
        // you just tapped out from under your finger. Scripture from the selected version,
        // not a string resource; the English line only covers a version with neither verse.
        Text(
            text = "1 ${textSizeSample ?: "In the beginning God created the heavens and the earth."}",
            style = ReadingStyle.VerseText.scaledBy(verseTextScale.multiplier),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg)
        )
    }
}

/** The color themes (#244) as round swatches, three to a row so the names fit in every language.
 *  Each swatch shows that theme's page with its accent in the middle, in the current light/dark
 *  mode, and the whole app switches the moment one is tapped. */
@Composable
private fun PaletteChoiceRow(selected: ThemePalette, onSelect: (ThemePalette) -> Unit) {
    // Inside BibleAppTheme this is the app's own Light/Dark choice, not the phone's (#24).
    val isDark = isSystemInDarkTheme()
    Column(modifier = Modifier.fillMaxWidth()) {
        GroupedRow(title = stringResource(R.string.settings_colors), icon = Icons.Outlined.Palette)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(start = Spacing.sm, end = Spacing.sm, bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            ThemePalette.entries.chunked(PALETTES_PER_ROW).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { palette ->
                        PaletteSwatch(
                            palette = palette,
                            isDark = isDark,
                            selected = palette == selected,
                            onClick = { onSelect(palette) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteSwatch(
    palette: ThemePalette,
    isDark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = palette.colorScheme(isDark)
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                // The ring sits outside the circle, with a gap, so it never blends into the
                // swatch's own page color.
                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                .padding(Spacing.xs)
                .clip(CircleShape)
                .background(colors.background)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    // The theme's own button colors, which ThemePaletteContrastTest checks.
                    Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(
            text = stringResource(palette.labelRes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else null,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private const val PALETTES_PER_ROW = 3
