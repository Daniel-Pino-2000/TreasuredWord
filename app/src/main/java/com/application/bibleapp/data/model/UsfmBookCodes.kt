package com.application.bibleapp.data.model

import com.application.bibleapp.utils.VerseReference

/**
 * The 66 books' USFM codes ("GEN"…"REV"), the standard three-character IDs Bible software uses.
 * The Bible Helper's answers name verses with them, as `[[JHN 3:16]]` (#129), so a book never
 * depends on the answer's language. A code's position is its book id: [BibleBooks]' 1–66.
 */
object UsfmBookCodes {

    val codes: List<String> = listOf(
        // Old Testament (1–39)
        "GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA",
        "1KI", "2KI", "1CH", "2CH", "EZR", "NEH", "EST", "JOB", "PSA", "PRO",
        "ECC", "SNG", "ISA", "JER", "LAM", "EZK", "DAN", "HOS", "JOL", "AMO",
        "OBA", "JON", "MIC", "NAM", "HAB", "ZEP", "HAG", "ZEC", "MAL",
        // New Testament (40–66)
        "MAT", "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO", "GAL", "EPH",
        "PHP", "COL", "1TH", "2TH", "1TI", "2TI", "TIT", "PHM", "HEB", "JAS",
        "1PE", "2PE", "1JN", "2JN", "3JN", "JUD", "REV"
    )

    private val idsByCode: Map<String, Int> = codes.withIndex().associate { (index, code) -> code to index + 1 }

    /** The book id for [code], in any case ("jhn" too), or null for a code that isn't one of the 66. */
    fun bookId(code: String): Int? = idsByCode[code.uppercase()]

    /** The code for [bookId] 1–66, or null. */
    fun code(bookId: Int): String? = codes.getOrNull(bookId - 1)

    private val REFERENCE = Regex("""^([1-3]?[A-Za-z]{2,3}) (\d+)(?::(\d+)(?:-(\d+))?)?$""")

    /**
     * A reference our server sends, such as search's key passages (#320): `MAT 11:28-30`,
     * `PSA 147:3` or a whole chapter, `PSA 73`. Null for anything else: an unknown code, a zero,
     * a range that ends before it starts. Whether the verses exist is up to the Bible that shows them.
     */
    fun parseReference(text: String): VerseReference? {
        val match = REFERENCE.matchEntire(text.trim()) ?: return null
        val (codeText, chapterText, startText, endText) = match.destructured
        val bookId = bookId(codeText) ?: return null
        val chapter = chapterText.toIntOrNull()?.takeIf { it > 0 } ?: return null
        if (startText.isEmpty()) return VerseReference(bookId, chapter)
        val start = startText.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val end = if (endText.isEmpty()) null else endText.toIntOrNull()?.takeIf { it >= start } ?: return null
        return VerseReference(bookId, chapter, start, end)
    }
}
