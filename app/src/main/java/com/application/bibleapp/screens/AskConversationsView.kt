package com.application.bibleapp.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.utils.displayDate
import com.application.bibleapp.viewmodel.AskViewModel

/**
 * The Bible Helper's past conversations, newest first (#128), from the Ask tab's top bar. They're
 * kept only on this phone, so deleting one asks first. [onOpened] returns to the chat.
 */
@Composable
fun AskConversationsView(askViewModel: AskViewModel, modifier: Modifier = Modifier, onOpened: () -> Unit) {
    LaunchedEffect(Unit) { askViewModel.refreshConversations() }
    val conversations by askViewModel.conversations.collectAsState()
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }

    if (conversations.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically)
        ) {
            Icon(
                imageVector = Icons.Outlined.QuestionAnswer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = stringResource(R.string.ai_conversations_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(conversations, key = { it.id }) { conversation ->
                ListItem(
                    headlineContent = { Text(conversation.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(displayDate(conversation.updatedAt)) },
                    trailingContent = {
                        IconButton(onClick = { deletingId = conversation.id }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.ai_delete_conversation, conversation.title)
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        askViewModel.openConversation(conversation.id)
                        onOpened()
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    deletingId?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.ai_delete_conversation_title)) },
            text = { Text(stringResource(R.string.ai_delete_conversation_message)) },
            confirmButton = {
                TextButton(onClick = {
                    askViewModel.deleteConversation(id)
                    deletingId = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
