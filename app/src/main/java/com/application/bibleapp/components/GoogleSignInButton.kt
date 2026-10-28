package com.application.bibleapp.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.utils.GoogleSignIn
import com.application.bibleapp.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

/**
 * "Continue with Google" (#112). The colors are Google's, not the app theme's: its sign-in
 * branding guidelines fix the fill, outline and text color for light and dark buttons, and the
 * "G" in its own colors.
 */
@Composable
fun GoogleSignInButton(
    onClick: () -> Unit,
    enabled: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    // The app's own Light/Dark choice inside BibleAppTheme (#24).
    val dark = isSystemInDarkTheme()
    val container = if (dark) Color(0xFF131314) else Color(0xFFFFFFFF)
    val content = if (dark) Color(0xFFE3E3E3) else Color(0xFF1F1F1F)
    val outline = if (dark) Color(0xFF8E918F) else Color(0xFF747775)

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.38f),
            disabledContentColor = content.copy(alpha = 0.38f)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = content)
        } else {
            Image(painter = painterResource(R.drawable.ic_google_logo), contentDescription = null)
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.auth_continue_with_google))
        }
    }
}

/** A line with "or" in the middle, between the email form and [GoogleSignInButton]. */
@Composable
fun OrDivider(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.auth_or),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md)
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/**
 * "or" and "Continue with Google" under the email form of the sign-in and create-account
 * screens (#112), or nothing until the web client ID is set. Google's sheet runs here, since it
 * needs the Activity; the server part runs in [AuthViewModel], so it finishes even if the person
 * leaves the screen meanwhile (#134).
 */
@Composable
fun GoogleSignInSection(authViewModel: AuthViewModel) {
    val context = LocalContext.current
    if (!GoogleSignIn.isConfigured(context)) return
    val scope = rememberCoroutineScope()
    val isLoading by authViewModel.isLoading.collectAsState()
    val isGoogleLoading by authViewModel.isGoogleLoading.collectAsState()

    OrDivider()
    GoogleSignInButton(
        onClick = {
            authViewModel.clearError()
            scope.launch { authViewModel.onGoogleResult(GoogleSignIn.requestIdToken(context)) }
        },
        enabled = !isLoading,
        isLoading = isGoogleLoading
    )
}
