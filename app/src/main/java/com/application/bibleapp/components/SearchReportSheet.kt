package com.application.bibleapp.components

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.data.remote.SearchReportResult
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.utils.AppLanguage
import com.application.bibleapp.utils.VerseReference
import com.application.bibleapp.utils.formatVerseReference
import com.application.bibleapp.viewmodel.SearchReportDraft
import kotlinx.coroutines.launch

/**
 * "These results didn't help" (#402): shows everything the report sends, and sends it only on
 * Send. [language] is the AppLanguage tag it goes with, [versionLabel] names the draft's Bible
 * version and [bookName] its books. [send] posts it; [onSent] closes the sheet and thanks the
 * person on the screen's snackbar. A failure (offline, too many) is said in the sheet's own
 * snackbar, since the sheet is drawn over the screen's, and the sheet stays open to try again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchReportSheet(
    draft: SearchReportDraft,
    language: String,
    appVersion: String,
    versionLabel: String,
    bookName: (Int) -> String,
    send: suspend () -> SearchReportResult,
    onSent: () -> Unit,
    onDismiss: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    var sending by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.large,
        // Only matters once the sheet is as tall as the window (landscape): stop below the
        // status bar instead of running the drag handle up under it.
        modifier = Modifier.statusBarsPadding()
    ) {
        Box {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                    .padding(bottom = if (isCompactHeight()) Spacing.sm else Spacing.xl)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Text(
                    text = stringResource(R.string.search_report_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.search_report_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SentField(stringResource(R.string.search_report_query), draft.query)
                SentField(stringResource(R.string.search_report_language), AppLanguage.displayName(language))
                SentField(stringResource(R.string.search_report_bible_version), versionLabel)
                SentField(stringResource(R.string.search_report_app_version), appVersion)
                if (draft.keyPassages.isNotEmpty()) {
                    SentField(stringResource(R.string.search_section_key_passages), referenceList(draft.keyPassages, bookName))
                }
                SentField(
                    stringResource(R.string.search_section_meaning),
                    referenceList(draft.results, bookName).ifEmpty { stringResource(R.string.search_report_none) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Button(
                        onClick = {
                            sending = true
                            scope.launch {
                                val result = send()
                                sending = false
                                if (result == SearchReportResult.SENT) {
                                    onSent()
                                } else {
                                    snackbarHostState.showSnackbar(resources.getString(failureMessage(result)))
                                }
                            }
                        },
                        enabled = !sending
                    ) {
                        if (sending) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.search_report_send))
                        }
                    }
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            )
        }
    }
}

/** One thing the report sends: what it is, then exactly what goes. */
@Composable
private fun SentField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** The references as the screen showed them, "John 3:16, Psalm 73"; "" for none. */
private fun referenceList(references: List<VerseReference>, bookName: (Int) -> String): String =
    references.joinToString(", ") { formatVerseReference(it, bookName) }

/** What the sheet's snackbar says when the report didn't go through. */
private fun failureMessage(result: SearchReportResult): Int = when (result) {
    SearchReportResult.OFFLINE -> R.string.search_report_offline
    SearchReportResult.RATE_LIMITED -> R.string.search_report_rate_limited
    else -> R.string.search_report_failed
}
