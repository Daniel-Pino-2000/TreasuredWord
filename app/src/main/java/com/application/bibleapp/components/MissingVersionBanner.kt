package com.application.bibleapp.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.viewmodel.BibleViewModel

/**
 * One banner per translation that highlights or notes on screen were made in but that isn't on
 * this device (#78) — e.g. after a reinstall brought them back. Their verses are shown in KJV
 * meanwhile; "Download" gets the real text in one tap, and the screen updates by itself once
 * it's in. Shows nothing when every translation in [versionIds] is downloaded.
 */
@Composable
fun MissingVersionBanners(
    bibleViewModel: BibleViewModel,
    versionIds: Set<String>,
    modifier: Modifier = Modifier
) {
    val missingLabels by bibleViewModel.libraryMissingVersionLabels.collectAsState()
    val downloadingVersionId by bibleViewModel.downloadingVersionId.collectAsState()
    val downloadProgress by bibleViewModel.downloadProgress.collectAsState()
    val shown = missingLabels.filterKeys { it in versionIds }
    if (shown.isEmpty()) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        shown.forEach { (versionId, label) ->
            MissingVersionBanner(
                versionLabel = label,
                downloadProgress = downloadProgress.takeIf { downloadingVersionId == versionId },
                downloadEnabled = downloadingVersionId == null,
                onDownload = { bibleViewModel.downloadMissingVersion(versionId) }
            )
        }
    }
}

/** [downloadProgress] is null unless this translation is downloading. */
@Composable
private fun MissingVersionBanner(
    versionLabel: String,
    downloadProgress: Float?,
    downloadEnabled: Boolean,
    onDownload: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.library_missing_version, versionLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                if (downloadProgress != null) {
                    Text(
                        text = stringResource(R.string.version_download_progress, (downloadProgress * 100).toInt()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.md)
                    )
                } else {
                    TextButton(onClick = onDownload, enabled = downloadEnabled) {
                        Text(stringResource(R.string.action_download))
                    }
                }
            }
            if (downloadProgress != null) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm, end = Spacing.sm, bottom = Spacing.xs)
                )
            }
        }
    }
}
