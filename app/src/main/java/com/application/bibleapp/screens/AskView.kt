package com.application.bibleapp.screens

import android.content.res.Configuration
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.components.AiCrisisCard
import com.application.bibleapp.components.AiNoticeSheet
import com.application.bibleapp.components.AiReportSheet
import com.application.bibleapp.components.FIND_A_HELPLINE_URL
import com.application.bibleapp.components.AiVerseCard
import com.application.bibleapp.components.AiVerseCardList
import com.application.bibleapp.components.ServerWakeHint
import com.application.bibleapp.components.rememberWaitingLong
import com.application.bibleapp.data.model.AiMessage
import com.application.bibleapp.data.model.AiRole
import com.application.bibleapp.data.remote.AiAskResult
import com.application.bibleapp.data.repository.AiHelperRepository.Companion.MAX_QUESTION_CHARS
import com.application.bibleapp.ui.theme.BibleAppTheme
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.utils.AiAnswerPart
import com.application.bibleapp.utils.AppLanguage
import com.application.bibleapp.utils.VerseReference
import com.application.bibleapp.utils.bookNameInVersion
import com.application.bibleapp.utils.citedReferences
import com.application.bibleapp.utils.formatVerseReference
import com.application.bibleapp.utils.parseAiAnswer
import com.application.bibleapp.utils.UiText
import com.application.bibleapp.utils.rememberExternalLinks
import com.application.bibleapp.utils.rememberIsOnline
import com.application.bibleapp.viewmodel.AskUiState
import com.application.bibleapp.viewmodel.AskViewModel
import com.application.bibleapp.viewmodel.BibleViewModel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date

/** The starter questions a new conversation offers (#128). */
private val STARTER_QUESTIONS = listOf(
    R.string.ai_starter_saved,
    R.string.ai_starter_baptism,
    R.string.ai_starter_trinity,
    R.string.ai_starter_prayer,
    R.string.ai_starter_start_reading,
    R.string.ai_starter_death
)

/** An AI answer usually takes 3–9 s (#122), so a longer wait than this means Render is waking up. */
private const val ASK_SLOW_AFTER_MILLIS = 10_000L

/**
 * The Ask tab (#128): the Bible Helper's chat. Signed out it explains itself and offers sign-in;
 * signed in it shows the open conversation (or starter questions), every way a question can fail,
 * and the one-time notice before the first question. Every answer can be reported, and one the
 * server flagged as a crisis shows the help card above it (#130). [onReadChapter] opens the reader at a cited
 * passage, the way a Search result does. [onWakeServer] starts Render's cold start while the person
 * types (#87). [onSessionEnded] runs when the server says the session is gone, so the app re-checks
 * whether it's still signed in (the refresh that failed has already cleared the tokens).
 */
@Composable
fun AskView(
    askViewModel: AskViewModel,
    bibleViewModel: BibleViewModel,
    isLoggedIn: Boolean,
    modifier: Modifier = Modifier,
    onSignInClick: () -> Unit,
    onWakeServer: () -> Unit,
    onSessionEnded: () -> Unit,
    onReadChapter: (bookId: Int, chapter: Int, verse: Int) -> Unit
) {
    LaunchedEffect(isLoggedIn) {
        askViewModel.onSignInChanged(isLoggedIn)
        if (isLoggedIn) onWakeServer()
    }
    if (!isLoggedIn) {
        AskSignedOut(onSignInClick = onSignInClick, modifier = modifier)
        return
    }

    val state by askViewModel.state.collectAsState()
    val draft by askViewModel.draft.collectAsState()
    val noticeAccepted by askViewModel.noticeAccepted.collectAsState()
    val language = AppLanguage.answerLanguageTag(LocalConfiguration.current.locales[0])
    val online = rememberIsOnline()
    LaunchedEffect(state.failure) {
        if (state.failure == AiAskResult.SignedOut) onSessionEnded()
    }

    // Before the first question; until "Got it", it comes back at the next one.
    var noticeOpen by rememberSaveable { mutableStateOf(!noticeAccepted) }
    var sendAfterNotice by rememberSaveable { mutableStateOf(false) }
    val send = {
        if (noticeAccepted) {
            askViewModel.send(language)
        } else {
            sendAfterNotice = true
            noticeOpen = true
        }
    }
    if (noticeOpen) {
        AiNoticeSheet(
            onAccept = {
                askViewModel.acceptNotice()
                noticeOpen = false
                if (sendAfterNotice) askViewModel.send(language)
                sendAfterNotice = false
            },
            onDismiss = {
                noticeOpen = false
                sendAfterNotice = false
            }
        )
    }

    val selectedVersion by bibleViewModel.selectedVersion.collectAsState()
    val bookNames by bibleViewModel.bookNames.collectAsState()
    val chapterStructure by bibleViewModel.chapterStructure.collectAsState()
    val bookName = { id: Int -> bookNameInVersion(selectedVersion.id, id, mapOf(selectedVersion.id to bookNames)) }
    val links = rememberExternalLinks { bibleViewModel.showMessage(it) }

    var reportingId by rememberSaveable { mutableStateOf<Long?>(null) }
    reportingId?.let { answerId ->
        AiReportSheet(
            send = { reason, comment -> askViewModel.report(answerId, reason, comment, language) },
            onSent = {
                reportingId = null
                bibleViewModel.showMessage(UiText.Res(R.string.ai_report_sent))
            },
            onDismiss = { reportingId = null }
        )
    }

    AskChat(
        state = state,
        draft = draft,
        online = online,
        modifier = modifier,
        onDraftChange = askViewModel::onDraftChange,
        onSend = send,
        onStarter = { question ->
            askViewModel.onDraftChange(question)
            send()
        },
        onRetry = { askViewModel.retry(language) },
        onSignInClick = onSignInClick,
        onRemovePassage = askViewModel::removePassage,
        answer = { message ->
            AiAnswer(
                message = message,
                parts = parseAiAnswer(answerWithBullets(message.content), chapterStructure),
                bookName = bookName,
                onReference = { onReadChapter(it.bookId, it.chapter, it.startVerse ?: 1) },
                onReport = { reportingId = message.id },
                onFindHelpline = { links.openPage(FIND_A_HELPLINE_URL) },
                verseCard = { reference -> AiVerseCard(reference, bibleViewModel, onReadChapter) }
            )
        }
    )
}

/** The chat without its view models, so previews can draw every state. */
@Composable
private fun AskChat(
    state: AskUiState,
    draft: String,
    online: Boolean,
    modifier: Modifier,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStarter: (String) -> Unit,
    onRetry: () -> Unit,
    onSignInClick: () -> Unit,
    onRemovePassage: () -> Unit,
    answer: @Composable (AiMessage) -> Unit
) {
    val listState = rememberLazyListState()
    val waitingLong = rememberWaitingLong(waiting = state.isSending, afterMillis = ASK_SLOW_AFTER_MILLIS)
    // A new question scrolls down to the "Thinking…" row; its answer then shows from its first line.
    // Indexes from the state, not the list's layout, which hasn't caught up yet when this runs.
    // With messages there's no intro item, so the "Thinking…" row or failure card is right after them.
    // The server-waking hint makes that row taller later, so it scrolls again then (#213).
    LaunchedEffect(state.messages.size, state.isSending, state.failure, waitingLong) {
        val lastQuestion = state.messages.indexOfLast { it.role == AiRole.USER }
        when {
            state.messages.isEmpty() -> Unit
            state.isSending || state.failure != null -> listState.animateScrollToItem(state.messages.size)
            lastQuestion >= 0 -> listState.animateScrollToItem(lastQuestion)
        }
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        AnimatedVisibility(visible = !online) { OfflineBanner() }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            if (state.messages.isEmpty() && !state.isSending) {
                // With a passage attached the question is already written; starters would only distract.
                item(key = "intro") { AskIntro(onStarter = onStarter, enabled = online, showStarters = state.passage == null) }
            }
            items(state.messages, key = { it.id }) { message ->
                if (message.role == AiRole.USER) QuestionBubble(message.content) else answer(message)
            }
            if (state.isSending) item(key = "thinking") { Thinking(waitingLong) }
            state.failure?.let { failure ->
                item(key = "failure") {
                    FailureCard(failure = failure, online = online, onRetry = onRetry, onSignInClick = onSignInClick)
                }
            }
        }
        // Not under the daily-limit card, which already says it.
        state.remainingToday?.takeIf { it <= REMAINING_SHOWN_FROM && state.failure !is AiAskResult.DailyLimit }?.let { remaining ->
            Text(
                text = pluralStringResource(R.plurals.ai_remaining_today, remaining, remaining),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg)
            )
        }
        // In landscape the chip sits beside the field instead of on a row of its own, which left
        // the conversation a line or two of the little height there is (#210).
        val compact = isCompactHeight()
        val passage = state.passage
        if (passage != null && !compact) {
            PassageChip(
                passage.label,
                onRemove = onRemovePassage,
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs)
            )
        }
        QuestionField(
            draft = draft,
            canSend = draft.isNotBlank() && !state.isSending && online,
            onDraftChange = onDraftChange,
            onSend = onSend,
            leading = if (passage != null && compact) {
                {
                    PassageChip(
                        passage.label,
                        onRemove = onRemovePassage,
                        modifier = Modifier.padding(end = Spacing.sm).widthIn(max = PASSAGE_CHIP_MAX_WIDTH)
                    )
                }
            } else {
                null
            }
        )
    }
}

/** "Questions left today" shows once this few remain (#128). */
private const val REMAINING_SHOWN_FROM = 3

/** In landscape the passage chip shares the field's row; past this a long reference ellipsizes (#210). */
private val PASSAGE_CHIP_MAX_WIDTH = 280.dp

@Composable
private fun AskSignedOut(onSignInClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically)
    ) {
        Icon(
            imageVector = Icons.Outlined.QuestionAnswer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = stringResource(R.string.ai_signed_out_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.ai_signed_out_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(onClick = onSignInClick) { Text(stringResource(R.string.action_sign_in)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskIntro(onStarter: (String) -> Unit, enabled: Boolean, showStarters: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(text = stringResource(R.string.ai_intro_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.ai_intro_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showStarters) FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            STARTER_QUESTIONS.forEach { res ->
                val question = stringResource(res)
                SuggestionChip(onClick = { onStarter(question) }, label = { Text(question) }, enabled = enabled)
            }
        }
    }
}

@Composable
private fun QuestionBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(start = Spacing.xxl), contentAlignment = Alignment.CenterEnd) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
            )
        }
    }
}

/**
 * One answer: the crisis help card first when the server flagged it (#130), the "AI answer" label
 * (plan section 7), the text with each cited passage as a link, a verse card for each one under it
 * (#129), and the report action (#130). A whole book shows as its name (#190).
 */
@Composable
private fun AiAnswer(
    message: AiMessage,
    parts: List<AiAnswerPart>,
    bookName: (Int) -> String,
    onReference: (VerseReference) -> Unit,
    onReport: () -> Unit,
    onFindHelpline: () -> Unit,
    verseCard: @Composable (VerseReference) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (message.crisis) AiCrisisCard(onFindHelpline = onFindHelpline)
        // A crisis answer can be the help card alone: the server sends no text when the model
        // wrote only the crisis marker (docs/api_contract.md).
        if (message.content.isNotBlank()) {
            AiAnswerLabel()
            Text(
                text = answerText(parts, bookName, MaterialTheme.colorScheme.primary, onReference),
                style = MaterialTheme.typography.bodyLarge
            )
            AiVerseCardList(parts.citedReferences()) { verseCard(it) }
            // Inside: a report needs an answer to send.
            TextButton(onClick = onReport, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Outlined.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.ai_action_report), modifier = Modifier.padding(start = Spacing.xs))
            }
        }
    }
}

@Composable
private fun AiAnswerLabel() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = stringResource(R.string.ai_answer_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The instructions allow simple lists that start with "- " (system_prompt.md); show them as bullets. */
private fun answerWithBullets(answer: String): String = answer.replace(Regex("(?m)^[ \t]*[-*] "), "• ")

private fun answerText(
    parts: List<AiAnswerPart>,
    bookName: (Int) -> String,
    linkColor: Color,
    onReference: (VerseReference) -> Unit
): AnnotatedString = buildAnnotatedString {
    val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium))
    parts.forEach { part ->
        when (part) {
            is AiAnswerPart.Text -> append(part.text)
            is AiAnswerPart.Book -> append(bookName(part.bookId))
            is AiAnswerPart.Reference -> withLink(
                LinkAnnotation.Clickable(tag = "verse", styles = linkStyle) { onReference(part.reference) }
            ) {
                append(formatVerseReference(part.reference, bookName))
            }
        }
    }
}

@Composable
private fun Thinking(waitingLong: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        AiAnswerLabel()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.ai_thinking),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (waitingLong) ServerWakeHint()
    }
}

/** Why the last question has no answer, and what can be done about it. */
@Composable
private fun FailureCard(failure: AiAskResult.Failure, online: Boolean, onRetry: () -> Unit, onSignInClick: () -> Unit) {
    val message = when (failure) {
        AiAskResult.Offline -> stringResource(R.string.ai_failure_offline)
        AiAskResult.Unavailable -> stringResource(R.string.ai_failure_unavailable)
        AiAskResult.RateLimited -> stringResource(R.string.ai_failure_rate_limited)
        AiAskResult.SignedOut -> stringResource(R.string.ai_failure_signed_out)
        is AiAskResult.DailyLimit -> stringResource(R.string.ai_failure_daily_limit, resetTimeText(failure.resetsAtMillis))
        is AiAskResult.Resting -> stringResource(R.string.ai_failure_resting, resetTimeText(failure.resetsAtMillis))
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = Spacing.lg, top = Spacing.md, end = Spacing.sm, bottom = Spacing.xs)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Spacing.sm)
            )
            when (failure) {
                AiAskResult.Offline, AiAskResult.Unavailable, AiAskResult.RateLimited ->
                    TextButton(onClick = onRetry, enabled = online, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.action_retry))
                    }
                AiAskResult.SignedOut ->
                    TextButton(onClick = onSignInClick, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.action_sign_in))
                    }
                // Trying again before the reset can't help.
                is AiAskResult.DailyLimit, is AiAskResult.Resting -> Box(modifier = Modifier.padding(bottom = Spacing.sm))
            }
        }
    }
}

/** When the daily limits reset (00:00 UTC), as a weekday and time in the phone's time zone and
 *  12/24-hour setting: "Tue 7:00 PM". */
@Composable
private fun resetTimeText(epochMillis: Long): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val skeleton = if (DateFormat.is24HourFormat(context)) "EEEHm" else "EEEhm"
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(epochMillis))
}

@Composable
private fun OfflineBanner() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.ai_offline_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/** The verses "Ask about this verse" attached (#132), above the field (beside it in landscape, #210) while they go with each question. */
@Composable
private fun PassageChip(label: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ai_passage_remove, label), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun QuestionField(
    draft: String,
    canSend: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    val numbers = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    val counter = stringResource(R.string.ai_question_counter, numbers.format(draft.length), numbers.format(MAX_QUESTION_CHARS))
    // In landscape the counter goes inside the field, and the field grows less, so the
    // conversation keeps most of the little height there is.
    val compact = isCompactHeight()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text(stringResource(R.string.ai_question_placeholder)) },
            suffix = if (compact) {
                { Text(counter, style = MaterialTheme.typography.bodySmall) }
            } else {
                null
            },
            supportingText = if (compact) {
                null
            } else {
                { Text(text = counter, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) }
            },
            maxLines = if (compact) 2 else 5,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onSend,
            enabled = canSend,
            // Level with the field, not with the counter under it.
            modifier = if (compact) Modifier else Modifier.padding(bottom = Spacing.lg)
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.ai_action_send))
        }
    }
}

@Preview(name = "Light", widthDp = 360, heightDp = 720)
@Preview(name = "Dark", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Landscape", widthDp = 780, heightDp = 360)
@Composable
private fun AskChatPreview() {
    val messages = listOf(
        AiMessage(1, 1, AiRole.USER, "What is the Trinity?", createdAt = ""),
        AiMessage(2, 1, AiRole.ASSISTANT, "One God in three persons: the Father, the Son and the Holy Spirit [[MAT 28:19]].\n- The [[PSA]] praise him.", createdAt = ""),
        AiMessage(3, 1, AiRole.USER, "How should I pray?", createdAt = "")
    )
    BibleAppTheme {
        Surface {
            AskChat(
                state = AskUiState(conversationId = 1, messages = messages, failure = AiAskResult.Unavailable, remainingToday = 2),
                draft = "Why",
                online = false,
                modifier = Modifier,
                onDraftChange = {},
                onSend = {},
                onStarter = {},
                onRetry = {},
                onSignInClick = {},
                onRemovePassage = {},
                answer = { message ->
                    AiAnswer(
                        message = message,
                        parts = parseAiAnswer(answerWithBullets(message.content)),
                        bookName = { if (it == 40) "Matthew" else "Psalms" },
                        onReference = {},
                        onReport = {},
                        onFindHelpline = {},
                        verseCard = {}
                    )
                }
            )
        }
    }
}
