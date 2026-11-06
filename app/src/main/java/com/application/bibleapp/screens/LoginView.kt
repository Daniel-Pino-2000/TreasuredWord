package com.application.bibleapp.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.application.bibleapp.components.GoogleSignInSection
import com.application.bibleapp.components.PasswordResetSheet
import com.application.bibleapp.components.ServerWakeHint
import com.application.bibleapp.components.rememberWaitingLong
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.utils.isValidEmailFormat
import com.application.bibleapp.viewmodel.AuthViewModel
import com.application.bibleapp.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.autofill.ContentType
import com.application.bibleapp.utils.asString
import com.application.bibleapp.utils.autofill
import com.application.bibleapp.utils.AccountEmail

@Composable
fun LoginView(
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier,
    onNavigateToRegister: () -> Unit
) {
    // The email and the eye toggle survive turning the phone; passwords don't on purpose, since
    // saved state can be written to disk (#231).
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val isLoading by authViewModel.isLoading.collectAsState()
    val isGoogleLoading by authViewModel.isGoogleLoading.collectAsState()
    val errorMessage by authViewModel.errorMessage.collectAsState()
    val passwordReset by authViewModel.passwordReset.collectAsState()

    // Only flagged once there's something to judge — an empty field isn't "invalid" yet,
    // it just hasn't been filled in.
    val emailFormatValid = email.isBlank() || isValidEmailFormat(email)
    val waitingLong = rememberWaitingLong(isLoading)

    // A sleeping server starts up while the form is filled in, not after Sign in (#87).
    LaunchedEffect(Unit) { authViewModel.wakeServer() }

    // Back from "Forgot password?" (#160): ready to sign in with the new password.
    LaunchedEffect(authViewModel) {
        authViewModel.passwordResetDone.collect { resetEmail ->
            email = resetEmail
            password = ""
            authViewModel.clearError()
        }
    }
    if (passwordReset.isOpen) PasswordResetSheet(authViewModel)

    Column(
        modifier = modifier
            .fillMaxSize()
            // The window resizes for the keyboard (manifest): only the form scrolls (#225).
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(stringResource(R.string.action_sign_in), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.login_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                authViewModel.clearError()
            },
            label = { Text(stringResource(R.string.auth_email)) },
            isError = !emailFormatValid,
            supportingText = if (!emailFormatValid) {
                { Text(stringResource(R.string.auth_invalid_email)) }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().autofill(AccountEmail)
        )

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                authViewModel.clearError()
            },
            label = { Text(stringResource(R.string.auth_password)) },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(if (passwordVisible) R.string.auth_hide_password else R.string.auth_show_password)
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().autofill(ContentType.Password)
        )

        TextButton(
            onClick = { authViewModel.openPasswordReset(email) },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(stringResource(R.string.login_forgot_password))
        }

        // Deliberately the same message whether the email doesn't exist or the password is
        // wrong — never reveals which one — matching the backend's own INVALID_CREDENTIALS
        // ambiguity (server/docs/api_contract.md decision 4).
        errorMessage?.let {
            Text(
                text = it.asString(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Button(
            onClick = { authViewModel.login(email.trim(), password) },
            enabled = !isLoading && isValidEmailFormat(email) && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading && !isGoogleLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.action_sign_in))
            }
        }

        GoogleSignInSection(authViewModel)

        if (waitingLong) ServerWakeHint()

        TextButton(onClick = onNavigateToRegister, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.login_no_account))
        }
    }
}
