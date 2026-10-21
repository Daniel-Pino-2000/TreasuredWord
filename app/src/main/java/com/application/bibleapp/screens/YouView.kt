package com.application.bibleapp.screens

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
import com.application.bibleapp.utils.formatRelativeSyncTime
import com.application.bibleapp.viewmodel.AuthViewModel
import com.application.bibleapp.viewmodel.BibleViewModel

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
    onContinueReadingClick: () -> Unit
) {
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
    val currentUser by authViewModel.currentUser.collectAsState()
    val isSyncing by bibleViewModel.isSyncing.collectAsState()
    val lastSyncCompletedAt by bibleViewModel.lastSyncCompletedAt.collectAsState()
    val currentBookName by bibleViewModel.currentBookName.collectAsState()
    val currentChapter by bibleViewModel.currentChapter.collectAsState()
    val highlights by bibleViewModel.libraryHighlights.collectAsState()
    val notes by bibleViewModel.libraryNotes.collectAsState()

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
                    email = currentUser?.email,
                    status = when {
                        isSyncing -> "Syncing…"
                        lastSyncCompletedAt != null -> "Synced " + formatRelativeSyncTime(lastSyncCompletedAt!!)
                        else -> "Not synced yet"
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

        GroupedSection(title = "Your library") {
            GroupedRow(
                title = "Highlights",
                icon = Icons.Outlined.BorderColor,
                value = highlights.size.toString(),
                onClick = { onLibraryClick(Screen.Library.TAB_HIGHLIGHTS) }
            )
            GroupedDivider()
            GroupedRow(
                title = "Notes",
                icon = Icons.Outlined.EditNote,
                value = notes.size.toString(),
                onClick = { onLibraryClick(Screen.Library.TAB_NOTES) }
            )
        }
    }
}

/** The avatar's initial is the first letter of the signed-in email — there's always something to
 *  put in the circle without an actual avatar-image feature. */
@Composable
private fun ProfileRow(email: String?, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = (email?.firstOrNull()?.uppercaseChar() ?: '?').toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Column {
            Text(text = email ?: "Signed in", style = MaterialTheme.typography.titleMedium)
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

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
                modifier = Modifier.size(48.dp)
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
                Text(text = "Not signed in", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Sign in to keep your highlights, notes, and reading progress on every device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Button(onClick = onSignInClick, modifier = Modifier.fillMaxWidth()) {
            Text("Sign in")
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
                text = "Continue reading",
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
