package com.application.bibleapp.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.audio.CHAPTER_AUDIO_SKIP_MS
import com.application.bibleapp.data.model.AudioNarrator
import com.application.bibleapp.data.model.ChapterAudio
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.viewmodel.ChapterAudioState
import java.text.NumberFormat

/**
 * The reader's mini player for chapter audio (#367), above the chapter row (or at the bottom of
 * the reader in landscape). The chapter and who reads it, back 15 s, play/pause, forward 15 s,
 * speed and close. In portrait the controls get a row of their own, since one row on a 360 dp
 * phone left the chapter's name about 100 dp; [compact] (landscape) keeps everything in one row,
 * so the reader keeps its height. The credit line names the narrator and the version, and opens
 * the narrator choice when there's more than one.
 */
@Composable
fun ChapterAudioPlayer(
    state: ChapterAudioState,
    chapterTitle: String,
    versionLabel: String,
    narrators: List<AudioNarrator>,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onNarratorChange: (AudioNarrator) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val item = state.item ?: return
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = NavigationBarDefaults.Elevation
    ) {
        Column {
            // Indeterminate while it loads, so a slow connection doesn't look like a stuck player.
            // The track is set by hand: the default is secondaryContainer, solid black in Ink.
            if (state.isBuffering || state.durationMs <= 0) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(PROGRESS_HEIGHT),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
            } else {
                LinearProgressIndicator(
                    progress = { (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(PROGRESS_HEIGHT),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }
            val credit: @Composable (Modifier) -> Unit = { creditModifier ->
                NarratorCredit(
                    chapterTitle = chapterTitle,
                    credit = stringResource(R.string.audio_read_by, item.narrator.displayName, versionLabel),
                    narrators = narrators,
                    selected = item.narrator,
                    onNarratorChange = onNarratorChange,
                    modifier = creditModifier
                )
            }
            val transport: @Composable () -> Unit = {
                IconButton(onClick = onSeekBack) {
                    SkipIcon(forward = false, contentDescription = stringResource(R.string.audio_back_15))
                }
                FilledIconButton(
                    onClick = onPlayPause,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (state.isPlaying) {
                        Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.audio_pause))
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.audio_play))
                    }
                }
                IconButton(onClick = onSeekForward) {
                    SkipIcon(forward = true, contentDescription = stringResource(R.string.audio_forward_15))
                }
            }
            val closeButton: @Composable () -> Unit = {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.audio_stop),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (compact) {
                Row(
                    modifier = Modifier.padding(start = Spacing.lg, end = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    credit(Modifier.weight(1f))
                    transport()
                    SpeedButton(speed = state.speed, onSpeedChange = onSpeedChange)
                    closeButton()
                }
            } else {
                Row(
                    modifier = Modifier.padding(start = Spacing.lg, end = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    credit(Modifier.weight(1f))
                    SpeedButton(speed = state.speed, onSpeedChange = onSpeedChange)
                    closeButton()
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    transport()
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun NarratorCredit(
    chapterTitle: String,
    credit: String,
    narrators: List<AudioNarrator>,
    selected: AudioNarrator,
    onNarratorChange: (AudioNarrator) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val canChoose = narrators.size > 1
    val chooseLabel = stringResource(R.string.audio_choose_narrator)
    Box(modifier) {
        Column(
            modifier = Modifier
                .then(
                    if (canChoose) {
                        Modifier.clickable(onClickLabel = chooseLabel, role = Role.Button) { menuOpen = true }
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = Spacing.sm)
        ) {
            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = credit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (canChoose) {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            narrators.forEach { narrator ->
                DropdownMenuItem(
                    text = { Text(narrator.displayName) },
                    // An empty slot when unchecked, so every option lines up with the checked one.
                    leadingIcon = {
                        if (narrator == selected) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(MENU_ICON_SIZE))
                    },
                    onClick = {
                        menuOpen = false
                        onNarratorChange(narrator)
                    }
                )
            }
        }
    }
}

@Composable
private fun SpeedButton(speed: Float, onSpeedChange: (Float) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    // The app's language, not the phone's: "0,75×" in German.
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 } }
    val label = { value: Float -> format.format(value) }
    Box {
        TextButton(onClick = { menuOpen = true }) {
            Text(
                text = stringResource(R.string.audio_speed_value, label(speed)),
                style = MaterialTheme.typography.labelLarge
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Text(
                text = stringResource(R.string.audio_speed_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
            ChapterAudio.speeds.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.audio_speed_value, label(option))) },
                    // An empty slot when unchecked, so every option lines up with the checked one.
                    leadingIcon = {
                        if (option == speed) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(MENU_ICON_SIZE))
                    },
                    onClick = {
                        menuOpen = false
                        onSpeedChange(option)
                    }
                )
            }
        }
    }
}

/** Material's replay arrow with the seconds inside it, mirrored for forward. Material Icons
 *  only come with 5, 10 and 30 seconds; the number is a fixed size so it stays inside the arrow
 *  at any font scale (the button's description says it in full). */
@Composable
private fun SkipIcon(forward: Boolean, contentDescription: String) {
    val seconds = (CHAPTER_AUDIO_SKIP_MS / 1000).toString()
    val numberSize = with(LocalDensity.current) { 8.dp.toSp() }
    Box(contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.Replay,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.graphicsLayer { if (forward) scaleX = -1f }
        )
        Text(
            text = seconds,
            color = MaterialTheme.colorScheme.primary,
            fontSize = numberSize,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 3.dp).clearAndSetSemantics {}
        )
    }
}

private val PROGRESS_HEIGHT = 3.dp
private val MENU_ICON_SIZE = 24.dp
