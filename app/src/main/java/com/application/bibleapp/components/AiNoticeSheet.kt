package com.application.bibleapp.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Church
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.utils.PRIVACY_POLICY_URL
import com.application.bibleapp.utils.rememberExternalLinks
import kotlinx.coroutines.launch

/** The Baptist Faith and Message 2000 on the Southern Baptist Convention's site, which the Bible
 *  Helper's doctrine summary follows. Checked by hand 2026-11-02 (it blocks curl, so in a browser). */
const val BFM_2000_URL = "https://bfm.sbc.net/bfm2000/"

/**
 * Shown before the first question (docs/ai_helper_plan.md section 7, #128): it's an AI, whose
 * perspective it answers from, to check it against Scripture, not to share personal details, and
 * the privacy policy. Only [onAccept] ("Got it") is remembered; closing it any other way shows it
 * again at the next question.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiNoticeSheet(onAccept: () -> Unit, onDismiss: () -> Unit) {
    // Its own snackbar, since the sheet is drawn over the screen's: a link that can't open says so here.
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
                    text = stringResource(R.string.ai_notice_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                NoticePoint(Icons.Outlined.AutoAwesome, stringResource(R.string.ai_notice_ai))
                NoticePoint(Icons.Outlined.Church, stringResource(R.string.ai_notice_baptist)) {
                    LinkButton(stringResource(R.string.ai_notice_bfm_link), stringResource(R.string.source_bfm)) {
                        links.openPage(BFM_2000_URL)
                    }
                }
                NoticePoint(Icons.AutoMirrored.Outlined.MenuBook, stringResource(R.string.ai_notice_scripture))
                NoticePoint(Icons.Outlined.PrivacyTip, stringResource(R.string.ai_notice_personal)) {
                    LinkButton(stringResource(R.string.settings_privacy_policy), source = null) {
                        links.openPage(PRIVACY_POLICY_URL)
                    }
                }
                Button(onClick = onAccept, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
                    Text(stringResource(R.string.ai_notice_accept))
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

@Composable
private fun NoticePoint(icon: ImageVector, text: String, link: (@Composable () -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp).size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            link?.invoke()
        }
    }
}

/** A link that leaves the app, with its [source] named beside it when it's someone else's site. */
@Composable
private fun LinkButton(label: String, source: String?, onClick: () -> Unit) {
    // Pulled back by its padding so the link lines up with the text above it. Small corners: the
    // default pill shape clips the first letter of a link that wraps.
    TextButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.sm),
        modifier = Modifier.offset(x = -Spacing.xs)
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(label)
            source?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            modifier = Modifier.padding(start = Spacing.xs).size(16.dp)
        )
    }
}
