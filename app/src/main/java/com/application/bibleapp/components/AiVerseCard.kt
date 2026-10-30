package com.application.bibleapp.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.em
import com.application.bibleapp.R
import com.application.bibleapp.data.model.AiVersePassage
import com.application.bibleapp.data.model.PassageVerse
import com.application.bibleapp.ui.theme.BibleAppTheme
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.utils.VerseReference
import com.application.bibleapp.utils.bookNameInVersion
import com.application.bibleapp.utils.formatVerseReference
import com.application.bibleapp.viewmodel.BibleViewModel

/** How many verse cards an answer shows before "Show N more" (#129). */
const val AI_VERSE_CARDS_SHOWN = 5

/** Where a card's passage is while it loads from the phone's Bible. */
sealed interface AiVerseCardText {
    data object Loading : AiVerseCardText
    data object Missing : AiVerseCardText
    data class Loaded(val passage: AiVersePassage, val versionLabel: String) : AiVerseCardText
}

/**
 * One cited passage under a Bible Helper answer (#129): the reference in the selected version's
 * book names, its text from the user's own Bible (the KJV when the selected translation lacks it,
 * see [BibleViewModel.aiVersePassage]) and that translation's abbreviation. The card and "Read the
 * whole chapter" both call [onReadChapter], which opens the reader at the passage's first verse the
 * way a Search result does (#59): `bibleViewModel.setBook(...)`, then navigate to the reader.
 */
@Composable
fun AiVerseCard(
    reference: VerseReference,
    bibleViewModel: BibleViewModel,
    onReadChapter: (bookId: Int, chapter: Int, verse: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val bookNames by bibleViewModel.bookNames.collectAsState()
    val selectedVersion by bibleViewModel.selectedVersion.collectAsState()
    // Reloads when the translation changes, so the card always matches the reader.
    val text by produceState<AiVerseCardText>(AiVerseCardText.Loading, reference, selectedVersion) {
        value = bibleViewModel.aiVersePassage(reference)
            ?.let { AiVerseCardText.Loaded(it, bibleViewModel.versionLabel(it.versionId)) }
            ?: AiVerseCardText.Missing
    }
    AiVerseCardContent(
        reference = formatVerseReference(reference) {
            bookNameInVersion(selectedVersion.id, it, mapOf(selectedVersion.id to bookNames))
        },
        text = text,
        onReadChapter = { onReadChapter(reference.bookId, reference.chapter, reference.startVerse ?: 1) },
        modifier = modifier
    )
}

/** [AiVerseCard] without the loading, for previews and tests. */
@Composable
fun AiVerseCardContent(
    reference: String,
    text: AiVerseCardText,
    onReadChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(onClick = onReadChapter, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(
            modifier = Modifier.padding(start = Spacing.lg, top = Spacing.md, end = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = reference,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (text is AiVerseCardText.Loaded) {
                    Text(
                        text = text.versionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            when (text) {
                AiVerseCardText.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm))
                AiVerseCardText.Missing -> Text(
                    text = stringResource(R.string.ai_verse_not_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is AiVerseCardText.Loaded -> Text(
                    text = passageText(text.passage.verses, MaterialTheme.colorScheme.onSurfaceVariant),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                    // A whole chapter would fill the screen; the reader is one tap away.
                    maxLines = PASSAGE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        TextButton(
            onClick = onReadChapter,
            modifier = Modifier.align(Alignment.End).padding(end = Spacing.xs)
        ) {
            Text(stringResource(R.string.ai_verse_read_chapter))
        }
    }
}

/**
 * An answer's verse cards: the first [AI_VERSE_CARDS_SHOWN], then "Show N more" for the rest
 * (#129). [card] draws one, normally an [AiVerseCard].
 */
@Composable
fun AiVerseCardList(
    references: List<VerseReference>,
    modifier: Modifier = Modifier,
    card: @Composable (VerseReference) -> Unit
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val hidden = references.size - AI_VERSE_CARDS_SHOWN
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        (if (showAll || hidden <= 0) references else references.take(AI_VERSE_CARDS_SHOWN)).forEach { card(it) }
        if (!showAll && hidden > 0) {
            TextButton(onClick = { showAll = true }) {
                Text(pluralStringResource(R.plurals.ai_verse_cards_show_more, hidden, hidden))
            }
        }
    }
}

/** The verses as one paragraph, each numbered in superscript when there's more than one. */
private fun passageText(verses: List<PassageVerse>, numberColor: Color): AnnotatedString = buildAnnotatedString {
    verses.forEachIndexed { index, verse ->
        if (index > 0) append(' ')
        if (verses.size > 1) {
            withStyle(SpanStyle(color = numberColor, fontWeight = FontWeight.Medium, fontSize = 0.7.em, baselineShift = BaselineShift.Superscript)) {
                append(verse.number.toString())
            }
            append(' ')
        }
        append(verse.text)
    }
}

private const val PASSAGE_MAX_LINES = 8

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Landscape", widthDp = 780, heightDp = 360)
@Composable
private fun AiVerseCardPreview() {
    BibleAppTheme {
        Surface {
            Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AiVerseCardContent(
                    reference = "Juan 3:16-17",
                    text = AiVerseCardText.Loaded(
                        AiVersePassage(
                            "spa_rv1909",
                            listOf(
                                PassageVerse(16, "Porque de tal manera amó Dios al mundo, que ha dado á su Hijo unigénito, para que todo aquel que en él cree, no se pierda, mas tenga vida eterna."),
                                PassageVerse(17, "Porque no envió Dios á su Hijo al mundo, para que condene al mundo, mas para que el mundo sea salvo por él.")
                            )
                        ),
                        versionLabel = "RV1909"
                    ),
                    onReadChapter = {}
                )
                AiVerseCardContent(reference = "Romans 8:28", text = AiVerseCardText.Loading, onReadChapter = {})
                AiVerseCardContent(reference = "Psalms 23", text = AiVerseCardText.Missing, onReadChapter = {})
            }
        }
    }
}
