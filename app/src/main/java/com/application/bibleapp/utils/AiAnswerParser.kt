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

    /** A whole book, such as `[[PSA]]`: shown as the book's name, without a card (#190). */
    data class Book(val bookId: Int) : AiAnswerPart
}

/**
 * Splits [answer] at its verse markers — `[[JHN 3:16]]`, `[[JHN 3:16-18]]`, `[[PSA 23]]`, with
 * the USFM book code in any case (#129). The AI only names verses; the app shows their text from
 * the user's own Bible, so a marker that doesn't point at a real passage (an unknown book, chapter
 * 0, a verse past the chapter's end) becomes plain text, never a card. A verse counts as real when
 * the selected translation has it ([structure], see [ChapterStructure]) or the bundled KJV does,
 * since a card falls back to the KJV.
 *
 * The model gets the codes right most of the time but not always, so the parser forgives (#190):
 * a full name or common abbreviation instead of the code (`JOHN`, `PSALM`, `EZEK`, `PHI`), a space
 * after the number (`1 JHN`), two markers in one (`[[PSA 103:8], [1JN 1:9]]`) and a whole book
 * (`[[PSA]]`). Whatever can't be read shows its inner text: `[[` and `]]` never reach the screen.
 */
fun parseAiAnswer(answer: String, structure: ChapterStructure = emptyMap()): List<AiAnswerPart> {
    val parts = mutableListOf<AiAnswerPart>()
    val text = StringBuilder()
    fun addText(value: String) {
        text.append(value.replace("[[", "").replace("]]", ""))
    }
    fun add(part: AiAnswerPart) {
        if (text.isNotEmpty()) parts += AiAnswerPart.Text(text.toString())
        text.clear()
        parts += part
    }

    var last = 0
    for (match in MARKER.findAll(answer)) {
        addText(answer.substring(last, match.range.first))
        last = match.range.last + 1
        // "PSA 103:8], [1JN 1:9" → "PSA 103:8", ", ", "1JN 1:9"
        val inner = match.groupValues[1]
        var pieceStart = 0
        for (separator in SEPARATOR.findAll(inner) + sequenceOf(null)) {
            val end = separator?.range?.first ?: inner.length
            val piece = inner.substring(pieceStart, end).replace("[", "").replace("]", "").trim()
            when (val part = piece.toPart(structure)) {
                null -> addText(piece) // e.g. "XYZ 1:1": readable, but not a link
                else -> add(part)
            }
            if (separator != null) {
                addText(separator.groupValues[1] + " ")
                pieceStart = separator.range.last + 1
            }
        }
    }
    addText(answer.substring(last))
    if (text.isNotEmpty()) parts += AiAnswerPart.Text(text.toString())
    return parts
}

/** Each passage [parts] cite, once, in the order they first appear — one verse card each. */
fun List<AiAnswerPart>.citedReferences(): List<VerseReference> =
    filterIsInstance<AiAnswerPart.Reference>().map { it.reference }.distinct()

/**
 * [parts] as plain text, a reference or whole book in [bookName]'s names (the selected Bible's, as
 * the verse cards use): "Juan 3:16", "Salmos". For copying an answer and the conversation list.
 */
fun List<AiAnswerPart>.plainText(bookName: (Int) -> String): String = joinToString("") { part ->
    when (part) {
        is AiAnswerPart.Text -> part.text
        is AiAnswerPart.Reference -> formatVerseReference(part.reference, bookName)
        is AiAnswerPart.Book -> bookName(part.bookId)
    }
}

/** One marker's text as a passage or a whole book, or null when it doesn't name a real one. */
private fun String.toPart(structure: ChapterStructure): AiAnswerPart? {
    val match = PIECE.matchEntire(this) ?: return null
    val (bookText, chapterText, startText, endText) = match.destructured
    // The code the instructions ask for first, then a name or abbreviation ("1 JHN" → 1 John).
    val bookId = UsfmBookCodes.bookId(bookText.replace(" ", "")) ?: findBook(bookText) ?: return null
    if (chapterText.isEmpty()) return AiAnswerPart.Book(bookId)

    val chapters = maxOf(structure.chapterCount(bookId), BibleBooks.getChapterCount(bookId))
    // A one-chapter book is often cited by verse alone: "Jude 5" is Jude 1:5.
    if (chapters == 1 && startText.isEmpty() && chapterText.toInt() > 1) {
        return reference(bookId, 1, chapterText, "", structure)?.let(AiAnswerPart::Reference)
    }
    return reference(bookId, chapterText.toInt(), startText, endText, structure)?.let(AiAnswerPart::Reference)
}

private fun reference(
    bookId: Int,
    chapter: Int,
    startText: String,
    endText: String,
    structure: ChapterStructure
): VerseReference? {
    val chapters = maxOf(structure.chapterCount(bookId), BibleBooks.getChapterCount(bookId))
    if (chapter !in 1..chapters) return null
    if (startText.isEmpty()) return VerseReference(bookId, chapter)

    val verses = maxOf(structure.verseCount(bookId, chapter), BibleBooks.getVerseCount(bookId, chapter))
    val start = startText.toInt()
    val end = endText.toIntOrNull() ?: start
    if (start !in 1..verses || end !in start..verses) return null
    return VerseReference(bookId, chapter, start, end.takeIf { it != start })
}

/** `[[`, up to 120 characters on one line without another `[[` or `]]`, then `]]`. */
private val MARKER = Regex("""\[\[((?:(?!\[\[|]]).){1,120})]]""")

/** Between two markers written as one: a comma or semicolon, with any brackets around it. */
private val SEPARATOR = Regex("""[\s\]\[]*([,;])[\s\]\[]*""")

/** A book — the code or a name, which may start with 1–3 and contain spaces or dots — then
 *  optionally a chapter, `:verse` (or `.verse`) and `-verse`. Spaces are tolerated, and an en dash
 *  for the range. Three digits at most, so a long number can't overflow `toInt`. */
private val PIECE = Regex(
    """([1-3]?\s*\p{L}[\p{L} .]*?)\.?\s*(?:(\d{1,3})(?:\s*[:.]\s*(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?)?)?"""
)
