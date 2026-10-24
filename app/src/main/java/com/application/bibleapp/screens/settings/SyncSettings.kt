package com.application.bibleapp.screens.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
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
import com.application.bibleapp.components.rememberWaitingLong
import com.application.bibleapp.utils.NetworkUtils
import com.application.bibleapp.utils.formatSyncedStatus
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.worker.SyncScheduler
import com.application.bibleapp.R
import androidx.compose.ui.res.stringResource

/** Signed-in only — [SettingsView] leaves it out otherwise. */
@Composable
internal fun SyncSettings(bibleViewModel: BibleViewModel) {
    val context = LocalContext.current
    val autoSyncEnabled by bibleViewModel.autoSyncEnabled.collectAsState()
    val wifiOnlySync by bibleViewModel.wifiOnlySync.collectAsState()
    val lastSyncCompletedAt by bibleViewModel.lastSyncCompletedAt.collectAsState()
    val lastSyncFailed by bibleViewModel.lastSyncFailed.collectAsState()
    val isSyncing by bibleViewModel.isSyncing.collectAsState()
    // A sync that's this slow is almost always the server waking up, so say so (#87).
    val syncingLong = rememberWaitingLong(isSyncing)

    // Reflects a background periodic sync's timestamp even if this screen was already open
    // when it completed, rather than only ever showing whatever was true on first composition.
    LaunchedEffect(Unit) {
        bibleViewModel.refreshSyncStatus()
    }

    // Checked on resume rather than via a live NetworkCallback — a connectivity flip while
    // this screen is sitting in the foreground is rare enough that "catches up next time you
    // look at it" is good enough, and it's the same pattern the Daily verse section's
    // battery-optimization check uses (Phase H).
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

    GroupedSection(title = stringResource(R.string.settings_section_sync)) {
        when {
            isOffline -> GroupedRow(
                title = stringResource(R.string.sync_offline_title),
                subtitle = stringResource(R.string.sync_offline_subtitle),
                icon = Icons.Outlined.CloudOff
            )
            lastSyncFailed && !isSyncing -> GroupedRow(
                title = stringResource(R.string.sync_failed),
                titleColor = MaterialTheme.colorScheme.error,
                icon = Icons.Outlined.ErrorOutline,
                iconTint = MaterialTheme.colorScheme.error,
                trailing = { TextButton(onClick = syncNow) { Text(stringResource(R.string.action_retry)) } }
            )
            else -> GroupedRow(
                title = when {
                    isSyncing -> stringResource(R.string.sync_status_syncing)
                    lastSyncCompletedAt != null -> formatSyncedStatus(context.resources, lastSyncCompletedAt!!)
                    else -> stringResource(R.string.sync_status_not_synced)
                },
                subtitle = if (isSyncing && syncingLong) stringResource(R.string.server_waking_hint) else null,
                icon = Icons.Outlined.CloudDone,
                trailing = {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        TextButton(onClick = syncNow) { Text(stringResource(R.string.sync_now)) }
                    }
                }
            )
        }
        GroupedDivider()
        GroupedSwitchRow(
            title = stringResource(R.string.sync_auto),
            subtitle = stringResource(R.string.sync_auto_subtitle),
            icon = Icons.Outlined.Sync,
            checked = autoSyncEnabled,
            onCheckedChange = { enabled ->
                bibleViewModel.setAutoSyncEnabled(enabled)
                SyncScheduler.schedulePeriodicSync(context)
            }
        )
        GroupedDivider()
        GroupedSwitchRow(
            title = stringResource(R.string.sync_wifi_only),
            subtitle = stringResource(R.string.sync_wifi_only_subtitle),
            icon = Icons.Outlined.Wifi,
            checked = wifiOnlySync,
            onCheckedChange = { enabled ->
                bibleViewModel.setWifiOnlySync(enabled)
                SyncScheduler.schedulePeriodicSync(context)
            }
        )
    }
}
