package com.application.bibleapp.screens

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.application.bibleapp.navigation.Screen
import com.application.bibleapp.ui.theme.ReadingStyle
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.VerseTextScale
import com.application.bibleapp.ui.theme.scaledBy
import com.application.bibleapp.utils.formatRelativeSyncTime
import com.application.bibleapp.viewmodel.AuthViewModel
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.worker.SyncScheduler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsView(
    bibleViewModel: BibleViewModel,
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier,
    onSignInClick: () -> Unit,
    onLibraryClick: (tab: String) -> Unit,
    onContinueReadingClick: () -> Unit
) {
    val context = LocalContext.current
    val themeMode by bibleViewModel.themeMode.collectAsState()
    val verseTextScale by bibleViewModel.verseTextScale.collectAsState()
    val notificationEnabled by bibleViewModel.notificationEnabled.collectAsState()
    val notificationTime by bibleViewModel.notificationTime.collectAsState()
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
    val currentUser by authViewModel.currentUser.collectAsState()
    val autoSyncEnabled by bibleViewModel.autoSyncEnabled.collectAsState()
    val wifiOnlySync by bibleViewModel.wifiOnlySync.collectAsState()
    val lastSyncCompletedAt by bibleViewModel.lastSyncCompletedAt.collectAsState()
    val isSyncing by bibleViewModel.isSyncing.collectAsState()
    val currentBookName by bibleViewModel.currentBookName.collectAsState()
    val currentChapter by bibleViewModel.currentChapter.collectAsState()

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
        ProfileHeader(
            isLoggedIn = isLoggedIn,
            email = currentUser?.email,
            isSyncing = isSyncing,
            lastSyncCompletedAt = lastSyncCompletedAt
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SettingsSection(title = "My Library") {
            SettingsRow(
                label = "Highlights",
                onClick = { onLibraryClick(Screen.Library.TAB_HIGHLIGHTS) }
            )
            SettingsRow(
                label = "Notes",
                onClick = { onLibraryClick(Screen.Library.TAB_NOTES) }
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SettingsSection(title = "Reading") {
            SettingsRow(
                label = "Continue reading",
                supportingText = "$currentBookName $currentChapter",
                onClick = onContinueReadingClick
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SettingsSection(title = "Appearance") {
            // Measured on device: "Small"+"Default"+"Large"+"Extra Large" together
            // need ~1024px on a 360dp-wide phone, ~40px more than the row actually
            // has — wrapping the widest chip onto its own line reads as an orphaned
            // button, and there's no font-size/padding trim that reliably closes a
            // 40px gap across devices and system text-scale settings without
            // breaking again. A single scrollable row is the same pattern already
            // used for the suggested-search chips in SearchView, so every chip stays
            // the same size as its siblings and the group still reads as one row.
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { bibleViewModel.setThemeMode(mode) },
                        label = { Text(mode.label) }
                    )
                }
            }
        }

        SettingsSection(title = "Verse Text Size") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                VerseTextScale.entries.forEach { scale ->
                    FilterChip(
                        selected = verseTextScale == scale,
                        onClick = { bibleViewModel.setVerseTextScale(scale) },
                        label = { Text(scale.label) }
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = "1 In the beginning God created the heavens and the earth.",
                    style = ReadingStyle.VerseText.scaledBy(verseTextScale.multiplier),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(Spacing.lg)
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SettingsSection(title = "Sync") {
            if (isLoggedIn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-sync", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = autoSyncEnabled,
                        onCheckedChange = { enabled ->
                            bibleViewModel.setAutoSyncEnabled(enabled)
                            SyncScheduler.schedulePeriodicSync(context)
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Wi-Fi only", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = wifiOnlySync,
                        onCheckedChange = { enabled ->
                            bibleViewModel.setWifiOnlySync(enabled)
                            SyncScheduler.schedulePeriodicSync(context)
                        }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when {
                            isSyncing -> "Syncing…"
                            lastSyncCompletedAt != null -> "Synced " + formatRelativeSyncTime(lastSyncCompletedAt!!)
                            else -> "Not synced yet"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        TextButton(onClick = {
                            bibleViewModel.awaitManualSync()
                            SyncScheduler.triggerImmediateSync(context)
                        }) {
                            Text("Sync now")
                        }
                    }
                }
            } else {
                Text(
                    text = "Sign in to sync your highlights, notes, and reading progress across devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SettingsSection(title = "Notifications") {
            var showTimePicker by remember { mutableStateOf(false) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> if (granted) bibleViewModel.setNotificationEnabled(true) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Daily verse reminder", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = notificationEnabled,
                    onCheckedChange = { checked ->
                        val needsPermission = checked &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                        if (needsPermission) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            bibleViewModel.setNotificationEnabled(checked)
                        }
                    }
                )
            }

            if (notificationEnabled) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTimePicker = true },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "Send at " + formatNotificationTime(
                            notificationTime.hour, notificationTime.minute, DateFormat.is24HourFormat(context)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Spacing.md)
                    )
                }

                // Passive nudge, not an automatic system prompt: Play policy reserves the
                // "ignore battery optimizations" dialog for apps whose core function needs
                // guaranteed background execution, which a devotional reminder isn't. This
                // just points the user at the OS's own App Info screen and lets them decide
                // whether to grant it — re-checked on resume so it disappears on its own
                // once they've turned it on there.
                var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
                // A separate, stricter OS-level toggle from battery optimization ("Restrict
                // background usage" in App Info, or an OEM's own "sleeping apps" equivalent
                // on top of it): when set, WorkManager's queued jobs simply don't run until
                // the app is opened again, which is exactly "reminder works while the app is
                // open, never fires once it's closed" — this app's actual reported symptom.
                var backgroundRestricted by remember { mutableStateOf(isBackgroundRestricted(context)) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            batteryUnrestricted = isIgnoringBatteryOptimizations(context)
                            backgroundRestricted = isBackgroundRestricted(context)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (backgroundRestricted) {
                    Text(
                        text = "Background activity is restricted for this app, so the " +
                            "reminder can only fire while the app is open — tap to turn " +
                            "that off in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openAppSettings(context) }
                            .padding(top = Spacing.sm)
                    )
                } else if (!batteryUnrestricted) {
                    Text(
                        text = "Reminders arriving late or not at all? Some phones restrict " +
                            "background apps — tap to check your battery settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openAppSettings(context) }
                            .padding(top = Spacing.sm)
                    )
                }
            }

            if (showTimePicker) {
                val pickerState = rememberTimePickerState(
                    initialHour = notificationTime.hour,
                    initialMinute = notificationTime.minute,
                    is24Hour = DateFormat.is24HourFormat(context)
                )
                AlertDialog(
                    onDismissRequest = { showTimePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            bibleViewModel.setNotificationTime(pickerState.hour, pickerState.minute)
                            showTimePicker = false
                        }) { Text("OK") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                    },
                    text = { TimePicker(state = pickerState) }
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SettingsSection(title = "Account") {
            AccountSection(authViewModel = authViewModel, onSignInClick = onSignInClick)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SettingsSection(title = "About") {
            Text("BibleApp", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Version 1.0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

/** Avatar-initial + email/sign-in-state + sync status, at the top of the Profile hub — see
 *  docs/UI_Integration_Roadmap.md Phase G. The initial is the first letter of the signed-in
 *  email, or "?" signed out, so there's always something to put in the circle without pulling
 *  in an actual avatar-image feature nobody asked for. */
@Composable
private fun ProfileHeader(
    isLoggedIn: Boolean,
    email: String?,
    isSyncing: Boolean,
    lastSyncCompletedAt: String?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = (email?.firstOrNull()?.uppercaseChar() ?: '?').toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Column {
            Text(
                text = email ?: "Not signed in",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = when {
                    !isLoggedIn -> "Sign in to sync across devices"
                    isSyncing -> "Syncing…"
                    lastSyncCompletedAt != null -> "Synced " + formatRelativeSyncTime(lastSyncCompletedAt)
                    else -> "Not synced yet"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A tappable row used by "My Library" and "Reading" — a label plus optional supporting text
 *  (e.g. the current book/chapter under "Continue reading"). */
@Composable
private fun SettingsRow(
    label: String,
    onClick: () -> Unit,
    supportingText: String? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AccountSection(
    authViewModel: AuthViewModel,
    onSignInClick: () -> Unit
) {
    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Identity itself (email, sync status) lives in the ProfileHeader at the top of this
    // screen — this section is just the account-level actions.
    if (isLoggedIn) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            TextButton(onClick = { authViewModel.logout() }) {
                Text("Sign out")
            }
            TextButton(onClick = { showDeleteDialog = true }) {
                Text("Delete account", color = MaterialTheme.colorScheme.error)
            }
        }
    } else {
        Text(
            text = "Sign in to sync your highlights, notes, and reading progress across devices.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onSignInClick) {
            Text("Sign in")
        }
    }

    if (showDeleteDialog) {
        DeleteAccountDialog(
            onDismiss = { showDeleteDialog = false },
            onConfirm = { password, onError ->
                authViewModel.deleteAccount(password) { errorMessage ->
                    if (errorMessage == null) {
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
                    "This permanently deletes your account and all synced highlights, notes, " +
                        "and reading progress. This can't be undone."
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

/** "8:05 AM" when the device uses a 12-hour clock, "08:05" when it uses 24-hour —
 *  matches [DateFormat.is24HourFormat] rather than hardcoding either format. */
private fun formatNotificationTime(hour: Int, minute: Int, is24Hour: Boolean): String {
    if (is24Hour) return "%02d:%02d".format(hour, minute)
    val displayHour = when (val h = hour % 12) { 0 -> 12; else -> h }
    val period = if (hour < 12) "AM" else "PM"
    return "%d:%02d %s".format(displayHour, minute, period)
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

/** API 28+ only; below that OS-level background restriction doesn't exist as a user toggle. */
private fun isBackgroundRestricted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return activityManager.isBackgroundRestricted
}

/** Opens this app's own App Info screen (has a "Battery" entry on every OEM skin) rather
 *  than requesting ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS directly — the user takes
 *  the final step themselves instead of the app auto-prompting for the exemption. */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}
