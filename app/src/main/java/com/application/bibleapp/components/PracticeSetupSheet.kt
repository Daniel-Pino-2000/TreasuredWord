package com.application.bibleapp.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.application.bibleapp.R
import com.application.bibleapp.data.model.MemoryVerse
import com.application.bibleapp.data.model.PracticeGame
import com.application.bibleapp.navigation.Screen
import com.application.bibleapp.screens.gameHintRes
import com.application.bibleapp.screens.practiceGameName
import com.application.bibleapp.ui.theme.Spacing

private enum class PracticeVerses { DUE, ALL, CHOOSE }

/**
 * "Practice" on the Memorize tab (#363): which passages (due today, all, or picked by hand) and
 * which game (Mixed, or one for all). [verses] are in practice order, most in need first; [dueIds]
 * are the ones due today. [onStart] gets the game's stored name, or
 * [Screen.MemorizePractice.MIXED], and the passages' ids in order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeSetupSheet(
    verses: List<MemoryVerse>,
    dueIds: Set<Long>,
    reference: @Composable (MemoryVerse) -> String,
    onStart: (game: String, ids: List<Long>) -> Unit,
    onDismiss: () -> Unit
) {
    var which by rememberSaveable { mutableStateOf(if (dueIds.isNotEmpty()) PracticeVerses.DUE else PracticeVerses.ALL) }
    var picked by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var game by rememberSaveable { mutableStateOf(Screen.MemorizePractice.MIXED) }
    val ids = when (which) {
        PracticeVerses.DUE -> verses.filter { it.localId in dueIds }.map { it.localId }
        PracticeVerses.ALL -> verses.map { it.localId }
        PracticeVerses.CHOOSE -> verses.filter { it.localId in picked }.map { it.localId }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.statusBarsPadding()
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
            ) {
                Text(
                    text = stringResource(R.string.practice_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }.padding(bottom = Spacing.md)
                )
                SectionLabel(stringResource(R.string.practice_setup_verses))
                Column(Modifier.selectableGroup()) {
                    ChoiceRow(stringResource(R.string.practice_setup_due, dueIds.size), null, which == PracticeVerses.DUE, enabled = dueIds.isNotEmpty()) {
                        which = PracticeVerses.DUE
                    }
                    ChoiceRow(stringResource(R.string.practice_setup_all, verses.size), null, which == PracticeVerses.ALL) {
                        which = PracticeVerses.ALL
                    }
                    ChoiceRow(stringResource(R.string.practice_setup_choose), null, which == PracticeVerses.CHOOSE) {
                        which = PracticeVerses.CHOOSE
                    }
                }
                if (which == PracticeVerses.CHOOSE) {
                    verses.forEach { verse ->
                        val checked = verse.localId in picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(value = checked, role = Role.Checkbox) {
                                    picked = if (checked) picked - verse.localId else picked + verse.localId
                                }
                                .padding(start = Spacing.xl, top = Spacing.xs, bottom = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(reference(verse), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spacing.md))
                        }
                    }
                }
                SectionLabel(stringResource(R.string.practice_setup_game), Modifier.padding(top = Spacing.lg))
                Column(Modifier.selectableGroup()) {
                    ChoiceRow(
                        stringResource(R.string.practice_game_mixed),
                        stringResource(R.string.practice_game_mixed_hint),
                        game == Screen.MemorizePractice.MIXED
                    ) { game = Screen.MemorizePractice.MIXED }
                    PracticeGame.entries.forEach { g ->
                        ChoiceRow(stringResource(practiceGameName(g)), stringResource(gameHintRes(g)), game == g.name) { game = g.name }
                    }
                }
            }
            Button(
                onClick = { onStart(game, ids) },
                enabled = ids.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg)
            ) { Text(stringResource(R.string.practice_setup_start)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() }.padding(vertical = Spacing.xs)
    )
}

@Composable
private fun ChoiceRow(title: String, hint: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
