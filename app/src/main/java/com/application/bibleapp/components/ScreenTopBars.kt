package com.application.bibleapp.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.application.bibleapp.ui.theme.Spacing

/** [versionLabel] is the active translation's short code (e.g. "KJV", "ESV") — see
 *  [com.application.bibleapp.viewmodel.BibleViewModel.currentVersionLabel].
 *  [chapterNav], when given (compact height — see MainActivity), takes the title's place so the
 *  chapter controls ride in this bar instead of a separate strip at the bottom.
 *  [bookmark] drives the bookmark button — see [BookmarkButton]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibleTopBar(
    scrollBehavior: TopAppBarScrollBehavior?,
    versionLabel: String,
    onVersionClick: () -> Unit,
    bookmark: BookmarkButtonState,
    chapterNav: (@Composable () -> Unit)? = null
) {
    TopBar(
        title = chapterNav ?: { Text("Bible") },
        actions = {
            BookmarkButton(bookmark)
            // Without the end margin the pill sits flush against the screen edge.
            Button(onClick = onVersionClick, modifier = Modifier.padding(end = Spacing.sm)) {
                Text(versionLabel)
            }
        },
        scrollBehavior = scrollBehavior
    )
}

/** What the reader's bookmark button shows and does. [label] ("John 3:16") is null when there's
 *  no bookmark; [isInThisChapter] fills the icon when the bookmark is in the chapter on screen. */
class BookmarkButtonState(
    val label: String?,
    val isInThisChapter: Boolean,
    val onBookmarkHere: () -> Unit,
    val onGoToBookmark: () -> Unit,
    val onRemoveBookmark: () -> Unit
)

/** No bookmark yet: one tap bookmarks the verse at the top of the screen. Once one exists, the tap
 *  opens a menu instead — going back to it is the common case, and replacing it shouldn't happen
 *  by accident. */
@Composable
private fun BookmarkButton(state: BookmarkButtonState) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { if (state.label == null) state.onBookmarkHere() else menuOpen = true }) {
            Icon(
                imageVector = if (state.isInThisChapter) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = if (state.label == null) "Bookmark this place" else "Bookmark: ${state.label}"
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Go to ${state.label}") },
                leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    state.onGoToBookmark()
                }
            )
            DropdownMenuItem(
                text = { Text("Move bookmark here") },
                leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    state.onBookmarkHere()
                }
            )
            DropdownMenuItem(
                text = { Text("Remove bookmark") },
                leadingIcon = { Icon(Icons.Outlined.BookmarkRemove, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    state.onRemoveBookmark()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar() {
    TopBar(
        title = { Text("Home") }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar() {
    TopBar(
        title = { Text("Search") }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookPickerTopBar(onBackClick: () -> Unit) {
    TopBar(
        title = { Text("Select Book") },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersePickerTopBar(onBackClick: () -> Unit) {
    TopBar(
        title = { Text("Select Verse") },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionPickerTopBar(onBackClick: () -> Unit) {
    TopBar(
        title = { Text("Select Version") },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTopBar(onSettingsClick: () -> Unit) {
    TopBar(
        title = { Text("You") },
        actions = {
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
            }
        }
    )
}

/** Settings, and any future page under it (e.g. a language picker) — [title] is its Screen title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(title: String, onBackClick: () -> Unit) {
    TopBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginTopBar(onBackClick: () -> Unit) {
    TopBar(
        title = { Text("Sign In") },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterTopBar(onBackClick: () -> Unit) {
    TopBar(
        title = { Text("Create Account") },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTopBar(onBackClick: () -> Unit) {
    TopBar(
        title = { Text("Library") },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryItemDetailTopBar(title: String, onBackClick: () -> Unit) {
    TopBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    )
}
