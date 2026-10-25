package com.application.bibleapp.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.app.LocaleManagerCompat
import com.application.bibleapp.R
import com.application.bibleapp.components.GroupedDivider
import com.application.bibleapp.components.GroupedPanel
import com.application.bibleapp.components.GroupedRow
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.utils.AppLanguage

/**
 * The app's language: "System default" first, then each language written in its own name, the
 * current one checked. Picking one switches the app over in place and leaves you here, so you
 * see the page change language.
 */
@Composable
fun LanguagePickerView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LocalConfiguration.current // the choice can also change from the phone's settings
    val current = AppLanguage.currentTag()
    // What "System default" means right now, so it isn't a blind choice.
    val systemLanguage = LocaleManagerCompat.getSystemLocales(context)[0]?.toLanguageTag()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg)
    ) {
        GroupedPanel(modifier = Modifier.selectableGroup()) {
            LanguageOption(
                title = stringResource(R.string.language_system_default),
                subtitle = systemLanguage?.let { AppLanguage.displayName(it) },
                selected = current == null,
                onSelect = { AppLanguage.select(context, null) }
            )
            AppLanguage.supportedTags.forEach { tag ->
                GroupedDivider()
                LanguageOption(
                    title = AppLanguage.displayName(tag),
                    selected = current == tag,
                    onSelect = { AppLanguage.select(context, tag) }
                )
            }
        }
    }
}

@Composable
private fun LanguageOption(title: String, selected: Boolean, onSelect: () -> Unit, subtitle: String? = null) {
    GroupedRow(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = { if (!selected) onSelect() }
        ),
        trailing = {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    )
}
