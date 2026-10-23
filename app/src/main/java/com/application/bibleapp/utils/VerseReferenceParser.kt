package com.application.bibleapp.utils

import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.VerseUI
import java.text.Normalizer

/** A passage typed into Search: a whole chapter when [startVerse] is null (#59). */
data class VerseReference(
    val bookId: Int,
    val chapter: Int,
    val startVerse: Int? = null,
    val endVerse: Int? = null
) {
    /** Whether [verse] is part of this passage. */
    fun includes(verse: Int): Boolean {
        val start = startVerse ?: return true
        val end = endVerse?.takeIf { it >= start } ?: start
        return verse in start..end
    }
}

/**
 * Reads [query] as `<book> <chapter>[:<verse>[-<verse>]]` — "John 3:16", "Jn 3:16-18", "john 3",
 * "1 Juan 4:8", "约翰福音 3:16" — or returns null so Search falls back to a word search (#59).
 *
 * The book can be the selected version's own name for it ([bookNames], by book id), the English
 * name, a common English abbreviation, or the start of any of those ("Gen", "Yoh") as long as it
 * points at only one book. Case, accents, spaces and dots don't matter.
 */
fun parseVerseReference(query: String, bookNames: Map<Int, String>): VerseReference? {
    val match = REFERENCE.matchEntire(query.trim()) ?: return null
    val (bookText, chapterText, startText, endText) = match.destructured
    val bookId = findBook(bookText, bookNames) ?: return null
    val chapter = chapterText.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return VerseReference(
        bookId = bookId,
        chapter = chapter,
        startVerse = startText.toIntOrNull()?.takeIf { it > 0 },
        endVerse = endText.toIntOrNull()?.takeIf { it > 0 }
    )
}

/**
 * The verses of [reference] from its chapter's rows, one result per verse like a word search's:
 * a verse stored as several rows is joined back together, and the legacy "¶ " marker dropped.
 */
fun List<VerseUI>.passage(reference: VerseReference): List<VerseUI> =
    filter { it.bookId == reference.bookId && it.chapter == reference.chapter }
        .filter { verse -> verse.verse?.let(reference::includes) == true }
        .groupBy { it.verse }
        .map { (_, rows) ->
            rows.first().copy(
                text = rows.joinToString(" ") { it.text.removePrefix(LEGACY_PARAGRAPH_MARKER).trim() },
                richContent = null
            )
        }
        .sortedBy { it.verse }

private const val LEGACY_PARAGRAPH_MARKER = "¶ "

// The book is everything before the chapter number, and must contain a letter, so "1 John 3"
// reads as book "1 John" and plain numbers don't parse. Verses follow a ":" or "." (and "," in
// some languages' convention), a range a hyphen or dash.
private val REFERENCE = Regex("""^(.*\p{L}.*?)\s*(\d+)(?:\s*[:.,]\s*(\d+)(?:\s*[-–—]\s*(\d+))?)?$""")

private fun findBook(bookText: String, bookNames: Map<Int, String>): Int? {
    val key = bookKey(bookText)
    if (key.isEmpty()) return null

    // The version's own names come first, so its wording wins over an English look-alike.
    val names: List<Pair<String, Int>> =
        bookNames.map { (id, name) -> bookKey(name) to id } +
            BibleBooks.allBooks.map { bookKey(it.name) to it.id } +
            ENGLISH_ABBREVIATIONS.map { (abbreviation, id) -> bookKey(abbreviation) to id }

    names.firstOrNull { it.first == key }?.let { return it.second }

    // A prefix only counts when it's long enough to mean something and names a single book.
    if (key.length < MIN_PREFIX_LENGTH) return null
    return names.filter { it.first.startsWith(key) }.map { it.second }.distinct().singleOrNull()
}

private const val MIN_PREFIX_LENGTH = 2

/** Lowercase, accents and spaces/punctuation removed: "1 Corinthians" → "1corinthians", "Génesis" → "genesis". */
private fun bookKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .lowercase()
        .replace("[^\\p{L}\\p{N}]".toRegex(), "")

/**
 * Common English abbreviations that aren't just the start of the book's name (those already
 * match as a prefix), plus "Phil", which as a prefix would also match Philemon.
 */
private val ENGLISH_ABBREVIATIONS: List<Pair<String, Int>> = listOf(
    "Gn" to 1, "Lv" to 3, "Nm" to 4, "Nb" to 4, "Dt" to 5, "Jdg" to 7, "Jg" to 7, "Jdgs" to 7,
    "Rth" to 8, "1 Sm" to 9, "2 Sm" to 10, "1 Kgs" to 11, "1 Kg" to 11, "2 Kgs" to 12, "2 Kg" to 12,
    "Pss" to 19, "Prv" to 20, "Qoh" to 21, "SoS" to 22, "Sg" to 22,
    "Song of Songs" to 22, "Jr" to 24, "Ezk" to 26, "Dn" to 27, "Jl" to 29,
    "Hb" to 35, "Zp" to 36, "Hg" to 37, "Zc" to 38,
    "Mt" to 40, "Mk" to 41, "Mrk" to 41, "Lk" to 42, "Jn" to 43, "Jhn" to 43, "Rm" to 45,
    "1 Co" to 46, "2 Co" to 47, "Phil" to 50, "Php" to 50, "Pp" to 50, "1 Th" to 52, "2 Th" to 53,
    "1 Tm" to 54, "2 Tm" to 55, "Tt" to 56, "Phlm" to 57, "Phm" to 57, "Jas" to 59, "Jm" to 59,
    "1 Pt" to 60, "2 Pt" to 61, "1 Jn" to 62, "1 Jhn" to 62, "2 Jn" to 63, "2 Jhn" to 63,
    "3 Jn" to 64, "3 Jhn" to 64, "Jd" to 65, "Rv" to 66, "Revelations" to 66
)
