package com.application.bibleapp.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.application.bibleapp.data.model.DailyVerseUI
import com.application.bibleapp.ui.theme.MaxContentWidth
import com.application.bibleapp.ui.theme.ReadingStyle
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.centeredContentWidth
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.ui.theme.scaledBy
import com.application.bibleapp.viewmodel.BibleViewModel

@Composable
fun HomeView(
    bibleViewModel: BibleViewModel,
    modifier: Modifier = Modifier,
    onContinueReadingClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    val verseOfTheDay by bibleViewModel.verseOfTheDay.collectAsState()
    val verseTextScale by bibleViewModel.verseTextScale.collectAsState()
    val currentBookName by bibleViewModel.currentBookName.collectAsState()
    val currentChapter by bibleViewModel.currentChapter.collectAsState()
    val bookmarkLabel by bibleViewModel.bookmarkLabel.collectAsState()

    val verseCard: @Composable (Modifier) -> Unit = { cardModifier ->
        verseOfTheDay?.let { verse ->
            VerseOfTheDayCard(
                verse = verse,
                textScale = verseTextScale.multiplier,
                modifier = cardModifier
            )
        }
    }
    val shortcuts: @Composable () -> Unit = {
        HomeShortcutRow(
            icon = Icons.Filled.MenuBook,
            title = "Continue Reading",
            subtitle = "$currentBookName $currentChapter",
            onClick = onContinueReadingClick
        )

        bookmarkLabel?.let { label ->
            HomeShortcutRow(
                icon = Icons.Filled.Bookmark,
                title = "Bookmark",
                subtitle = label,
                onClick = onBookmarkClick
            )
        }

        HomeShortcutRow(
            icon = Icons.Filled.Search,
            title = "Search the Bible",
            subtitle = "Find a verse by keyword",
            onClick = onSearchClick
        )
    }

    if (isCompactHeight()) {
        // Landscape: verse card on the left, shortcuts on the right, so the whole card fits
        // without scrolling. Each column stays well under MaxContentWidth.
        Row(
            modifier = modifier
                .centeredContentWidth(maxWidth = MaxContentWidth * 1.4f)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            verseCard(Modifier.weight(1.6f))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                shortcuts()
            }
        }
    } else {
        Column(
            modifier = modifier
                .centeredContentWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            verseCard(Modifier.fillMaxWidth())
            shortcuts()
        }
    }
}

@Composable
private fun VerseOfTheDayCard(verse: DailyVerseUI, textScale: Float, modifier: Modifier = Modifier) {
    // A range shows each verse's number as a superscript before its text, like the reader does.
    val verseNumberStyle = ReadingStyle.VerseNumber.scaledBy(textScale).toSpanStyle().copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        baselineShift = BaselineShift.Superscript
    )
    val verseText = remember(verse, verseNumberStyle) {
        buildAnnotatedString {
            verse.verses.forEachIndexed { index, line ->
                if (index > 0) append(" ")
                if (verse.hasVerseNumbers) {
                    withStyle(verseNumberStyle) { append("${line.number}") }
                    append(" ")
                }
                append(line.text)
            }
        }
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            // Tighter in landscape, where the card has to fit the short screen without scrolling.
            modifier = Modifier.padding(if (isCompactHeight()) Spacing.lg else Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = "Verse of the Day",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = verseText,
                style = ReadingStyle.VerseText.scaledBy(textScale),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = verse.reference,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HomeShortcutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
