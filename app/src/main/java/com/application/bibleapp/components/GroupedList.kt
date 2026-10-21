package com.application.bibleapp.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.application.bibleapp.ui.theme.Spacing

// Grouped-list building blocks shared by the You tab and the Settings page, so both read as
// one design: related rows share a single framed panel (same surface + outline as Home's Verse of
// the Day card), split by hairlines, each led by a gold icon in a small tinted square.

/** A labelled, framed group of rows. Put [GroupedDivider] between rows. */
@Composable
fun GroupedSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Spacing.xs)
            )
        }
        GroupedPanel(content = content)
    }
}

/** The bare framed panel, for groups whose content isn't a list of rows (e.g. the profile). */
@Composable
fun GroupedPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(content = content)
    }
}

@Composable
fun GroupedDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** A navigation/action row. Shows a chevron when [onClick] is set and nothing else is trailing;
 *  [value] is a short read-out ("42", "8:00 AM") shown just before it. */
@Composable
fun GroupedRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    GroupedRowLayout(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        icon = icon,
        iconTint = iconTint,
        title = title,
        titleColor = titleColor,
        subtitle = subtitle
    ) {
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when {
            trailing != null -> trailing()
            onClick != null && showChevron -> Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The whole row toggles, not just the switch — a bigger target, announced as one switch. */
@Composable
fun GroupedSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null
) {
    GroupedRowLayout(
        modifier = modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        icon = icon,
        iconTint = MaterialTheme.colorScheme.primary,
        title = title,
        titleColor = MaterialTheme.colorScheme.onSurface,
        subtitle = subtitle
    ) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A short single choice (2–4 options) picked right in the row, via a segmented bar under its
 *  title. A longer list (e.g. languages) should be a [GroupedRow] with a [value] that opens its
 *  own picker page instead — a segmented bar stops fitting a phone's width past ~4 options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> GroupedChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GroupedRowLayout(
            modifier = Modifier,
            icon = icon,
            iconTint = MaterialTheme.colorScheme.primary,
            title = title,
            titleColor = MaterialTheme.colorScheme.onSurface,
            subtitle = null
        ) {}
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.md)
        ) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    icon = {}
                ) {
                    Text(optionLabel(option), maxLines = 1)
                }
            }
        }
    }
}

/** The tinted square a row's icon sits in — also used on its own by the You tab's profile. */
@Composable
fun GroupedIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun GroupedRowLayout(
    modifier: Modifier,
    icon: ImageVector?,
    iconTint: Color,
    title: String,
    titleColor: Color,
    subtitle: String?,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        if (icon != null) GroupedIcon(icon, iconTint)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing()
    }
}
