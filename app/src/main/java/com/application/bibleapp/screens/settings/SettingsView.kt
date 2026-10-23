package com.application.bibleapp.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.viewmodel.AuthViewModel
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Icon
import com.application.bibleapp.components.GroupedRow
import com.application.bibleapp.components.GroupedSection
import com.application.bibleapp.utils.AppLanguage
import com.application.bibleapp.utils.PRIVACY_POLICY_URL
import com.application.bibleapp.utils.rememberExternalLinks

/**
 * Every setting on one grouped page — at this size (~10 settings) a single screen is quicker to
 * scan than an index of near-empty sub-pages. Simple choices stay inline (switches, segmented
 * bars); a choice from a long list, like Language, goes in as a row with its current value that
 * opens its own picker page. If one section outgrows the page, it becomes a
 * row that opens its own page the same way.
 */
@Composable
fun SettingsView(
    bibleViewModel: BibleViewModel,
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier,
    onSignInClick: () -> Unit,
    onLanguageClick: () -> Unit
) {
    val context = LocalContext.current
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
    val links = rememberExternalLinks { bibleViewModel.showMessage(it) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl)
    ) {
        GroupedSection(title = stringResource(R.string.settings_section_general)) {
            GroupedRow(
                title = stringResource(R.string.title_language),
                icon = Icons.Outlined.Language,
                value = AppLanguage.currentTag()?.let { AppLanguage.displayName(it) }
                    ?: stringResource(R.string.language_system_default),
                onClick = onLanguageClick
            )
        }
        AppearanceSettings(bibleViewModel)
        DailyVerseSettings(bibleViewModel)
        // Sync's switches mean nothing signed out — the Account section's sign-in row covers it.
        if (isLoggedIn) SyncSettings(bibleViewModel)
        AccountSettings(authViewModel, bibleViewModel, onSignInClick)
        // Google Play requires the privacy policy to be reachable from inside the app, too.
        GroupedSection(title = stringResource(R.string.settings_section_about)) {
            GroupedRow(
                title = stringResource(R.string.settings_privacy_policy),
                icon = Icons.Outlined.PrivacyTip,
                onClick = { links.openPage(PRIVACY_POLICY_URL) },
                trailing = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.external_link_opens_outside),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }

        Text(
            text = stringResource(R.string.settings_app_version, stringResource(R.string.app_name), appVersionName(context)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** From the installed package rather than a hardcoded string, so it can't drift from build.gradle. */
private fun appVersionName(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
