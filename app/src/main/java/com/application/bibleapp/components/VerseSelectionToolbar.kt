package com.application.bibleapp.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.Spacing

/**
 * A genuinely floating card — margin on every side, all four corners rounded, a real cast
 * shadow — positioned by MainActivity a fixed gap above [MainBottomBar]'s current top edge,
 * never inside its own slot or flush against it: it should read as "hovering over" the bottom
 * chrome, not "docked onto" or "replacing" it. MainActivity recomputes that gap continuously off
 * [MainBottomBar]'s live measured height, so this stays correctly positioned whether the tab row
 * is fully shown, mid-collapse, or fully hidden on scroll.
 *
 * Picking a color applies it and leaves the selection (and this toolbar) open, so a note can
 * still be added to the same verses; Copy, Share, Note and Cancel each end it.
 */
@Composable
fun VerseSelectionToolbar(
    selectionCount: Int,
    onColorSelected: (Int) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onAddNote: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.md)
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
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                }
            }
            HighlightColorSwatchRow(
                onColorSelected = onColorSelected,
                modifier = Modifier.padding(end = Spacing.md)
            )
            // Labeled actions, Note emphasized as the one that opens an editor rather than
            // handing the verses off — it gets the filled container, Copy/Share stay quiet.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md, end = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                SelectionAction("Copy", rememberVectorPainter(Icons.Outlined.ContentCopy), onCopy)
                SelectionAction("Share", rememberVectorPainter(Icons.Outlined.Share), onShare)
                SelectionAction("Note", painterResource(R.drawable.ic_stylus_note), onAddNote, emphasized = true)
            }
        }
    }
}

@Composable
private fun RowScope.SelectionAction(
    label: String,
    icon: Painter,
    onClick: () -> Unit,
    emphasized: Boolean = false
) {
    // Solid primary, not primaryContainer — the container tone sits too close to this card's own
    // tonal surface to read as emphasis.
    val container = if (emphasized) MaterialTheme.colorScheme.primary else Color.Transparent
    val content = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content
    ) {
        Column(
            modifier = Modifier.padding(vertical = Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(painter = icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
