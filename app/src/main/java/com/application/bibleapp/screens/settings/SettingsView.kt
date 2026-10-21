package com.application.bibleapp.screens.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.application.bibleapp.components.GroupedDivider
import com.application.bibleapp.components.GroupedRow
import com.application.bibleapp.components.GroupedSection
import com.application.bibleapp.navigation.Screen
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.viewmodel.AuthViewModel
import com.application.bibleapp.viewmodel.BibleViewModel

/**
 * The settings hub: one row per page, each subtitled with its current state so most questions
 * ("is the reminder on?") are answered without opening anything. A new setting goes on the page
 * it belongs to; a new page is one more row here plus an entry in [Screen.settingsPages].
 */
@Composable
fun SettingsView(
    bibleViewModel: BibleViewModel,
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier,
    onPageClick: (route: String) -> Unit
) {
    val context = LocalContext.current
    val themeMode by bibleViewModel.themeMode.collectAsState()
    val verseTextScale by bibleViewModel.verseTextScale.collectAsState()
    val notificationEnabled by bibleViewModel.notificationEnabled.collectAsState()
    val notificationTime by bibleViewModel.notificationTime.collectAsState()
    val autoSyncEnabled by bibleViewModel.autoSyncEnabled.collectAsState()
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
    val currentUser by authViewModel.currentUser.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl)
    ) {
        GroupedSection(title = "Preferences") {
            GroupedRow(
                title = Screen.SettingsAppearance.title,
                icon = Icons.Outlined.Palette,
                subtitle = "${themeMode.label} theme, ${verseTextScale.label.lowercase()} text",
                onClick = { onPageClick(Screen.SettingsAppearance.route) }
            )
            GroupedDivider()
            GroupedRow(
                title = Screen.SettingsNotifications.title,
                icon = Icons.Outlined.Notifications,
                subtitle = if (notificationEnabled) {
                    "Daily verse at " + formatNotificationTime(
                        notificationTime.hour, notificationTime.minute, DateFormat.is24HourFormat(context)
                    )
                } else {
                    "Daily verse off"
                },
                onClick = { onPageClick(Screen.SettingsNotifications.route) }
            )
            GroupedDivider()
            GroupedRow(
                title = Screen.SettingsSync.title,
                icon = Icons.Outlined.Sync,
                subtitle = when {
                    !isLoggedIn -> "Sign in to sync"
                    autoSyncEnabled -> "Auto-sync on"
                    else -> "Auto-sync off"
                },
                onClick = { onPageClick(Screen.SettingsSync.route) }
            )
        }

        GroupedSection(title = "Account and app") {
            GroupedRow(
                title = Screen.SettingsAccount.title,
                icon = Icons.Outlined.ManageAccounts,
                subtitle = currentUser?.email?.takeIf { isLoggedIn } ?: "Not signed in",
                onClick = { onPageClick(Screen.SettingsAccount.route) }
            )
            GroupedDivider()
            GroupedRow(
                title = "About TreasuredWord",
                icon = Icons.Outlined.Info,
                subtitle = "Version ${appVersionName(context)}",
                onClick = { onPageClick(Screen.SettingsAbout.route) }
            )
        }
    }
}
