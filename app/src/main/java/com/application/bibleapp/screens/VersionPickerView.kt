package com.application.bibleapp.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.application.bibleapp.data.model.BUNDLED_TRANSLATION
import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.DownloadedVersionInfo
import com.application.bibleapp.data.model.SelectedBibleVersion
import com.application.bibleapp.data.remote.downloadedSection
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.R
import androidx.compose.ui.res.stringResource
import com.application.bibleapp.utils.asString

/**
 * Renders each translation with one of three states, purely from what
 * [BibleViewModel.selectedVersion] and [BibleViewModel.downloadedVersions] say —
 * this composable never queries local storage itself:
 * - active (blue checkmark) — `version.id == selectedVersion.id`. Never offered a
 *   delete action, since it's always also downloaded.
 * - downloaded, not active (delete icon + "Downloaded", or a refresh icon +
 *   "Update available" if [com.application.bibleapp.data.model.DownloadedVersionInfo.isUpToDate]
 *   is false) — `version.id` is a key in `downloadedVersions`.
 * - neither (download icon) — tapping the row downloads then switches; tapping the
 *   icon downloads without switching away from whatever's currently active.
 *
 * Downloaded translations get a shortcut "Downloaded" section at the top, regardless
 * of language, so they're never buried inside a language group the user would have to
 * scroll to find. They also still appear under their own language group below — this
 * is a deliberate duplicate listing, not a bug, so browsing by language always shows
 * every translation for that language in one place.
 *
 * The bundled KJV ([BUNDLED_TRANSLATION]) heads that section as a fourth state, "Built in",
 * with no download or delete action (#56). The section is built from what's on the device,
 * not from the catalog, so it stays usable when the catalog can't load offline and lists
 * downloads the catalog no longer has (#63).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VersionPickerView(
    bibleViewModel: BibleViewModel,
    modifier: Modifier = Modifier,
    onVersionClicked: () -> Unit
) {
    val versions by bibleViewModel.availableVersions.collectAsState()
    val groupedVersions by bibleViewModel.groupedVersions.collectAsState()
    val searchQuery by bibleViewModel.versionSearchQuery.collectAsState()
    val isLoading by bibleViewModel.isLoadingVersions.collectAsState()
    val error by bibleViewModel.versionsError.collectAsState()
    val selectedVersion by bibleViewModel.selectedVersion.collectAsState()
    val downloadedVersions by bibleViewModel.downloadedVersions.collectAsState()
    val downloadingVersionId by bibleViewModel.downloadingVersionId.collectAsState()
    val downloadProgress by bibleViewModel.downloadProgress.collectAsState()
    val downloadError by bibleViewModel.downloadError.collectAsState()
    val downloadInfo by bibleViewModel.downloadInfo.collectAsState()

    // Deleting is destructive (it wipes local content, not just this screen's state),
    // so it's confirmed here rather than firing straight off the row's icon tap.
    var versionPendingDelete by remember { mutableStateOf<BibleTranslation?>(null) }

    versionPendingDelete?.let { version ->
        AlertDialog(
            onDismissRequest = { versionPendingDelete = null },
            title = { Text(stringResource(R.string.version_remove_title, version.displayName)) },
            text = { Text(stringResource(R.string.version_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    bibleViewModel.deleteVersion(version.id)
                    versionPendingDelete = null
                }) {
                    Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { versionPendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        // A failed or empty catalog (offline with no cached copy) only hides the catalog: what's
        // already on the device can still be opened or removed (#56, #63).
        val catalogUnavailable = error != null || versions.isEmpty()

        if (catalogUnavailable) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            ) {
                if (error != null) {
                    Text(stringResource(R.string.version_load_failed), color = MaterialTheme.colorScheme.error)
                } else {
                    Text(stringResource(R.string.version_none_available))
                }
                Text(
                    text = stringResource(R.string.tap_to_retry),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = Spacing.sm)
                        .clickable { bibleViewModel.loadAvailableVersions() }
                )
            }
        } else {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = bibleViewModel::onVersionSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                placeholder = { Text(stringResource(R.string.version_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )
        }

        // Download error banner
        downloadError?.let {
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                Text(text = stringResource(R.string.version_download_failed, it.asString()), color = MaterialTheme.colorScheme.error)
                Text(
                    text = stringResource(R.string.version_download_retry_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Download info banner (success, but some content wasn't available for this version)
        downloadInfo?.let {
            Text(
                text = it.asString(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
        }

        if (!catalogUnavailable && groupedVersions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.version_no_match, searchQuery))
            }
            return@Column
        }

        // Downloaded translations get a shortcut section up top for fast access without
        // scrolling when just browsing (order preserved from the language-sorted
        // groups), and also still appear a second time under their own language group
        // below, so browsing by language never looks like a version is "missing" from
        // where you'd expect it. Once a search is active, that shortcut stops earning
        // its keep — a match's Downloaded/Active status is already on the row, so
        // duplicating it up top is just noise — so the section only shows up empty-query.
        val downloadedTranslations = when {
            catalogUnavailable -> downloadedSection(emptyList(), downloadedVersions)
            searchQuery.isBlank() -> downloadedSection(groupedVersions, downloadedVersions)
            else -> emptyList()
        }
        // Only these can be re-downloaded; a download the catalog doesn't list can only be removed (#63).
        val catalogIds = versions.mapTo(HashSet()) { it.id }

        LazyColumn {
            if (downloadedTranslations.isNotEmpty()) {
                stickyHeader {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                            text = stringResource(R.string.version_section_downloaded),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                        )
                    }
                }

                // Keys are namespaced per section — a downloaded translation's id also
                // appears in its language group below, and LazyColumn keys must be
                // unique across the whole list, not just within one items() call.
                items(downloadedTranslations, key = { "downloaded-${it.id}" }) { version ->
                    VersionRow(
                        version = version,
                        selectedVersion = selectedVersion,
                        downloadingVersionId = downloadingVersionId,
                        downloadProgress = downloadProgress,
                        downloadedVersions = downloadedVersions,
                        isInCatalog = version.id in catalogIds,
                        bibleViewModel = bibleViewModel,
                        onVersionClicked = onVersionClicked,
                        onDeleteClicked = { versionPendingDelete = it }
                    )
                }
            }

            if (!catalogUnavailable) groupedVersions.forEach { group ->
                stickyHeader {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                            text = group.languageName,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                        )
                    }
                }

                items(group.translations, key = { "lang-${it.id}" }) { version ->
                    VersionRow(
                        version = version,
                        selectedVersion = selectedVersion,
                        downloadingVersionId = downloadingVersionId,
                        downloadProgress = downloadProgress,
                        downloadedVersions = downloadedVersions,
                        isInCatalog = version.id in catalogIds,
                        bibleViewModel = bibleViewModel,
                        onVersionClicked = onVersionClicked,
                        onDeleteClicked = { versionPendingDelete = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionRow(
    version: BibleTranslation,
    selectedVersion: SelectedBibleVersion,
    downloadingVersionId: String?,
    downloadProgress: Float,
    downloadedVersions: Map<String, DownloadedVersionInfo>,
    isInCatalog: Boolean,
    bibleViewModel: BibleViewModel,
    onVersionClicked: () -> Unit,
    onDeleteClicked: (BibleTranslation) -> Unit
) {
    val isSelected = version.id == selectedVersion.id
    val isDownloading = version.id == downloadingVersionId
    val isBundled = version.id == BUNDLED_TRANSLATION.id
    val downloadInfoForVersion = downloadedVersions[version.id]
    val isDownloaded = isBundled || downloadInfoForVersion != null
    // Re-downloading needs the catalog to still list this id (#63).
    val needsUpdate = isInCatalog && downloadInfoForVersion != null && !downloadInfoForVersion.isUpToDate

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = downloadingVersionId == null) {
                    // Only leave this screen once the version is actually ready —
                    // otherwise the download progress/error never gets seen. Tapping
                    // the already-active version is a no-op (no re-fetch/re-switch),
                    // it just finishes immediately and lets the caller dismiss the picker.
                    bibleViewModel.selectVersion(version.id) { success ->
                        if (success) onVersionClicked()
                    }
                }
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = version.displayName, style = MaterialTheme.typography.bodyLarge)
                version.nativeName.takeIf { it.isNotBlank() && it != version.displayName }?.let { nativeName ->
                    Text(
                        text = nativeName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (needsUpdate) {
                    Text(
                        text = stringResource(R.string.version_update_available),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (isDownloaded) {
                    // Shown whether or not this row is the active version — keeping this
                    // line present in both cases means selecting a downloaded row doesn't
                    // change the row's height (it used to disappear on selection, which
                    // made the list visibly jump).
                    Text(
                        text = stringResource(
                            when {
                                isSelected -> R.string.version_status_active
                                isBundled -> R.string.version_status_built_in
                                else -> R.string.version_status_downloaded
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isDownloading) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.version_download_progress, (downloadProgress * 100).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (needsUpdate) {
                IconButton(
                    enabled = downloadingVersionId == null,
                    onClick = { bibleViewModel.redownloadVersion(version.id) }
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.version_redownload, version.displayName),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (isDownloaded && !isSelected && !isBundled) {
                // Never offered for the active version — selecting a version always
                // downloads it first, so "downloaded and selected" always holds together.
                // Nor for the bundled KJV, which ships with the app (#56).
                IconButton(
                    enabled = downloadingVersionId == null,
                    onClick = { onDeleteClicked(version) }
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.version_remove_downloaded, version.displayName),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (!isDownloaded && !isSelected) {
                // Downloads without switching — tapping the row itself still downloads
                // and switches, same as before this button existed.
                IconButton(
                    enabled = downloadingVersionId == null,
                    onClick = { bibleViewModel.downloadVersion(version.id) }
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = stringResource(R.string.version_download, version.displayName),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isSelected) {
                // Sized to match the IconButtons above (default 48.dp touch target) so the
                // checkmark lines up with them instead of sitting further out toward the
                // row's edge.
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.version_currently_active),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}