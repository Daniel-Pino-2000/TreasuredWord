package com.application.bibleapp.utils

import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.ChapterStructure
import com.application.bibleapp.data.model.UsfmBookCodes
import com.application.bibleapp.data.model.chapterCount
import com.application.bibleapp.data.model.verseCount

/** A Bible Helper answer split into its text and the verses it cites (#129). */
sealed interface AiAnswerPart {
    data class Text(val text: String) : AiAnswerPart

    /** A verse, range or whole chapter that exists: shown as a link and a verse card. */
    data class Reference(val reference: VerseReference) : AiAnswerPart
}

/**
 * Splits [answer] at its verse markers — `[[JHN 3:16]]`, `[[JHN 3:16-18]]`, `[[PSA 23]]`, with
 * the USFM book code in any case (#129). The AI only names verses; the app shows their text from
 * the user's own Bible, so a marker that doesn't point at a real passage (an unknown code, chapter
 * 0, a verse past the chapter's end) becomes plain text, never a card. A verse counts as real when
 * the selected translation has it ([structure], see [ChapterStructure]) or the bundled KJV does,
 * since a card falls back to the KJV. Brackets that aren't a whole marker stay as they are.
 */
fun parseAiAnswer(answer: String, structure: ChapterStructure = emptyMap()): List<AiAnswerPart> {
    val parts = mutableListOf<AiAnswerPart>()
    val text = StringBuilder()
    var last = 0
    for (match in MARKER.findAll(answer)) {
        text.append(answer, last, match.range.first)
        last = match.range.last + 1
        val reference = match.toReference(structure)
        if (reference == null) {
            // e.g. "XYZ 1:1": readable, but not a link
            text.append(match.groupValues[0].removePrefix("[[").removeSuffix("]]").trim())
        } else {
            if (text.isNotEmpty()) parts += AiAnswerPart.Text(text.toString())
            text.clear()
            parts += AiAnswerPart.Reference(reference)
        }
    }
    text.append(answer, last, answer.length)
    if (text.isNotEmpty()) parts += AiAnswerPart.Text(text.toString())
    return parts
}

/** Each passage [parts] cite, once, in the order they first appear — one verse card each. */
fun List<AiAnswerPart>.citedReferences(): List<VerseReference> =
    filterIsInstance<AiAnswerPart.Reference>().map { it.reference }.distinct()

private fun MatchResult.toReference(structure: ChapterStructure): VerseReference? {
    val (code, chapterText, startText, endText) = destructured
    val bookId = UsfmBookCodes.bookId(code) ?: return null
    val chapter = chapterText.toInt()
    val chapters = maxOf(structure.chapterCount(bookId), BibleBooks.getChapterCount(bookId))
    if (chapter !in 1..chapters) return null
    if (startText.isEmpty()) return VerseReference(bookId, chapter)

    val verses = maxOf(structure.verseCount(bookId, chapter), BibleBooks.getVerseCount(bookId, chapter))
    val start = startText.toInt()
    val end = endText.toIntOrNull() ?: start
    if (start !in 1..verses || end !in start..verses) return null
    return VerseReference(bookId, chapter, start, end.takeIf { it != start })
}

/** `[[`, a three-character code, a chapter, then optionally `:verse` and `-verse`, then `]]`.
 *  Spaces inside are tolerated, and an en dash for the range. Three digits at most, so a long
 *  number can't overflow `toInt`. */
private val MARKER = Regex("""\[\[\s*([0-9A-Za-z]{3})\s+(\d{1,3})(?:\s*:\s*(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?)?\s*]]""")
