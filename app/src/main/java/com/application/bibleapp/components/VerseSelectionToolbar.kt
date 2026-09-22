package com.application.bibleapp.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.application.bibleapp.ui.theme.Spacing
import androidx.compose.ui.unit.dp

/**
 * Takes over Scaffold's `bottomBar` slot (see MainActivity) while one or more verses are
 * selected (BibleViewModel.selectedVerses / docs/UI_Integration_Roadmap.md) — rather than
 * floating as a second overlay stacked on top of the ordinary bottom chrome, which used to leave
 * both visible at once. Picking a color applies it and leaves the selection (and this toolbar)
 * open, so a note can still be added to the same verses; only Cancel or the Note action end it.
 *
 * Rounded on top only, flat on the bottom — like a sheet, but it fills this slot exactly the way
 * [MainBottomBar] does (same edge-to-edge width, same self-applied [navigationBarsPadding]), so
 * a symmetrically-rounded shape would expose whatever's behind the app at its bottom corners.
 */
@Composable
fun VerseSelectionToolbar(
    selectionCount: Int,
    onColorSelected: (Int) -> Unit,
    onAddNote: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectionCount == 1) "1 verse selected" else "$selectionCount verses selected",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    // A labeled filled-tonal chip, not another plain icon button — the one
                    // action here that leaves this toolbar (into the note editor) needs to read
                    // as the clear primary action, not blend in next to Cancel.
                    FilledTonalButton(
                        onClick = onAddNote,
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Note")
                    }
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                    }
                }
            }
            HighlightColorSwatchRow(
                onColorSelected = onColorSelected,
                modifier = Modifier.padding(top = Spacing.sm)
            )
        }
    }
}
