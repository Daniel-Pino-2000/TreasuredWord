package com.application.bibleapp.server.ai

import com.application.bibleapp.server.db.dbQuery
import com.application.bibleapp.server.search.VerseSearch
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection

/**
 * The public-domain BSB text of a passage "Ask about this verse" sent without its text (#383): the
 * reader's version is a licensed one (LBLA, NASB…), whose text may never go to the AI
 * (docs/bible-licensing.md), so the app sends only the reference and the server fills it in from
 * the BSB that search by meaning already keeps (`search_verses`, [VerseSearch.TRANSLATION]).
 */
object PassageText {

    /** A passage reference the contract allows ("JHN 3", "LUK 12:4,6-7"): the book number (1–66),
     *  the chapter, and its verses, or null for the whole chapter. */
    data class Parsed(val book: Int, val chapter: Int, val verses: Set<Int>?)

    /** [reference] split up, or null if it isn't one; validateAsk has already checked its shape. */
    fun parse(reference: String): Parsed? {
        val code = reference.substringBefore(' ')
        val book = UsfmBookCodes.codes.indexOf(code) + 1
        if (book == 0) return null
        val rest = reference.substringAfter(' ', "")
        val chapter = rest.substringBefore(':').toIntOrNull() ?: return null
        if (':' !in rest) return Parsed(book, chapter, null)
        val verses = rest.substringAfter(':').split(',').flatMap { run ->
            val start = run.substringBefore('-').toIntOrNull() ?: return null
            val end = run.substringAfter('-', run).toIntOrNull() ?: return null
            if (end < start) return null
            (start..end).toList()
        }
        return Parsed(book, chapter, verses.toSet())
    }

    /**
     * [verses] as the app sends a passage's text: one verse plain, several with their numbers
     * inline ("16 For God… 17 For God did not…"), cut at a word to fit [maxChars].
     */
    fun join(verses: List<Pair<Int, String>>, maxChars: Int): String {
        val text = if (verses.size == 1) verses.single().second else verses.joinToString(" ") { (number, text) -> "$number $text" }
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars - 1)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace >= maxChars / 2) cut.take(lastSpace) else cut).trimEnd() + "…"
    }

    /** [reference]'s BSB text, at most [maxChars] characters, or null when the BSB has none of it. */
    suspend fun bsb(reference: String, maxChars: Int): String? {
        val parsed = parse(reference) ?: return null
        val rows = dbQuery {
            versesOf(TransactionManager.current().connection.connection as Connection, VerseSearch.TRANSLATION, parsed.book, parsed.chapter)
        }
        val wanted = rows.filter { (number, text) -> text.isNotBlank() && (parsed.verses == null || number in parsed.verses) }
        return wanted.takeIf { it.isNotEmpty() }?.let { join(it, maxChars) }
    }

    private fun versesOf(connection: Connection, translation: String, book: Int, chapter: Int): List<Pair<Int, String>> =
        connection.prepareStatement(
            "SELECT verse, text FROM search_verses WHERE translation = ? AND book = ? AND chapter = ? ORDER BY verse"
        ).use { statement ->
            statement.setString(1, translation)
            statement.setInt(2, book)
            statement.setInt(3, chapter)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getInt(1) to rows.getString(2).trim()) }
            }
        }
}
