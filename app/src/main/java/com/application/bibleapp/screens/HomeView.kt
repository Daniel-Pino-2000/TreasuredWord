package com.application.bibleapp.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.components.MissingVersionBanners
import com.application.bibleapp.data.model.BookResources
import com.application.bibleapp.data.model.DailyVerseUI
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.chapterCount
import com.application.bibleapp.ui.theme.MaxContentWidth
import com.application.bibleapp.ui.theme.ReadingStyle
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.centeredContentWidth
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.ui.theme.scaledBy
import com.application.bibleapp.utils.UiText
import com.application.bibleapp.utils.bookNameInVersion
import com.application.bibleapp.utils.isSpanishUi
import com.application.bibleapp.utils.rememberExternalLinks
import com.application.bibleapp.utils.verseRefsText
import com.application.bibleapp.viewmodel.BibleViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/** How many of the newest highlights the Home strip shows before "See all". */
private const val RECENT_HIGHLIGHT_COUNT = 5

@Composable
fun HomeView(
    bibleViewModel: BibleViewModel,
    modifier: Modifier = Modifier,
    onContinueReadingClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onHighlightClick: (Highlight) -> Unit,
    onSeeAllHighlightsClick: () -> Unit
) {
    // The recent-highlights strip reads the Library's data; reload on every visit so a highlight
    // made or removed in the reader shows up here straight away.
    LaunchedEffect(Unit) { bibleViewModel.loadLibrary() }

    val verseOfTheDay by bibleViewModel.verseOfTheDay.collectAsState()
    val verseTextScale by bibleViewModel.verseTextScale.collectAsState()
    val currentBook by bibleViewModel.currentBook.collectAsState()
    val currentBookName by bibleViewModel.currentBookName.collectAsState()
    val currentChapter by bibleViewModel.currentChapter.collectAsState()
    val chapterStructure by bibleViewModel.chapterStructure.collectAsState()
    val bookmarkLabel by bibleViewModel.bookmarkLabel.collectAsState()
    val highlights by bibleViewModel.libraryHighlights.collectAsState()
    val verseTextByKey by bibleViewModel.libraryVerseText.collectAsState()
    val bookNamesByVersion by bibleViewModel.libraryBookNames.collectAsState()
    val isRestoring by bibleViewModel.isRestoringContent.collectAsState()

    // ISO-8601 UTC timestamps sort chronologically as plain strings.
    val recentHighlights = remember(highlights) {
        highlights.sortedByDescending { it.createdAt }.take(RECENT_HIGHLIGHT_COUNT)
    }
    // Each card in its own translation's book names, like its text (#54).
    val bookName = { versionId: String, bookId: Int -> bookNameInVersion(versionId, bookId, bookNamesByVersion) }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val greeting: @Composable () -> Unit = { HomeGreeting() }
    val verseCard: @Composable () -> Unit = {
        verseOfTheDay?.let { verse ->
            VerseOfTheDayCard(
                verse = verse,
                textScale = verseTextScale.multiplier,
                onCopy = {
                    bibleViewModel.verseOfTheDayShareText()?.let { text ->
                        clipboardManager.setText(AnnotatedString(text))
                        // Android 13+ shows its own clipboard confirmation; don't double it.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            bibleViewModel.showMessage(UiText.Res(R.string.message_copied))
                        }
                    }
                },
                onShare = {
                    bibleViewModel.verseOfTheDayShareText()?.let { text ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                }
            )
        }
    }
    val readingSection: @Composable () -> Unit = {
        ContinueReadingCard(
            reference = "$currentBookName $currentChapter",
            chapter = currentChapter,
            chapterCount = chapterStructure.chapterCount(currentBook),
            onClick = onContinueReadingClick
        )
        bookmarkLabel?.let { label ->
            HomeShortcutRow(
                icon = Icons.Filled.Bookmark,
                title = stringResource(R.string.label_bookmark),
                subtitle = label,
                onClick = onBookmarkClick
            )
        }
        RecentHighlightsSection(
            highlights = recentHighlights,
            isRestoring = isRestoring,
            verseText = { highlight -> resolveVerseText(highlight.versionId, highlight.verses, verseTextByKey) },
            bookName = bookName,
            onHighlightClick = onHighlightClick,
            onSeeAllClick = onSeeAllHighlightsClick
        )
        // Recent highlights in a translation that isn't downloaded show KJV text (#78).
        MissingVersionBanners(bibleViewModel, versionIds = recentHighlights.mapTo(mutableSetOf()) { it.versionId })
    }
    // GotQuestions.org (#47): someone else's site, so the row names it and shows the
    // opens-outside icon instead of a chevron.
    val spanish = isSpanishUi()
    val externalLinks = rememberExternalLinks { bibleViewModel.showMessage(it) }
    val questionsRow: @Composable () -> Unit = {
        HomeShortcutRow(
            icon = Icons.AutoMirrored.Outlined.HelpOutline,
            title = stringResource(R.string.home_questions_title),
            subtitle = stringResource(R.string.home_questions_subtitle),
            trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
            trailingIconDescription = stringResource(R.string.external_link_opens_outside),
            onClick = { externalLinks.openPage(BookResources.gotQuestionsHome(spanish)) }
        )
    }

    if (isCompactHeight()) {
        // Landscape: verse card on the left, greeting, reading and highlights on the right, so
        // the verse fits without scrolling. Each column stays well under MaxContentWidth. The
        // questions row fills the space under the verse card.
        Row(
            modifier = modifier
                .centeredContentWidth(maxWidth = MaxContentWidth * 1.4f)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Column(
                modifier = Modifier.weight(1.3f),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                verseCard()
                questionsRow()
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                greeting()
                readingSection()
            }
        }
    } else {
        Column(
            modifier = modifier
                .centeredContentWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            greeting()
            verseCard()
            readingSection()
            questionsRow()
        }
    }
}

/** "Good morning" (afternoon / evening by the clock) over today's date in the app's language. */
@Composable
private fun HomeGreeting() {
    val locale = LocalConfiguration.current.locales[0]
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greetingRes = when (hour) {
        in 5..11 -> R.string.home_greeting_morning
        in 12..17 -> R.string.home_greeting_afternoon
        else -> R.string.home_greeting_evening
    }
    val date = remember(locale) {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
        SimpleDateFormat(pattern, locale).format(Date())
    }
    Column(modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xs)) {
        Text(
            text = stringResource(greetingRes),
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif),
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = date,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VerseOfTheDayCard(
    verse: DailyVerseUI,
    textScale: Float,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    // A range shows each verse's number as a superscript before its text, like the reader does.
    val verseNumberStyle = ReadingStyle.VerseNumber.scaledBy(textScale).toSpanStyle().copy(
        color = MaterialTheme.colorScheme.primary,
        baselineShift = BaselineShift.Superscript
    )
    val verseText = remember(verse, verseNumberStyle) {
        buildAnnotatedString {
            verse.verses.forEachIndexed { index, line ->
                if (index > 0) append(" ")
                if (verse.hasVerseNumbers) {
                    withStyle(verseNumberStyle) { append("${line.number}") }
                    append(" ")
                }
                append(line.text)
            }
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = contentColor
    ) {
        Column(
            modifier = Modifier.padding(
                start = Spacing.xl,
                end = Spacing.sm,
                top = if (isCompactHeight()) Spacing.lg else Spacing.xl,
                bottom = Spacing.sm
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.verse_of_the_day),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = verseText,
                style = ReadingStyle.VerseText.scaledBy(textScale),
                modifier = Modifier.padding(end = Spacing.lg)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = verse.reference,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.75f),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = stringResource(R.string.action_share),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/** Where you left off, with how far into the book that chapter is. */
@Composable
private fun ContinueReadingCard(reference: String, chapter: Int, chapterCount: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_continue_reading),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = reference,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            if (chapterCount > 1) {
                Row(
                    modifier = Modifier.padding(top = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    LinearProgressIndicator(
                        progress = { (chapter.toFloat() / chapterCount).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                    Text(
                        text = stringResource(R.string.home_chapter_progress, chapter, chapterCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** The newest few highlights as a sideways strip, or a hint about highlighting when there are none. */
@Composable
private fun RecentHighlightsSection(
    highlights: List<Highlight>,
    isRestoring: Boolean,
    verseText: (Highlight) -> String,
    bookName: (versionId: String, bookId: Int) -> String,
    onHighlightClick: (Highlight) -> Unit,
    onSeeAllClick: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_recent_highlights),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (highlights.isNotEmpty()) {
                TextButton(onClick = onSeeAllClick) {
                    Text(stringResource(R.string.home_see_all))
                }
            }
        }
        if (highlights.isEmpty() && isRestoring) {
            // The first sync after signing in, e.g. on a reinstall (#79): the highlights are on
            // their way, and appear here by themselves once they're in.
            Row(
                modifier = Modifier.padding(top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.sync_restoring_content),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (highlights.isEmpty()) {
            Text(
                text = stringResource(R.string.home_highlights_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs)
            )
        } else {
            // Keyed cards keep the current first card in view when the list changes, so a new
            // highlight, inserted at index 0, landed off-screen to the left (#58). Show it instead —
            // only when the newest one actually changes, so coming back to Home otherwise keeps
            // wherever the row was scrolled to.
            val rowState = rememberLazyListState()
            val newestId = highlights.first().localId
            var shownNewestId by rememberSaveable { mutableStateOf(newestId) }
            LaunchedEffect(newestId) {
                if (newestId != shownNewestId) {
                    shownNewestId = newestId
                    rowState.scrollToItem(0)
                }
            }
            LazyRow(
                state = rowState,
                modifier = Modifier.padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                contentPadding = PaddingValues(end = Spacing.sm)
            ) {
                items(highlights, key = { it.localId }) { highlight ->
                    RecentHighlightCard(
                        reference = verseRefsText(highlight.verses) { bookName(highlight.versionId, it) },
                        text = verseText(highlight),
                        color = Color(highlight.color),
                        onClick = { onHighlightClick(highlight) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentHighlightCard(reference: String, text: String, color: Color, onClick: () -> Unit) {
    // The stored color is already translucent (see toHighlightArgb), so over `surface` it reads
    // as the same tint the verse has in the reader, in light and dark alike.
    Surface(
        modifier = Modifier.width(176.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .background(color)
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // Always three lines tall, so every card in the strip matches without a fixed height
            // that taller scripts' line spacing (e.g. Korean) would overflow.
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                minLines = 3,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = reference,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HomeShortcutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.ChevronRight,
    trailingIconDescription: String? = null,
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
                // onSurface, not the Surface's muted onSurfaceVariant, to match the Continue Reading card.
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = trailingIcon,
                contentDescription = trailingIconDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
