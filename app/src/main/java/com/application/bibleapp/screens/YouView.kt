package com.application.bibleapp.screens

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.application.bibleapp.ui.theme.BibleAppTheme
import com.application.bibleapp.ui.theme.ThemePalette
import com.application.bibleapp.ui.theme.ThemePalettePreviewProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.application.bibleapp.utils.ProfilePicture
import com.application.bibleapp.utils.profilePictureRequestUrl
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.application.bibleapp.components.GroupedDivider
import com.application.bibleapp.components.GroupedIcon
import com.application.bibleapp.components.GroupedPanel
import com.application.bibleapp.components.GroupedRow
import com.application.bibleapp.components.GroupedSection
import com.application.bibleapp.navigation.Screen
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.utils.formatSyncedStatus
import com.application.bibleapp.viewmodel.AuthViewModel
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext

/**
 * The personal tab: who you are, where you left off, and what you've saved. Configuration lives
 * one level down in [com.application.bibleapp.screens.settings.SettingsView] (the top bar's gear),
 * so this screen only grows when there's more of the user's own content to show — new library
 * kinds go in as rows of "Your library".
 */
@Composable
fun YouView(
    bibleViewModel: BibleViewModel,
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier,
    onSignInClick: () -> Unit,
    onLibraryClick: (tab: String) -> Unit,
    onContinueReadingClick: () -> Unit,
    onBookmarkClick: () -> Unit
) {
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
    val email by authViewModel.email.collectAsState()
    val pictureUrl by authViewModel.pictureUrl.collectAsState()
    val isSyncing by bibleViewModel.isSyncing.collectAsState()
    val lastSyncCompletedAt by bibleViewModel.lastSyncCompletedAt.collectAsState()
    val currentBookName by bibleViewModel.currentBookName.collectAsState()
    val currentChapter by bibleViewModel.currentChapter.collectAsState()
    val highlights by bibleViewModel.libraryHighlights.collectAsState()
    val notes by bibleViewModel.libraryNotes.collectAsState()
    val bookmarkLabel by bibleViewModel.bookmarkLabel.collectAsState()

    // For the library counts — and refreshes the sync line if a background sync finished while
    // this tab sat in the back stack.
    LaunchedEffect(Unit) {
        bibleViewModel.loadLibrary()
        bibleViewModel.refreshSyncStatus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl)
    ) {
        GroupedPanel {
            if (isLoggedIn) {
                ProfileRow(
                    email = email,
                    pictureUrl = pictureUrl,
                    status = when {
                        isSyncing -> stringResource(R.string.sync_status_syncing)
                        lastSyncCompletedAt != null ->
                            formatSyncedStatus(LocalContext.current.resources, lastSyncCompletedAt!!)
                        else -> stringResource(R.string.sync_status_not_synced)
                    }
                )
            } else {
                SignedOutProfile(onSignInClick = onSignInClick)
            }
            GroupedDivider()
            ContinueReadingRow(
                reference = "$currentBookName $currentChapter",
                onClick = onContinueReadingClick
            )
        }

        GroupedSection(title = stringResource(R.string.you_section_library)) {
            // Always listed, even with no bookmark yet, so the feature is discoverable from here.
            GroupedRow(
                title = stringResource(R.string.label_bookmark),
                icon = Icons.Outlined.BookmarkBorder,
                subtitle = if (bookmarkLabel == null) stringResource(R.string.you_bookmark_hint) else null,
                value = bookmarkLabel,
                onClick = if (bookmarkLabel != null) onBookmarkClick else null
            )
            GroupedDivider()
            GroupedRow(
                title = stringResource(R.string.label_highlights),
                icon = Icons.Outlined.BorderColor,
                value = highlights.size.toString(),
                onClick = { onLibraryClick(Screen.Library.TAB_HIGHLIGHTS) }
            )
            GroupedDivider()
            GroupedRow(
                title = stringResource(R.string.label_notes),
                icon = Icons.Outlined.EditNote,
                value = notes.size.toString(),
                onClick = { onLibraryClick(Screen.Library.TAB_NOTES) }
            )
        }
    }
}

@Composable
private fun ProfileRow(email: String?, pictureUrl: String?, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        ProfileAvatar(email = email, pictureUrl = pictureUrl)
        Column {
            Text(text = email ?: stringResource(R.string.label_signed_in), style = MaterialTheme.typography.titleMedium)
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The Google account's picture (#335), over the first letter of the signed-in email. The letter
 * shows while the picture loads, offline before it was ever loaded, if it fails, and for email
 * accounts, which have no picture. With no email known yet (an older session, offline), the
 * circle shows the same person icon as signed out, never "?" (#109). Decorative: the email is
 * read out next to it.
 */
@Composable
private fun ProfileAvatar(email: String?, pictureUrl: String?) {
    val sizePx = with(LocalDensity.current) { AvatarSize.roundToPx() }
    val requestUrl = remember(pictureUrl, sizePx) { profilePictureRequestUrl(pictureUrl, sizePx) }
    val context = LocalContext.current
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(AvatarSize)
    ) {
        Box(contentAlignment = Alignment.Center) {
            val initial = email?.firstOrNull()?.uppercaseChar()
            if (initial != null) {
                Text(
                    text = initial.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            if (requestUrl != null) {
                // Draws nothing until it has the picture, so the letter stays visible meanwhile.
                AsyncImage(
                    model = ImageRequest.Builder(context).data(requestUrl).crossfade(true).build(),
                    contentDescription = null,
                    imageLoader = ProfilePicture.imageLoader(context),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .clip(CircleShape)
                )
            }
        }
    }
}

private val AvatarSize = 48.dp

@Composable
private fun SignedOutProfile(onSignInClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(AvatarSize)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Column {
                Text(text = stringResource(R.string.you_not_signed_in), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.you_sign_in_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Button(onClick = onSignInClick, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_sign_in))
        }
    }
}

@Composable
private fun ContinueReadingRow(reference: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        GroupedIcon(Icons.Outlined.AutoStories)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.you_continue_reading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = reference, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The account header with a picture and without one (#335). Run on a device through
 *  PreviewActivity to see the picture load: Android Studio's preview has no network, so it shows
 *  the letter. The address is Google's generic "no photo" avatar, not anyone's own. */
@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProfileRowPreview(@PreviewParameter(ThemePalettePreviewProvider::class) palette: ThemePalette) {
    BibleAppTheme(palette = palette) {
        Surface {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                GroupedPanel {
                    ProfileRow(
                        email = "reader@example.com",
                        pictureUrl = "https://lh3.googleusercontent.com/a/default-user=s96-c",
                        status = "Synced just now"
                    )
                    GroupedDivider()
                    ProfileRow(email = "reader@example.com", pictureUrl = null, status = "Synced just now")
                }
            }
        }
    }
}
