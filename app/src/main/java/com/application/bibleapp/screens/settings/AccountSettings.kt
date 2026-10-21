package com.application.bibleapp.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.application.bibleapp.components.GroupedDivider
import com.application.bibleapp.components.GroupedRow
import com.application.bibleapp.components.GroupedSection
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.viewmodel.AuthViewModel
import com.application.bibleapp.viewmodel.BibleViewModel

@Composable
internal fun AccountSettings(
    authViewModel: AuthViewModel,
    bibleViewModel: BibleViewModel,
    onSignInClick: () -> Unit
) {
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
    val currentUser by authViewModel.currentUser.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    GroupedSection(title = "Account") {
        if (!isLoggedIn) {
            GroupedRow(
                title = "Sign in",
                subtitle = "Sync your highlights, notes, and reading progress across devices",
                icon = Icons.AutoMirrored.Outlined.Login,
                onClick = onSignInClick
            )
        } else {
            GroupedRow(
                title = currentUser?.email ?: "Your account",
                subtitle = "Signed in",
                icon = Icons.Outlined.Person
            )
            GroupedDivider()
            GroupedRow(
                title = "Sign out",
                icon = Icons.AutoMirrored.Outlined.Logout,
                showChevron = false,
                onClick = { authViewModel.logout() }
            )
            GroupedDivider()
            GroupedRow(
                title = "Delete account",
                titleColor = MaterialTheme.colorScheme.error,
                icon = Icons.Outlined.DeleteForever,
                iconTint = MaterialTheme.colorScheme.error,
                showChevron = false,
                onClick = { showDeleteDialog = true }
            )
        }
    }

    if (showDeleteDialog) {
        DeleteAccountDialog(
            onDismiss = { showDeleteDialog = false },
            onConfirm = { password, onError ->
                authViewModel.deleteAccount(password) { errorMessage ->
                    if (errorMessage == null) {
                        // The account and its server-side backup are gone, but this device's
                        // own copies of its highlights/notes are not what just got deleted —
                        // detach them from the dead account instead of leaving every row
                        // pointing at a remote_id that will 404 forever (Phase H; see
                        // BibleRepository.detachLocalContentFromDeletedAccount).
                        bibleViewModel.detachLocalContentFromDeletedAccount()
                        showDeleteDialog = false
                    } else {
                        onError(errorMessage)
                    }
                }
            }
        )
    }
}

/**
 * Re-asks for the password before deleting — the backend requires this too
 * (server/docs/api_contract.md decision 5), since a destructive, irreversible action
 * deserves a stronger check than "whoever is holding a still-valid access token."
 */
@Composable
private fun DeleteAccountDialog(
    onDismiss: () -> Unit,
    onConfirm: (password: String, onError: (String) -> Unit) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "This permanently deletes your account and everything backed up to it. " +
                        "This can't be undone. Highlights and notes already on this device " +
                        "will stay here, but won't sync anywhere else."
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text("Confirm your password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotBlank() && !isSubmitting,
                onClick = {
                    isSubmitting = true
                    onConfirm(password) { error ->
                        isSubmitting = false
                        errorMessage = error
                    }
                }
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
