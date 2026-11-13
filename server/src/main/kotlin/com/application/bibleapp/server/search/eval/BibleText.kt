package com.application.bibleapp.server.search.eval

import com.application.bibleapp.server.ai.UsfmBookCodes
import java.io.File
import java.sql.DriverManager

/** One verse's address: [book] is 1–66 (Genesis to Revelation), as in the app and [UsfmBookCodes]. */
data class VerseRef(val book: Int, val chapter: Int, val verse: Int) : Comparable<VerseRef> {
    init {
        require(book in 1..66 && chapter >= 1 && verse >= 1) { "No such verse: $book $chapter:$verse" }
    }

    override fun compareTo(other: VerseRef) = compareValuesBy(this, other, { it.book }, { it.chapter }, { it.verse })

    /** `PHP 4:6`, as `docs/semantic_search_eval.md` writes it. */
    override fun toString() = "${UsfmBookCodes.codes[book - 1]} $chapter:$verse"

    companion object {
        private val pattern = Regex("""^([1-3]?[A-Z]{2,3})\s+(\d+):(\d+)(?:-(\d+))?$""")

        /** `PHP 4:6` is one verse, `PHP 4:6-7` both. A typo is an error, never a quiet miss. */
        fun parseRange(text: String): List<VerseRef> {
            val match = requireNotNull(pattern.matchEntire(text.trim())) { "Not a reference like \"PHP 4:6\" or \"PHP 4:6-7\": $text" }
            val (code, chapter, first, last) = match.destructured
            val book = UsfmBookCodes.codes.indexOf(code) + 1
            require(book > 0) { "Unknown book code $code in \"$text\"" }
            val from = first.toInt()
            val to = last.ifEmpty { first }.toInt()
            require(to >= from) { "Backwards range: $text" }
            return (from..to).map { VerseRef(book, chapter.toInt(), it) }
        }
    }
}

data class Verse(val ref: VerseRef, val text: String)

/**
 * Where the verses to embed come from (#287). One English translation is enough: a reference
 * points to the same verse in every translation, and the app shows the results in the reader's own.
 */
object BibleText {

    /**
     * The KJV the app ships (`app/src/main/assets/bible_default.db`), so nothing is downloaded.
     * Its book ids are 1–66 in the usual order, the same as [UsfmBookCodes].
     */
    fun readKjv(database: File): List<Verse> {
        require(database.isFile) { "No Bible database at ${database.path}" }
        // The driver is only on the evaluation's classpath (build.gradle.kts), never in the server jar.
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT book_id, chapter, verse, text FROM KJV_verses ORDER BY book_id, chapter, verse").use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(Verse(VerseRef(rows.getInt(1), rows.getInt(2), rows.getInt(3)), clean(rows.getString(4))))
                        }
                    }
                }
            }
        }
    }

    /**
     * Another translation, to compare with the KJV: one verse per line, `GEN<tab>1<tab>1<tab>text`.
     * Blank lines and lines starting with `#` are skipped.
     */
    fun readTsv(file: File): List<Verse> = file.readLines().mapIndexedNotNull { index, line ->
        if (line.isBlank() || line.startsWith("#")) return@mapIndexedNotNull null
        val parts = line.split('\t', limit = 4)
        require(parts.size == 4) { "${file.name} line ${index + 1}: expected BOOK, chapter, verse and text separated by tabs" }
        val ref = VerseRef.parseRange("${parts[0].trim()} ${parts[1].trim()}:${parts[2].trim()}").single()
        Verse(ref, clean(parts[3]))
    }.also { verses ->
        val duplicates = verses.groupBy { it.ref }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "${file.name} has some verses twice: ${duplicates.take(5)}" }
    }

    /**
     * The words alone: the database marks a few added words with brackets (`[but]`, 1 John 2:23),
     * and some verses end in spaces. Empty verses are dropped by the caller (Workers AI refuses them).
     */
    fun clean(text: String): String = text.replace("[", "").replace("]", "").replace(Regex("\\s+"), " ").trim()
}
