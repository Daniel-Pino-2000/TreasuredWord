package com.application.bibleapp.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.data.model.BookResources
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.utils.ExternalLinks
import com.application.bibleapp.utils.rememberExternalLinks
import kotlinx.coroutines.launch

private val LeadingWidth = 48.dp
private val LeadingHeight = 30.dp
private val LogoHeight = 20.dp

/**
 * "About <book>", from the reader's info icon: GotQuestions.org's summary of the book (#48),
 * BibleProject's videos about the chapter being read (#407) and their overview videos (#55). Each section shows its source's logo (#405), since all
 * of it is someone else's content and opens outside the app. The sheet stays open behind the browser or YouTube,
 * so Back lands here, ready for part 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutBookSheet(
    bookId: Int,
    chapter: Int,
    bookName: (Int) -> String,
    spanish: Boolean,
    onDismiss: () -> Unit
) {
    // The sheet is its own window, drawn over the screen's snackbar, so a link that can't open
    // (offline, no browser) says so in here instead.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val links = rememberExternalLinks { message ->
        scope.launch { snackbarHostState.showSnackbar(message.resolve(context.resources)) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.large,
        // Only matters once the sheet is as tall as the window (landscape): stop below the
        // status bar instead of running the drag handle up under it.
        modifier = Modifier.statusBarsPadding()
    ) {
        Box {
            AboutBookContent(bookId, chapter, bookName, spanish, links)
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            )
        }
    }
}

@Composable
private fun AboutBookContent(bookId: Int, chapter: Int, bookName: (Int) -> String, spanish: Boolean, links: ExternalLinks) {
    val summaryUrl = BookResources.summaryUrl(bookId)
    val videos = BookResources.overviewVideos(bookId, spanish)

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
            .padding(bottom = if (isCompactHeight()) Spacing.sm else Spacing.xl)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.about_book_title, bookName(bookId)),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = Spacing.xs)
        )
        // Each section is headed by what you do there and whose it is (#405), so its rows only
        // say which item they are.
        val sheetColor = MaterialTheme.colorScheme.surfaceContainerLow
        summaryUrl?.let { url ->
            SectionHeader(stringResource(R.string.about_book_read)) {
                GotQuestionsLogo(background = sheetColor, height = LogoHeight)
            }
            ResourceRow(
                title = stringResource(R.string.about_book_summary_of, bookName(bookId)),
                onClick = { links.openPage(url) }
            ) {
                // Same width as the video placeholders, so every row's text lines up.
                Box(modifier = Modifier.size(LeadingWidth, LeadingHeight), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        if (videos.isNotEmpty()) {
            SectionHeader(stringResource(R.string.about_book_watch), Modifier.padding(top = Spacing.md)) {
                if (spanish) {
                    // Proyecto Biblia's own logo isn't offered for download: name it instead.
                    Text(
                        text = stringResource(R.string.source_bibleproject),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    BibleProjectLogo(background = sheetColor, height = LogoHeight)
                }
            }
            PassageVideos(bookId, chapter, bookName, spanish, links)
            videos.forEachIndexed { index, videoId ->
                val covered = BookResources.booksCovered(videoId, bookId, spanish)
                val title = when {
                    covered.first != covered.last -> stringResource(
                        R.string.about_book_video_covers, bookName(covered.first), bookName(covered.last)
                    )
                    videos.size > 1 -> stringResource(R.string.about_book_video_part, index + 1, videos.size)
                    else -> stringResource(R.string.about_book_video)
                }
                ResourceRow(
                    title = title,
                    onClick = { links.openYouTubeVideo(videoId) }
                ) {
                    VideoPlaceholder()
                }
            }
            // BibleProject's terms: a clear notice naming them as author and owner, linking to their site.
            Text(
                text = stringResource(R.string.about_book_video_credit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { links.openPage(BookResources.bibleProjectHome(spanish)) }
                    .padding(vertical = Spacing.sm)
            )
        }
    }
}

/**
 * BibleProject's videos about [chapter] itself (#407), under "This chapter", then a "The whole
 * book" label for the overview rows that follow. A long list (Exodus 20, Matthew 5) shows its
 * first few until asked for the rest, so the overviews stay in reach.
 */
@Composable
private fun PassageVideos(bookId: Int, chapter: Int, bookName: (Int) -> String, spanish: Boolean, links: ExternalLinks) {
    val passages = remember(bookId, chapter, spanish) { BookResources.passageVideos(bookId, chapter, spanish) }
    if (passages.isEmpty()) return
    var showAll by rememberSaveable(bookId, chapter) { mutableStateOf(false) }
    SubsectionLabel(stringResource(R.string.about_book_this_chapter))
    val shown = if (showAll || passages.size <= PASSAGES_SHOWN + 1) passages else passages.take(PASSAGES_SHOWN)
    shown.forEach { video ->
        val reference = video.reference(bookName(bookId))
        // A series about the same verses (the Character of God, the Shema) is told apart by part.
        val series = passages.filter { it.chapters == video.chapters && it.verses == video.verses }
        val title = if (series.size > 1) {
            stringResource(R.string.about_book_passage_video_part, reference, series.indexOf(video) + 1, series.size)
        } else {
            stringResource(R.string.about_book_passage_video, reference)
        }
        ResourceRow(title = title, onClick = { links.openYouTubeVideo(video.videoId) }) {
            VideoPlaceholder()
        }
    }
    if (shown.size < passages.size) {
        val hidden = passages.size - shown.size
        TextButton(onClick = { showAll = true }) {
            Text(pluralStringResource(R.plurals.about_book_show_more_videos, hidden, hidden))
        }
    }
    SubsectionLabel(stringResource(R.string.about_book_whole_book), Modifier.padding(top = Spacing.sm))
}

/** How many chapter videos show before "Show more"; a list just one longer shows whole. */
private const val PASSAGES_SHOWN = 3

/** "This chapter" / "The whole book" inside the Watch section (#407). */
@Composable
private fun SubsectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() }
    )
}

/** A section's [label] ("Read", "Watch") on the left and whose content it is, [source], on the right. */
@Composable
private fun SectionHeader(label: String, modifier: Modifier = Modifier, source: @Composable () -> Unit) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        source()
    }
}

/** One outside resource: [leading] art, what it is, and the opens-outside icon. */
@Composable
private fun ResourceRow(
    title: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            leading()
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = stringResource(R.string.external_link_opens_outside),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Stands in for a thumbnail: no image loading, and nothing fetched just to draw the sheet. */
@Composable
private fun VideoPlaceholder() {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(LeadingWidth, LeadingHeight)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
