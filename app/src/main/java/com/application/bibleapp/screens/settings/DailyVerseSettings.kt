package com.application.bibleapp.screens.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.VerseImageStyle
import com.application.bibleapp.verseimage.VerseImageSaving
import com.application.bibleapp.verseimage.WallpaperTarget
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.application.bibleapp.components.GroupedDivider
import com.application.bibleapp.components.GroupedRow
import com.application.bibleapp.components.GroupedSection
import com.application.bibleapp.components.GroupedSwitchRow
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.R
import androidx.compose.ui.res.stringResource
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DailyVerseSettings(bibleViewModel: BibleViewModel) {
    val context = LocalContext.current
    val notificationEnabled by bibleViewModel.notificationEnabled.collectAsState()
    val notificationTime by bibleViewModel.notificationTime.collectAsState()
    val dailyWallpaper by bibleViewModel.dailyWallpaper.collectAsState()
    val canSetWallpaper = remember { VerseImageSaving.canSetWallpaper(context) }
    var wallpaperDialog by rememberSaveable { mutableStateOf(WallpaperDialog.NONE) }
    // Saveable so recreating the activity doesn't close the picker (#231).
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) bibleViewModel.setNotificationEnabled(true) }

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

    GroupedSection(title = stringResource(R.string.settings_section_daily_verse)) {
        GroupedSwitchRow(
            title = stringResource(R.string.settings_daily_reminder),
            subtitle = stringResource(R.string.settings_daily_reminder_subtitle),
            icon = Icons.Outlined.NotificationsActive,
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
        if (notificationEnabled) {
            GroupedDivider()
            GroupedRow(
                title = stringResource(R.string.settings_reminder_time),
                icon = Icons.Outlined.Schedule,
                value = formatNotificationTime(context, notificationTime.hour, notificationTime.minute),
                onClick = { showTimePicker = true }
            )
            if (backgroundRestricted) {
                GroupedDivider()
                GroupedRow(
                    title = stringResource(R.string.settings_background_restricted),
                    titleColor = MaterialTheme.colorScheme.error,
                    subtitle = stringResource(R.string.settings_background_restricted_subtitle),
                    icon = Icons.Outlined.BatteryAlert,
                    iconTint = MaterialTheme.colorScheme.error,
                    onClick = { openAppSettings(context) }
                )
            } else if (!batteryUnrestricted) {
                GroupedDivider()
                GroupedRow(
                    title = stringResource(R.string.settings_reminders_late),
                    subtitle = stringResource(R.string.settings_reminders_late_subtitle),
                    icon = Icons.Outlined.BatteryAlert,
                    onClick = { openAppSettings(context) }
                )
            }
        }
        // The daily verse wallpaper (#257), hidden where the phone doesn't let apps change it.
        if (canSetWallpaper) {
            GroupedDivider()
            GroupedSwitchRow(
                title = stringResource(R.string.settings_daily_wallpaper),
                subtitle = stringResource(
                    if (dailyWallpaper.enabled) R.string.settings_daily_wallpaper_subtitle_on
                    else R.string.settings_daily_wallpaper_subtitle_off
                ),
                icon = Icons.Outlined.Wallpaper,
                checked = dailyWallpaper.enabled,
                onCheckedChange = { checked ->
                    // Turning it on replaces the wallpaper, so it asks first.
                    if (checked) {
                        wallpaperDialog = WallpaperDialog.CONFIRM
                    } else {
                        bibleViewModel.updateDailyWallpaper(dailyWallpaper.copy(enabled = false))
                    }
                }
            )
            if (dailyWallpaper.enabled) {
                GroupedDivider()
                GroupedRow(
                    title = stringResource(R.string.settings_daily_wallpaper_where),
                    icon = Icons.Outlined.Smartphone,
                    value = stringResource(dailyWallpaper.target.labelRes()),
                    onClick = { wallpaperDialog = WallpaperDialog.TARGET }
                )
                GroupedDivider()
                GroupedRow(
                    title = stringResource(R.string.verse_image_style),
                    icon = Icons.Outlined.Palette,
                    value = stringResource(dailyWallpaper.style.labelRes),
                    onClick = { wallpaperDialog = WallpaperDialog.STYLE }
                )
            }
        }
    }

    when (wallpaperDialog) {
        WallpaperDialog.NONE -> Unit
        WallpaperDialog.CONFIRM -> AlertDialog(
            onDismissRequest = { wallpaperDialog = WallpaperDialog.NONE },
            title = { Text(stringResource(R.string.daily_wallpaper_confirm_title)) },
            text = { Text(stringResource(R.string.daily_wallpaper_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    wallpaperDialog = WallpaperDialog.NONE
                    bibleViewModel.updateDailyWallpaper(dailyWallpaper.copy(enabled = true))
                }) { Text(stringResource(R.string.daily_wallpaper_turn_on)) }
            },
            dismissButton = {
                TextButton(onClick = { wallpaperDialog = WallpaperDialog.NONE }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
        WallpaperDialog.TARGET -> ChoiceDialog(
            title = stringResource(R.string.settings_daily_wallpaper_where),
            choices = WallpaperTarget.entries.map { it to stringResource(it.labelRes()) },
            selected = dailyWallpaper.target,
            onChoose = { target ->
                wallpaperDialog = WallpaperDialog.NONE
                bibleViewModel.updateDailyWallpaper(dailyWallpaper.copy(target = target))
            },
            onDismiss = { wallpaperDialog = WallpaperDialog.NONE }
        )
        WallpaperDialog.STYLE -> ChoiceDialog(
            title = stringResource(R.string.verse_image_style),
            choices = VerseImageStyle.entries.map { it to stringResource(it.labelRes) },
            selected = dailyWallpaper.style,
            onChoose = { style ->
                wallpaperDialog = WallpaperDialog.NONE
                bibleViewModel.updateDailyWallpaper(dailyWallpaper.copy(style = style))
            },
            onDismiss = { wallpaperDialog = WallpaperDialog.NONE }
        )
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
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            text = {
                // A landscape phone leaves the dialog ~200 dp: the 256 dp clock dial overflowed it
                // and drew over the time (#232). Typing the time fits; portrait keeps the dial.
                if (isCompactHeight()) TimeInput(state = pickerState) else TimePicker(state = pickerState)
            }
        )
    }
}

/** "8:05 AM" or "08:05", per the device's 12/24-hour setting and the app language — the
 *  platform's own time format, so the AM/PM marker is translated too. */
private fun formatNotificationTime(context: Context, hour: Int, minute: Int): String {
    val time = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    return DateFormat.getTimeFormat(context).format(time.time)
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

/** Which daily-wallpaper dialog is open (#257). Saveable, so rotating keeps it open. */
private enum class WallpaperDialog { NONE, CONFIRM, TARGET, STYLE }

private fun WallpaperTarget.labelRes(): Int = when (this) {
    WallpaperTarget.HOME -> R.string.wallpaper_target_home
    WallpaperTarget.LOCK -> R.string.wallpaper_target_lock
    WallpaperTarget.BOTH -> R.string.wallpaper_target_both
}

/** A titled list of radio choices; picking one closes it. Scrolls, for landscape. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    choices: List<Pair<T, String>>,
    selected: T,
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                choices.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onChoose(value) }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = Spacing.md)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
