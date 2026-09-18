package com.application.bibleapp.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.application.bibleapp.data.model.StoredHeading
import com.application.bibleapp.data.model.VerseRun
import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.ui.theme.ReadingStyle
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.scaledBy

/** Indentation per poem nesting level — enough to read as a poetry line, not just a wrapped paragraph. */
private val POEM_INDENT_STEP = 20.sp

/** Generic marker for every footnote — the API's `caller` is usually just "auto-generate", so a
 *  fixed symbol avoids the complexity of sequential per-chapter lettering for a first pass. */
private const val FOOTNOTE_MARKER = "†"

/** Comfortable line length on wide/tablet screens — text doesn't stretch edge to edge.
 *  ~600dp keeps body copy (18sp serif, ~0.55em average glyph width) around 65-70
 *  characters per line, the upper end of the 60-75cpl reading-comfort range; phones
 *  never reach this width so they're governed by [READING_HORIZONTAL_MARGIN] instead. */
private val MAX_READING_WIDTH = 600.dp

/** Horizontal margin for the reading column. Wider than the app's default [Spacing.lg]
 *  so body text has real breathing room against the screen edge on phones — the
 *  narrowest viewport, where [MAX_READING_WIDTH] never actually kicks in. */
private val READING_HORIZONTAL_MARGIN = Spacing.xl

/** A stray pilcrow the plain-text fallback (no rich content) can't turn into a real break. */
private const val LEGACY_PARAGRAPH_MARKER = "¶ "

/** One or more consecutive verses that read as a single flowing paragraph.
 *  [isContinuation] marks a chunk that only exists because [groupIntoParagraphs] cut a
 *  real paragraph down to size for scroll-target granularity — it isn't a genuine new
 *  paragraph, so [BibleText] renders it with no gap above, right after the chunk before
 *  it, so a long uninterrupted psalm still reads as one continuous block instead of
 *  fracturing into arbitrarily-spaced pieces every [MAX_VERSES_PER_PARAGRAPH] verses. */
private data class VerseParagraph(val verses: List<VerseUI>, val isContinuation: Boolean = false)

/** A long run of verses with no internal paragraph signal (e.g. most of a Psalm, which
 *  only carries a heading on its opening verse) is still re-chunked to roughly this size —
 *  see the second doc paragraph on [groupIntoParagraphs]. */
private const val MAX_VERSES_PER_PARAGRAPH = 6

/** Character range (in one paragraph's [AnnotatedString]) covered by one verse — used to map a
 *  tap/long-press position (via [TextLayoutResult.getOffsetForPosition]) back to which verse was
 *  touched, since a paragraph renders several verses as one flowing block of text. */
private data class VerseSpan(val range: IntRange, val location: VerseLocationDto)

/** Character range covered by one footnote marker glyph. Checked before [VerseSpan]s when
 *  resolving a tap, so tapping the marker itself still opens the footnote instead of registering
 *  as a verse tap — this replaces the old `withLink`/`LinkAnnotation` approach so footnote clicks
 *  and verse tap/long-press share one gesture detector instead of two that could contend for the
 *  same touch. */
private data class FootnoteSpan(val range: IntRange, val noteId: Int)

/** Character range covered by one verse's note glyph (see [NOTE_GLYPH_ID]) — checked before
 *  [VerseSpan]s for the same reason as [FootnoteSpan], so tapping the glyph opens that verse's
 *  note editor instead of just registering as a plain verse tap. */
private data class NoteSpan(val range: IntRange, val location: VerseLocationDto)

/** Shared inline-content id for every note glyph in a paragraph — one [InlineTextContent]
 *  definition reused at each occurrence; which verse a given glyph belongs to comes from
 *  [NoteSpan], not from the id itself. */
private const val NOTE_GLYPH_ID = "note_glyph"

private data class ParagraphContent(
    val text: AnnotatedString,
    val verseSpans: List<VerseSpan>,
    val footnoteSpans: List<FootnoteSpan>,
    val noteSpans: List<NoteSpan>
)

private fun VerseUI.toLocationOrNull(): VerseLocationDto? {
    val book = bookId ?: return null
    val ch = chapter ?: return null
    val v = verse ?: return null
    return VerseLocationDto(book, ch, v)
}

/**
 * Groups verses at real paragraph boundaries only: the chapter's first verse, any verse
 * carrying a heading (a new section always starts a fresh paragraph), or a verse whose
 * own first run was marked [VerseRun.paragraphBreakBefore] by the parser. Everything else
 * continues the paragraph already in progress.
 *
 * If NO verse in the chapter carries a heading or paragraph-break marker at all — the
 * bundled KJV has no rich content whatsoever, and a downloaded translation's source text
 * may never mark paragraph starts — grouping this way would merge the *entire chapter*
 * into a single paragraph. The same failure shows up in a milder disguise whenever a
 * chapter's *only* structural signal is a single heading on its first verse: every Psalm
 * with a superscription ("A Psalm of David") but no further headings or paragraph breaks
 * groups its verse 1 through however many verses it has into one giant paragraph — a
 * 50-verse psalm becomes exactly one paragraph. [BibleText] scrolls to a verse by scrolling
 * to the paragraph (list item) containing it, so a single mega-paragraph makes every verse
 * in the chapter resolve to the same item — scrolling to verse 40 lands wherever verse 1 is.
 * Re-chunking any run of [MAX_VERSES_PER_PARAGRAPH]+ verses that share no real paragraph
 * marker keeps every verse within reach of a nearby scroll target while leaving genuine,
 * reasonably-sized paragraphs (real heading/pilcrow/line_break data) untouched.
 */
private fun groupIntoParagraphs(verses: List<VerseUI>): List<VerseParagraph> {
    val hasParagraphStructure = verses.any { verse ->
        !verse.richContent?.headings.isNullOrEmpty() ||
            verse.richContent?.runs?.firstOrNull()?.paragraphBreakBefore == true
    }
    val groups: List<List<VerseUI>> = if (!hasParagraphStructure) {
        verses.map { listOf(it) }
    } else {
        val built = mutableListOf<MutableList<VerseUI>>()
        verses.forEach { verse ->
            val hasHeading = !verse.richContent?.headings.isNullOrEmpty()
            val startsNewParagraph = verse.richContent?.runs?.firstOrNull()?.paragraphBreakBefore == true
            if (built.isEmpty() || hasHeading || startsNewParagraph) {
                built += mutableListOf(verse)
            } else {
                built.last() += verse
            }
        }
        built
    }
    return groups.flatMap { group ->
        group.chunked(MAX_VERSES_PER_PARAGRAPH).mapIndexed { chunkIndex, chunk ->
            VerseParagraph(chunk, isContinuation = chunkIndex > 0)
        }
    }
}

@Composable
fun BibleText(
    verses: List<VerseUI>,
    scrollToIndex: Int,
    chapterTitle: String,
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
    onFootnoteClick: (Int) -> Unit = {},
    /** Background color for a verse already covered by a saved highlight, keyed by location. */
    highlightColorsByVerse: Map<VerseLocationDto, Color> = emptyMap(),
    /** Verses currently selected (long-pressed/tapped) but not yet saved as a highlight. */
    selectedVerses: Set<VerseLocationDto> = emptySet(),
    onVerseTap: (VerseLocationDto) -> Unit = {},
    onVerseLongPress: (VerseLocationDto) -> Unit = {},
    /** Verses that have an active note — rendered with an inline glyph right after the verse number. */
    notedVerses: Set<VerseLocationDto> = emptySet(),
    onNoteGlyphTap: (VerseLocationDto) -> Unit = {}
) {
    // Create a new LazyListState each time the verses list changes
    val listState = remember(verses) { androidx.compose.foundation.lazy.LazyListState() }
    val paragraphs = remember(verses) { groupIntoParagraphs(verses) }

    // Scroll to the paragraph containing the desired verse whenever verses or the
    // scroll target changes — paragraphs, not verses, are the list's item granularity.
    // The chapter title occupies list item 0, ahead of every paragraph (item 1 = the
    // first paragraph, item 2 = the second, etc). Landing on the first paragraph scrolls
    // to item 0 instead of item 1, so the title is the thing snapped to the top of the
    // viewport — not pushed off-screen above whatever paragraph verse 1 sits in — matching
    // every other paragraph, which does scroll flush to the top of the viewport.
    LaunchedEffect(paragraphs, scrollToIndex) {
        if (paragraphs.isEmpty()) return@LaunchedEffect
        val targetIndex = paragraphs.indexOfFirst { paragraph ->
            paragraph.verses.any { it.verse == scrollToIndex }
        }.coerceIn(paragraphs.indices)
        val listItemIndex = if (targetIndex == 0) 0 else targetIndex + 1
        listState.scrollToItem(listItemIndex)
    }

    // Material's "error" role is spec'd to always be a red-family tone in both light
    // and dark schemes (and under dynamic color), so it doubles as a theme-aware
    // red-letter color without a hardcoded hex that could look wrong in dark mode.
    val wordsOfJesusColor = MaterialTheme.colorScheme.error
    val footnoteColor = MaterialTheme.colorScheme.primary
    val verseNumberColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Deliberately distinct from any saved highlight color (see ui/theme/HighlightColors.kt) so
    // "currently selecting" never looks like "already highlighted".
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val noteGlyphColor = MaterialTheme.colorScheme.primary
    // Roughly matches the verse-number superscript's visual weight rather than the full line
    // height, so the glyph reads as a small marker, not a mid-sized icon interrupting the text.
    val noteGlyphSize = (12 * textScale).sp
    val noteGlyphInlineContent = remember(noteGlyphColor, noteGlyphSize) {
        mapOf(
            NOTE_GLYPH_ID to InlineTextContent(
                Placeholder(
                    width = noteGlyphSize,
                    height = noteGlyphSize,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.StickyNote2,
                    contentDescription = "Note",
                    tint = noteGlyphColor,
                    modifier = Modifier.fillMaxSize()
                )
            }
        )
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(vertical = Spacing.xl, horizontal = READING_HORIZONTAL_MARGIN)
    ) {
        item {
            ChapterHeader(chapterTitle, textScale)
        }
        itemsIndexed(paragraphs) { index, paragraph ->
            val headings = paragraph.verses.first().richContent?.headings.orEmpty()
            val content = remember(paragraph, highlightColorsByVerse, selectedVerses, notedVerses, textScale) {
                paragraphAnnotatedString(
                    paragraph.verses,
                    wordsOfJesusColor,
                    footnoteColor,
                    verseNumberColor,
                    textScale,
                    highlightColorsByVerse,
                    selectedVerses,
                    selectionColor,
                    notedVerses
                )
            }
            var layoutResult by remember(paragraph) { mutableStateOf<TextLayoutResult?>(null) }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(modifier = Modifier.widthIn(max = MAX_READING_WIDTH).fillMaxWidth()) {
                    headings.forEach { heading ->
                        HeadingText(heading, textScale)
                    }
                    Text(
                        text = content.text,
                        style = ReadingStyle.VerseText.scaledBy(textScale),
                        color = MaterialTheme.colorScheme.onSurface,
                        inlineContent = noteGlyphInlineContent,
                        // A heading already reads as a section break on its own, and a
                        // continuation chunk (see VerseParagraph.isContinuation) isn't a real
                        // paragraph start at all — only add the gap above paragraphs that
                        // both start without a heading and are genuinely new.
                        modifier = Modifier
                            .padding(
                                top = if (index > 0 && headings.isEmpty() && !paragraph.isContinuation) {
                                    Spacing.xl
                                } else {
                                    0.dp
                                },
                                bottom = Spacing.xs
                            )
                            .pointerInput(paragraph) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        val layout = layoutResult ?: return@detectTapGestures
                                        val charOffset = layout.getOffsetForPosition(offset)
                                        val footnote = content.footnoteSpans.firstOrNull { charOffset in it.range }
                                        if (footnote != null) {
                                            onFootnoteClick(footnote.noteId)
                                            return@detectTapGestures
                                        }
                                        val noteGlyph = content.noteSpans.firstOrNull { charOffset in it.range }
                                        if (noteGlyph != null) {
                                            onNoteGlyphTap(noteGlyph.location)
                                            return@detectTapGestures
                                        }
                                        val verse = content.verseSpans.firstOrNull { charOffset in it.range }
                                        if (verse != null) onVerseTap(verse.location)
                                    },
                                    onLongPress = { offset ->
                                        val layout = layoutResult ?: return@detectTapGestures
                                        val charOffset = layout.getOffsetForPosition(offset)
                                        val verse = content.verseSpans.firstOrNull { charOffset in it.range }
                                        if (verse != null) onVerseLongPress(verse.location)
                                    }
                                )
                            },
                        onTextLayout = { layoutResult = it }
                    )
                }
            }
        }
    }
}

/**
 * The chapter's own title (e.g. "Genesis 1") — shown above every chapter regardless of
 * what the translation's own markup does or doesn't have, so there's always at least
 * one clear heading at the top of the reading surface. Centered within the same
 * max-width column as the verse text so it stays aligned with the body on wide screens.
 */
@Composable
private fun ChapterHeader(title: String, textScale: Float) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.widthIn(max = MAX_READING_WIDTH).fillMaxWidth()) {
            Text(
                text = title,
                style = ReadingStyle.ChapterTitle.scaledBy(textScale),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = Spacing.md)
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
                // Clearly separates the title block from the first line of verse text below —
                // more than the gap used between ordinary paragraphs.
                modifier = Modifier.padding(bottom = Spacing.xl)
            )
        }
    }
}

/** A section heading or Hebrew subtitle preceding the verse it introduces. */
@Composable
private fun HeadingText(heading: StoredHeading, textScale: Float) {
    if (heading.type == "hebrew_subtitle") {
        Text(
            text = heading.text,
            style = ReadingStyle.HebrewSubtitle.scaledBy(textScale),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xs)
        )
    } else {
        Text(
            text = heading.text,
            style = ReadingStyle.SectionHeading.scaledBy(textScale),
            color = MaterialTheme.colorScheme.onSurface,
            // A pericope title needs to read as a firm break from the previous passage,
            // not just a slightly-bolder line butted up against it.
            modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.sm)
        )
    }
}

/**
 * Builds one flowing paragraph's worth of text from however many verses belong to it:
 * verse numbers render as small superscript markers inline (not line-starts), words-of-Jesus
 * runs are colored, poem-tagged runs each get their own indented line via [ParagraphStyle],
 * and a clickable superscript marker follows any run with an attached footnote. Verses with
 * no rich content at all (legacy rows) fall back to their plain text. A verse with an active note
 * gets an inline glyph ([NOTE_GLYPH_ID]) right after its verse number. Alongside the text, tracks
 * each verse's, footnote marker's, and note glyph's character range ([VerseSpan]/[FootnoteSpan]/
 * [NoteSpan]) so a tap or long-press position (resolved to a character offset via the rendered
 * [TextLayoutResult]) can be mapped back to what was actually touched.
 */
private fun paragraphAnnotatedString(
    verses: List<VerseUI>,
    wordsOfJesusColor: Color,
    footnoteColor: Color,
    verseNumberColor: Color,
    textScale: Float,
    highlightColorsByVerse: Map<VerseLocationDto, Color>,
    selectedVerses: Set<VerseLocationDto>,
    selectionColor: Color,
    notedVerses: Set<VerseLocationDto>
): ParagraphContent {
    val verseNumberStyle = ReadingStyle.VerseNumber.scaledBy(textScale).toSpanStyle().copy(
        color = verseNumberColor,
        baselineShift = BaselineShift.Superscript
    )
    val footnoteMarkerStyle = SpanStyle(
        color = footnoteColor,
        fontSize = 11.sp * textScale,
        baselineShift = BaselineShift.Superscript
    )

    val verseSpans = mutableListOf<VerseSpan>()
    val footnoteSpans = mutableListOf<FootnoteSpan>()
    val noteSpans = mutableListOf<NoteSpan>()

    val text = buildAnnotatedString {
        var isFirstRunInParagraph = true

        verses.forEach { verse ->
            val verseStart = length
            val location = verse.toLocationOrNull()
            val hasNote = location != null && location in notedVerses
            val runs = verse.richContent?.runs

            fun appendVerseOpening() {
                appendVerseNumber(verse.verse, verseNumberStyle)
                if (hasNote) appendNoteGlyph(location!!, noteSpans)
            }

            if (runs.isNullOrEmpty()) {
                if (!isFirstRunInParagraph) append(" ")
                appendVerseOpening()
                append(verse.text.removePrefix(LEGACY_PARAGRAPH_MARKER))
                isFirstRunInParagraph = false
            } else {
                runs.forEachIndexed { index, run ->
                    val isFirstRunOfVerse = index == 0
                    val startsNewLine = run.poemLevel != null || (run.lineBreakBefore && !isFirstRunInParagraph)

                    if (startsNewLine) {
                        // Verified on-device with a pixel-level before/after comparison:
                        // giving a poem line's *first* display line a non-zero indent
                        // while it also opens with the verse-number superscript throws
                        // off Compose's indent for that paragraph's *wrapped* line — the
                        // wrap renders less indented than the first line instead of
                        // matching it. Lines that don't carry a verse number (every
                        // second-clause/continuation poem run) aren't affected — their
                        // first line and wrap both indent correctly at the same value.
                        // So: a verse-opening poem line gets firstLine=0 (the verse
                        // number sits flush at the margin, same as it does in ordinary
                        // prose paragraphs elsewhere in this file) and only its wrap
                        // picks up the poem indent; every other poem line indents
                        // uniformly on both its first line and its wrap.
                        val indent = POEM_INDENT_STEP * (run.poemLevel ?: 0)
                        val firstLineIndent = if (isFirstRunOfVerse) 0.sp else indent
                        val style = ParagraphStyle(textIndent = TextIndent(firstLine = firstLineIndent, restLine = indent))
                        withStyle(style) {
                            if (isFirstRunOfVerse) appendVerseOpening()
                            appendRun(run, wordsOfJesusColor, footnoteMarkerStyle, footnoteSpans)
                        }
                    } else {
                        if (!isFirstRunInParagraph) append(" ")
                        if (isFirstRunOfVerse) appendVerseOpening()
                        appendRun(run, wordsOfJesusColor, footnoteMarkerStyle, footnoteSpans)
                    }
                    isFirstRunInParagraph = false
                }
            }

            val verseEnd = length
            if (location != null) {
                verseSpans += VerseSpan(verseStart until verseEnd, location)
                val background = if (location in selectedVerses) selectionColor else highlightColorsByVerse[location]
                if (background != null) {
                    addStyle(SpanStyle(background = background), verseStart, verseEnd)
                }
            }
        }
    }

    return ParagraphContent(text, verseSpans, footnoteSpans, noteSpans)
}

private fun AnnotatedString.Builder.appendVerseNumber(verseNumber: Int?, style: SpanStyle) {
    withStyle(style) { append("${verseNumber ?: ""}") }
    append(" ")
}

private fun AnnotatedString.Builder.appendNoteGlyph(location: VerseLocationDto, noteSpans: MutableList<NoteSpan>) {
    val glyphStart = length
    appendInlineContent(NOTE_GLYPH_ID, "[note]")
    append(" ")
    noteSpans += NoteSpan(glyphStart until length, location)
}

private fun AnnotatedString.Builder.appendRun(
    run: VerseRun,
    wordsOfJesusColor: Color,
    footnoteMarkerStyle: SpanStyle,
    footnoteSpans: MutableList<FootnoteSpan>
) {
    if (run.isWordsOfJesus) {
        withStyle(SpanStyle(color = wordsOfJesusColor)) { append(run.text) }
    } else {
        append(run.text)
    }

    val noteId = run.footnoteId ?: return
    val markerStart = length
    withStyle(footnoteMarkerStyle) { append(FOOTNOTE_MARKER) }
    footnoteSpans += FootnoteSpan(markerStart until length, noteId)
}
