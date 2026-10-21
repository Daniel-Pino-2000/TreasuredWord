package com.application.bibleapp.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.application.bibleapp.components.GroupedDivider
import com.application.bibleapp.components.GroupedRow
import com.application.bibleapp.components.GroupedSection
import com.application.bibleapp.components.GroupedSwitchRow
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.utils.NetworkUtils
import com.application.bibleapp.utils.formatRelativeSyncTime
import com.application.bibleapp.viewmodel.AuthViewModel
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.worker.SyncScheduler

@Composable
fun SyncSettingsView(
    bibleViewModel: BibleViewModel,
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier,
    onSignInClick: () -> Unit
) {
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()

    // Reflects a background periodic sync's timestamp even if this screen was already open
    // when it completed, rather than only ever showing whatever was true on first composition.
    LaunchedEffect(Unit) {
        bibleViewModel.refreshSyncStatus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl)
    ) {
        if (!isLoggedIn) {
            SignInPrompt(
                text = "Sign in to sync your highlights, notes, and reading progress across devices.",
                onSignInClick = onSignInClick
            )
        } else {
            SyncControls(bibleViewModel)
        }
    }
}

@Composable
private fun SyncControls(bibleViewModel: BibleViewModel) {
    val context = LocalContext.current
    val autoSyncEnabled by bibleViewModel.autoSyncEnabled.collectAsState()
    val wifiOnlySync by bibleViewModel.wifiOnlySync.collectAsState()
    val lastSyncCompletedAt by bibleViewModel.lastSyncCompletedAt.collectAsState()
    val lastSyncFailed by bibleViewModel.lastSyncFailed.collectAsState()
    val isSyncing by bibleViewModel.isSyncing.collectAsState()

    // Checked on resume rather than via a live NetworkCallback — a connectivity flip while
    // this screen is sitting in the foreground is rare enough that "catches up next time you
    // look at it" is good enough, and it's the same pattern the battery-optimization check on
    // the Notifications page uses (Phase H).
    var isOffline by remember { mutableStateOf(!NetworkUtils.isOnline(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isOffline = !NetworkUtils.isOnline(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val syncNow = {
        bibleViewModel.awaitManualSync()
        SyncScheduler.triggerImmediateSync(context)
    }

    GroupedSection(title = "Status") {
        when {
            isOffline -> GroupedRow(
                title = "You're offline",
                subtitle = "Highlights and notes will sync once you're back online.",
                icon = Icons.Outlined.CloudOff
            )
            lastSyncFailed && !isSyncing -> GroupedRow(
                title = "Sync failed",
                titleColor = MaterialTheme.colorScheme.error,
                icon = Icons.Outlined.ErrorOutline,
                iconTint = MaterialTheme.colorScheme.error,
                trailing = { TextButton(onClick = syncNow) { Text("Retry") } }
            )
            else -> GroupedRow(
                title = when {
                    isSyncing -> "Syncing…"
                    lastSyncCompletedAt != null -> "Synced " + formatRelativeSyncTime(lastSyncCompletedAt!!)
                    else -> "Not synced yet"
                },
                icon = Icons.Outlined.CloudDone,
                trailing = {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        TextButton(onClick = syncNow) { Text("Sync now") }
                    }
                }
            )
        }
    }

    GroupedSection(title = "Options") {
        GroupedSwitchRow(
            title = "Auto-sync",
            subtitle = "Back up changes in the background",
            icon = Icons.Outlined.Sync,
            checked = autoSyncEnabled,
            onCheckedChange = { enabled ->
                bibleViewModel.setAutoSyncEnabled(enabled)
                SyncScheduler.schedulePeriodicSync(context)
            }
        )
        GroupedDivider()
        GroupedSwitchRow(
            title = "Wi-Fi only",
            subtitle = "Don't use mobile data to sync",
            icon = Icons.Outlined.Wifi,
            checked = wifiOnlySync,
            onCheckedChange = { enabled ->
                bibleViewModel.setWifiOnlySync(enabled)
                SyncScheduler.schedulePeriodicSync(context)
            }
        )
    }
}

/** Shown in place of a page's controls when they only make sense signed in (Sync, Account). */
@Composable
internal fun SignInPrompt(text: String, onSignInClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onSignInClick, modifier = Modifier.fillMaxWidth()) {
            Text("Sign in")
        }
    }
}
