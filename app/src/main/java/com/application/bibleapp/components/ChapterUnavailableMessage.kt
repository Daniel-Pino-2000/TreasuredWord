package com.application.bibleapp.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.application.bibleapp.R
import com.application.bibleapp.data.repository.ChapterProblem
import com.application.bibleapp.ui.theme.Spacing

/**
 * Shown in place of a YouVersion chapter (#366) that couldn't be fetched: offline, too many
 * requests for the app's key, or anything else. Calm on purpose, with a way to retry and a way
 * to a downloaded version, which always works offline. Scrolls, so it fits in landscape.
 */
@Composable
fun ChapterUnavailableMessage(
    problem: ChapterProblem,
    onRetry: () -> Unit,
    onChooseVersion: () -> Unit,
    modifier: Modifier = Modifier
) {
    val message = when (problem) {
        ChapterProblem.OFFLINE -> R.string.youversion_chapter_offline
        ChapterProblem.BUSY -> R.string.youversion_busy
        ChapterProblem.UNAVAILABLE -> R.string.youversion_chapter_failed
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically)
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        OutlinedButton(onClick = onChooseVersion) { Text(stringResource(R.string.youversion_choose_downloaded)) }
    }
}
