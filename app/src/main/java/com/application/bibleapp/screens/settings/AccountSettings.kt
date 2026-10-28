package com.application.bibleapp.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Person
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.application.bibleapp.components.GroupedDivider
import com.application.bibleapp.components.GroupedRow
import com.application.bibleapp.components.GoogleSignInButton
import com.application.bibleapp.components.GroupedSection
import com.application.bibleapp.components.OrDivider
import com.application.bibleapp.components.ServerWakeHint
import com.application.bibleapp.components.rememberWaitingLong
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.viewmodel.AuthViewModel
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.viewmodel.SignOutState
import com.application.bibleapp.R
import androidx.compose.ui.res.stringResource
import com.application.bibleapp.utils.GoogleSignIn
import com.application.bibleapp.utils.GoogleSignInResult
import com.application.bibleapp.utils.UiText
import com.application.bibleapp.utils.asString
import com.application.bibleapp.viewmodel.SignInMethods
import kotlinx.coroutines.launch

@Composable
internal fun AccountSettings(
    authViewModel: AuthViewModel,
    bibleViewModel: BibleViewModel,
    onSignInClick: () -> Unit
) {
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
    val email by authViewModel.email.collectAsState()
    val signOutState by authViewModel.signOutState.collectAsState()
    val signInMethods by authViewModel.signInMethods.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    // Sign-out removes the account's highlights and notes from this device (#81); the reader
    // and Library drop them straight away.
    val signOut = { discardUnsynced: Boolean ->
        authViewModel.signOut(discardUnsynced) { bibleViewModel.onAccountContentRemoved() }
    }

    GroupedSection(title = stringResource(R.string.settings_section_account)) {
        if (!isLoggedIn) {
            GroupedRow(
                title = stringResource(R.string.action_sign_in),
                subtitle = stringResource(R.string.account_sign_in_subtitle),
                icon = Icons.AutoMirrored.Outlined.Login,
                onClick = onSignInClick
            )
        } else {
            GroupedRow(
                title = email ?: stringResource(R.string.account_your_account),
                subtitle = stringResource(R.string.label_signed_in),
                icon = Icons.Outlined.Person
            )
            GroupedDivider()
            val isUploading = signOutState == SignOutState.Uploading
            GroupedRow(
                title = stringResource(if (isUploading) R.string.account_signing_out else R.string.account_sign_out),
                subtitle = stringResource(R.string.account_sign_out_subtitle),
                icon = Icons.AutoMirrored.Outlined.Logout,
                showChevron = false,
                trailing = if (isUploading) {
                    { CircularProgressIndicator(modifier = Modifier.size(20.dp)) }
                } else {
                    null
                },
                onClick = if (isUploading) null else ({ signOut(false) })
            )
            GroupedDivider()
            GroupedRow(
                title = stringResource(R.string.account_delete),
                titleColor = MaterialTheme.colorScheme.error,
                icon = Icons.Outlined.DeleteForever,
                iconTint = MaterialTheme.colorScheme.error,
                showChevron = false,
                onClick = { showDeleteDialog = true }
            )
        }
    }

    if (signOutState == SignOutState.UnsyncedChanges) {
        // Some highlights or notes couldn't be uploaded (offline, server asleep): signing out
        // would delete them from the only place they are, so it's the person's call.
        AlertDialog(
            onDismissRequest = { authViewModel.cancelSignOut() },
            title = { Text(stringResource(R.string.account_sign_out_unsynced_title)) },
            text = { Text(stringResource(R.string.account_sign_out_unsynced_message)) },
            confirmButton = {
                TextButton(onClick = { signOut(true) }) {
                    Text(stringResource(R.string.account_sign_out_anyway), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { authViewModel.cancelSignOut() }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showDeleteDialog) {
        DeleteAccountDialog(
            signInMethods = signInMethods,
            onDismiss = { showDeleteDialog = false },
            onConfirm = { password, googleIdToken, onError ->
                authViewModel.deleteAccount(password, googleIdToken) { errorMessage ->
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
 * deserves a stronger check than "whoever is holding a still-valid access token." An account
 * that uses Google can confirm with Google instead, and one made with Google has no password to
 * ask for (#112). Until /users/me has said which applies, both are offered.
 */
@Composable
private fun DeleteAccountDialog(
    signInMethods: SignInMethods,
    onDismiss: () -> Unit,
    onConfirm: (password: String?, googleIdToken: String?, onError: (UiText) -> Unit) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<UiText?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var isGoogleSubmitting by remember { mutableStateOf(false) }
    val submittingLong = rememberWaitingLong(isSubmitting)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val askPassword = signInMethods.hasPassword != false
    val offerGoogle = signInMethods.hasGoogle != false && GoogleSignIn.isConfigured(context)
    val onError = { error: UiText ->
        isSubmitting = false
        isGoogleSubmitting = false
        errorMessage = error
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.account_delete_message))
                if (askPassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        label = { Text(stringResource(R.string.account_confirm_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (offerGoogle) {
                    if (askPassword) OrDivider() else Text(stringResource(R.string.account_delete_confirm_with_google))
                    GoogleSignInButton(
                        onClick = {
                            errorMessage = null
                            scope.launch {
                                when (val result = GoogleSignIn.requestIdToken(context)) {
                                    is GoogleSignInResult.Success -> {
                                        isSubmitting = true
                                        isGoogleSubmitting = true
                                        onConfirm(null, result.idToken, onError)
                                    }
                                    GoogleSignInResult.Cancelled -> Unit
                                    is GoogleSignInResult.Failed -> errorMessage = result.message
                                }
                            }
                        },
                        enabled = !isSubmitting,
                        isLoading = isGoogleSubmitting
                    )
                }
                errorMessage?.let {
                    Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (submittingLong) ServerWakeHint()
            }
        },
        confirmButton = {
            if (askPassword) {
                TextButton(
                    enabled = password.isNotBlank() && !isSubmitting,
                    onClick = {
                        isSubmitting = true
                        onConfirm(password, null, onError)
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
