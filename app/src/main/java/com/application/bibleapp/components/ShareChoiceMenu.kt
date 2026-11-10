package com.application.bibleapp.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.application.bibleapp.R

/**
 * "Share as text" or "Share as image" (#254), dropped down from a Share button. Place it inside
 * the button's own layout so the menu opens next to it. Picking either closes the menu first.
 */
@Composable
fun ShareChoiceMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onShareText: () -> Unit,
    onShareImage: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.share_as_text)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Notes, contentDescription = null) },
            onClick = {
                onDismiss()
                onShareText()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.share_as_image)) },
            leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
            onClick = {
                onDismiss()
                onShareImage()
            }
        )
    }
}
