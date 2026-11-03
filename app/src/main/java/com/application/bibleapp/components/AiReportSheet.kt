package com.application.bibleapp.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.data.remote.AiAskResult
import com.application.bibleapp.data.remote.AiReportReason
import com.application.bibleapp.data.remote.AiReportResult
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.isCompactHeight
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** The contract's limit for a report's comment (docs/api_contract.md, #125). */
const val AI_REPORT_COMMENT_MAX_CHARS = 500

private val REASONS = listOf(
    AiReportReason.WRONG_TEACHING to R.string.ai_report_reason_wrong_teaching,
    AiReportReason.WRONG_VERSE to R.string.ai_report_reason_wrong_verse,
    AiReportReason.HARMFUL to R.string.ai_report_reason_harmful,
    AiReportReason.OTHER to R.string.ai_report_reason_other
)

/**
 * Reports a Bible Helper answer without leaving the app (#130), as Google Play's AI-Generated
 * Content policy requires: a reason, an optional comment, and what will be sent. [send] posts it
 * and returns how it went; [onSent] closes the sheet and thanks the person on the screen's
 * snackbar. Anything else is said in the sheet's own snackbar, since the sheet is drawn over the
 * screen's, and the sheet stays open to try again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiReportSheet(
    send: suspend (reason: AiReportReason, comment: String?) -> AiReportResult,
    onSent: () -> Unit,
    onDismiss: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    var reason by rememberSaveable { mutableStateOf<AiReportReason?>(null) }
    var comment by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val numbers = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.statusBarsPadding()
    ) {
        Box {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                    .padding(bottom = if (isCompactHeight()) Spacing.sm else Spacing.xl)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.ai_report_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.selectableGroup()) {
                    REASONS.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = reason == value, onClick = { reason = value }, role = Role.RadioButton)
                                .padding(vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = reason == value, onClick = null)
                            Text(
                                text = stringResource(label),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = Spacing.md)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(AI_REPORT_COMMENT_MAX_CHARS) },
                    label = { Text(stringResource(R.string.ai_report_comment_label)) },
                    supportingText = {
                        Text(
                            text = stringResource(
                                R.string.ai_question_counter,
                                numbers.format(comment.length),
                                numbers.format(AI_REPORT_COMMENT_MAX_CHARS)
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End
                        )
                    },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.ai_report_what_is_sent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val chosen = reason ?: return@Button
                        sending = true
                        scope.launch {
                            val result = send(chosen, comment.trim().takeIf { it.isNotEmpty() })
                            sending = false
                            if (result == AiReportResult.Sent) {
                                onSent()
                            } else {
                                snackbarHostState.showSnackbar(resources.getString(reportFailureMessage(result)))
                            }
                        }
                    },
                    enabled = reason != null && !sending,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
                ) {
                    if (sending) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.ai_report_send))
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

/** What the sheet's snackbar says when a report didn't go through. */
private fun reportFailureMessage(result: AiReportResult): Int = when (result) {
    AiAskResult.Offline -> R.string.ai_report_offline
    AiAskResult.RateLimited -> R.string.ai_report_rate_limited
    // Unavailable, or a session that has ended: nothing the sheet can fix, but trying again may.
    else -> R.string.ai_report_failed
}
