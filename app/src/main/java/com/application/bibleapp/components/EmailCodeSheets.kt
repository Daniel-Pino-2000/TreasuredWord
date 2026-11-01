package com.application.bibleapp.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.utils.MIN_PASSWORD_LENGTH
import com.application.bibleapp.utils.asString
import com.application.bibleapp.utils.isValidEmailFormat
import com.application.bibleapp.viewmodel.AuthViewModel
import kotlinx.coroutines.delay

/** Digits in an emailed code (#159, #160). */
const val EMAIL_CODE_LENGTH = 6

/** What the code field keeps of what was typed or pasted: the digits, at most six. A pasted
 *  "123 456" or "Code: 123456" becomes "123456". */
fun emailCodeInput(raw: String): String = raw.filter { it in '0'..'9' }.take(EMAIL_CODE_LENGTH)

/**
 * "Check your email" (#159): the code from the verification email, opened after Create account
 * and from Settings while the email isn't verified. A bottom sheet rather than a screen, so it
 * shows over wherever the person is when the code goes out, and "Later" just closes it.
 */
@Composable
fun VerifyEmailSheet(authViewModel: AuthViewModel) {
    val state by authViewModel.verifyEmail.collectAsState()
    val email by authViewModel.email.collectAsState()
    var code by rememberSaveable { mutableStateOf("") }
    val codeSent = state.resendAt > 0L

    EmailCodeSheet(onDismiss = authViewModel::dismissVerifyEmail) {
        Text(stringResource(R.string.verify_email_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = when {
                codeSent -> stringResource(R.string.verify_email_message, email.orEmpty())
                else -> stringResource(R.string.verify_email_sending, email.orEmpty())
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        EmailCodeField(
            value = code,
            onValueChange = {
                code = it
                authViewModel.clearVerifyEmailError()
            },
            enabled = !state.isVerifying,
            onDone = { if (code.length == EMAIL_CODE_LENGTH) authViewModel.verifyEmailCode(code) }
        )
        state.error?.let { ErrorText(it.asString()) }
        Button(
            onClick = { authViewModel.verifyEmailCode(code) },
            enabled = code.length == EMAIL_CODE_LENGTH && !state.isVerifying,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isVerifying) ButtonSpinner() else Text(stringResource(R.string.verify_email_submit))
        }
        ResendCodeButton(
            resendAt = state.resendAt,
            isSending = state.isSending,
            onClick = { authViewModel.sendVerificationCode() }
        )
        // Skipping is fine: an unverified account still syncs, and Settings offers this again.
        TextButton(onClick = authViewModel::dismissVerifyEmail, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.verify_email_later))
        }
    }
}

/**
 * "Forgot password?" (#160): the email first, then the emailed code and a new password. After
 * sending, it always says "if there's an account", as the server does, so it never tells
 * anyone whether an email has an account.
 */
@Composable
fun PasswordResetSheet(authViewModel: AuthViewModel) {
    val state by authViewModel.passwordReset.collectAsState()

    EmailCodeSheet(onDismiss = authViewModel::dismissPasswordReset) {
        Text(stringResource(R.string.password_reset_title), style = MaterialTheme.typography.titleLarge)
        if (!state.codeSent) {
            PasswordResetEmailStep(authViewModel, state.email, state.isBusy, state.error?.asString())
        } else {
            PasswordResetCodeStep(authViewModel, state.email, state.isBusy, state.resendAt, state.error?.asString())
        }
    }
}

@Composable
private fun ColumnScope.PasswordResetEmailStep(
    authViewModel: AuthViewModel,
    initialEmail: String,
    isBusy: Boolean,
    error: String?
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    val emailFormatValid = email.isBlank() || isValidEmailFormat(email)
    val canSend = isValidEmailFormat(email) && !isBusy

    Text(
        text = stringResource(R.string.password_reset_message),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = email,
        onValueChange = {
            email = it
            authViewModel.clearPasswordResetError()
        },
        label = { Text(stringResource(R.string.auth_email)) },
        isError = !emailFormatValid,
        supportingText = if (!emailFormatValid) {
            { Text(stringResource(R.string.auth_invalid_email)) }
        } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (canSend) authViewModel.sendPasswordResetCode(email) }),
        modifier = Modifier.fillMaxWidth()
    )
    error?.let { ErrorText(it) }
    Button(
        onClick = { authViewModel.sendPasswordResetCode(email) },
        enabled = canSend,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isBusy) ButtonSpinner() else Text(stringResource(R.string.password_reset_send))
    }
}

@Composable
private fun ColumnScope.PasswordResetCodeStep(
    authViewModel: AuthViewModel,
    email: String,
    isBusy: Boolean,
    resendAt: Long,
    error: String?
) {
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val passwordLengthValid = password.isBlank() || password.length >= MIN_PASSWORD_LENGTH
    val passwordsMatch = confirmPassword.isBlank() || password == confirmPassword
    val canReset = code.length == EMAIL_CODE_LENGTH && password.length >= MIN_PASSWORD_LENGTH &&
        password == confirmPassword && !isBusy
    val clearError = authViewModel::clearPasswordResetError

    Text(
        text = stringResource(R.string.password_reset_code_sent, email),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    EmailCodeField(
        value = code,
        onValueChange = {
            code = it
            clearError()
        },
        enabled = !isBusy,
        imeAction = ImeAction.Next
    )
    // Same rules and show-password button as Create account.
    OutlinedTextField(
        value = password,
        onValueChange = {
            password = it
            clearError()
        },
        label = { Text(stringResource(R.string.password_reset_new_password)) },
        isError = !passwordLengthValid,
        supportingText = { Text(pluralStringResource(R.plurals.register_password_hint, MIN_PASSWORD_LENGTH, MIN_PASSWORD_LENGTH)) },
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = confirmPassword,
        onValueChange = {
            confirmPassword = it
            clearError()
        },
        label = { Text(stringResource(R.string.auth_confirm_password)) },
        isError = !passwordsMatch,
        supportingText = if (!passwordsMatch) {
            { Text(stringResource(R.string.register_passwords_mismatch)) }
        } else null,
        singleLine = true,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (canReset) authViewModel.resetPassword(code, password) }),
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = stringResource(R.string.password_reset_signs_out),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    error?.let { ErrorText(it) }
    Button(
        onClick = { authViewModel.resetPassword(code, password) },
        enabled = canReset,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isBusy) ButtonSpinner() else Text(stringResource(R.string.password_reset_submit))
    }
    ResendCodeButton(resendAt = resendAt, isSending = isBusy, onClick = { authViewModel.sendPasswordResetCode(email) })
    TextButton(onClick = authViewModel::changePasswordResetEmail, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.password_reset_change_email))
    }
}

/** The sheet both flows share, following the app's other sheets (AboutBookSheet): full height
 *  straight away, clear of the status and navigation bars, and above the keyboard. Their
 *  messages are shown inside, since a sheet is drawn over the screen's snackbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmailCodeSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                .padding(bottom = if (isCompactHeight()) Spacing.sm else Spacing.xl)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            content = content
        )
    }
}

/** Digits only, number keyboard; pasting a whole code (with spaces) works. */
@Composable
private fun EmailCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(emailCodeInput(it)) },
        label = { Text(stringResource(R.string.email_code_label)) },
        singleLine = true,
        enabled = enabled,
        textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 6.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth()
    )
}

/** "Send a new code", greyed out with a countdown for a minute after each send. */
@Composable
private fun ResendCodeButton(resendAt: Long, isSending: Boolean, onClick: () -> Unit) {
    val secondsLeft = rememberSecondsUntil(resendAt)
    TextButton(
        onClick = onClick,
        enabled = secondsLeft == 0 && !isSending,
        modifier = Modifier.fillMaxWidth()
    ) {
        when {
            isSending -> ButtonSpinner(color = MaterialTheme.colorScheme.primary)
            secondsLeft > 0 -> Text(pluralStringResource(R.plurals.email_code_resend_wait, secondsLeft, secondsLeft))
            else -> Text(stringResource(R.string.email_code_resend))
        }
    }
}

/** Whole seconds until [millis] ([System.currentTimeMillis] time), counting down to 0. */
@Composable
private fun rememberSecondsUntil(millis: Long): Int {
    fun left() = ((millis - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
    var seconds by remember(millis) { mutableIntStateOf(left()) }
    LaunchedEffect(millis) {
        while (seconds > 0) {
            delay(1000)
            seconds = left()
        }
    }
    return seconds
}

@Composable
private fun ErrorText(text: String) {
    Text(text = text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ButtonSpinner(color: Color = MaterialTheme.colorScheme.onPrimary) {
    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = color)
}
