package com.application.bibleapp.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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

/** Find A Helpline's directory of free crisis lines by country (ThroughLine). Link only, its name
 *  shown beside it. Checked by hand 2026-11-02: curl 200, the page lists helplines by country. */
const val FIND_A_HELPLINE_URL = "https://findahelpline.com/"

/**
 * Shown above an answer the server flagged `crisis` (#130): someone may be in danger, so before
 * anything else, a kind word and a way to real help now. [onFindHelpline] opens [FIND_A_HELPLINE_URL]
 * through [com.application.bibleapp.utils.ExternalLinks].
 */
@Composable
fun AiCrisisCard(onFindHelpline: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(Icons.Outlined.VolunteerActivism, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(text = stringResource(R.string.ai_crisis_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(text = stringResource(R.string.ai_crisis_message), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onFindHelpline,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            ) {
                Text(stringResource(R.string.ai_crisis_find_helpline))
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.padding(start = Spacing.sm).size(16.dp)
                )
            }
            Text(
                text = stringResource(R.string.source_findahelpline),
                style = MaterialTheme.typography.labelSmall
            )
            Text(text = stringResource(R.string.ai_crisis_emergency), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AiCrisisCardPreview(@PreviewParameter(ThemePalettePreviewProvider::class) palette: ThemePalette) {
    BibleAppTheme(palette = palette) {
        Surface { AiCrisisCard(onFindHelpline = {}, modifier = Modifier.padding(Spacing.lg)) }
    }
}
