package com.application.bibleapp.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.BibleAppTheme
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.ThemePalette
import com.application.bibleapp.ui.theme.ThemePalettePreviewProvider

/**
 * Search's offer of the Bible Helper (#401), when the server says the search reads like a request
 * for help ("my dad is in the hospital and I'm scared") rather than a look-up. Search finds verses
 * that sound like those words; the Bible Helper answers them with Scripture. [onAsk] opens Ask with
 * the words in the field, unsent: an answer costs ~36 of the day's free neurons, so it's never
 * automatic. A banner, so `primaryContainer` (CLAUDE.md, theme).
 */
@Composable
fun AskHelperCard(onAsk: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(Icons.Outlined.QuestionAnswer, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(text = stringResource(R.string.search_ask_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(text = stringResource(R.string.search_ask_message), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAsk) { Text(stringResource(R.string.search_ask_button)) }
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AskHelperCardPreview(@PreviewParameter(ThemePalettePreviewProvider::class) palette: ThemePalette) {
    BibleAppTheme(palette = palette) {
        Surface { AskHelperCard(onAsk = {}, modifier = Modifier.padding(Spacing.lg)) }
    }
}
