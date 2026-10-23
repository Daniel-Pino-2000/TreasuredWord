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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

/**
 * "About <book>", from the reader's info icon: GotQuestions.org's summary of the book (#48) and
 * BibleProject's overview videos (#55). Each row names its source, since all of it is someone
 * else's content and opens outside the app. The sheet stays open behind the browser or YouTube,
 * so Back lands here, ready for part 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutBookSheet(
    bookId: Int,
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
            AboutBookContent(bookId, bookName, spanish, links)
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
private fun AboutBookContent(bookId: Int, bookName: (Int) -> String, spanish: Boolean, links: ExternalLinks) {
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
        summaryUrl?.let { url ->
            ResourceRow(
                title = stringResource(R.string.about_book_summary),
                source = stringResource(R.string.source_gotquestions),
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
            Text(
                text = stringResource(R.string.about_book_videos),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.md)
            )
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
                    source = stringResource(R.string.source_bibleproject),
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

/** One outside resource: [leading] art, what it is, whose it is, and the opens-outside icon. */
@Composable
private fun ResourceRow(
    title: String,
    source: String,
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = source,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
