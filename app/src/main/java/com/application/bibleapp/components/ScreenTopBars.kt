package com.application.bibleapp.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable

/** [versionLabel] is the active translation's short code (e.g. "KJV", "ESV") — see
 *  [com.application.bibleapp.viewmodel.BibleViewModel.currentVersionLabel]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibleTopBar(
    scrollBehavior: TopAppBarScrollBehavior?,
    versionLabel: String,
    onVersionClick: () -> Unit
) {
    TopBar(
        title = { Text("Bible") },
        actions = {
            Button(onClick = onVersionClick) {
                Text(versionLabel)
            }
        },
        scrollBehavior = scrollBehavior
    )
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
fun SettingsTopBar() {
    TopBar(
        title = { Text("Settings") }
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
