package com.application.bibleapp.data.model

import java.util.Calendar

/**
 * A book/chapter/verse reference, resolved against whatever translation is active.
 * [endVerse] is set when the source gave a range (e.g. "John 3:20-21"); null for a
 * single verse.
 */
data class DailyVerseRef(
    val bookId: Int,
    val chapter: Int,
    val startVerse: Int,
    val endVerse: Int? = null
)

/**
 * A small curated set of well-known verses, picked deterministically by day-of-year
 * so the Home screen shows the same verse all day and a different one tomorrow.
 * Used as a fallback when the remote daily-verse API is unreachable or returns a
 * reference this app can't resolve — see
 * [com.application.bibleapp.viewmodel.BibleViewModel.loadVerseOfTheDay].
 * Uses [Calendar] rather than java.time — this app's minSdk (24) predates java.time
 * without core library desugaring.
 */
object VerseOfTheDay {
    private val refs = listOf(
        DailyVerseRef(bookId = 43, chapter = 3, startVerse = 16),  // John 3:16
        DailyVerseRef(bookId = 19, chapter = 23, startVerse = 1),  // Psalm 23:1
        DailyVerseRef(bookId = 20, chapter = 3, startVerse = 5),   // Proverbs 3:5
        DailyVerseRef(bookId = 45, chapter = 8, startVerse = 28),  // Romans 8:28
        DailyVerseRef(bookId = 50, chapter = 4, startVerse = 13),  // Philippians 4:13
        DailyVerseRef(bookId = 6, chapter = 1, startVerse = 9),    // Joshua 1:9
        DailyVerseRef(bookId = 23, chapter = 41, startVerse = 10), // Isaiah 41:10
    )

    fun forToday(): DailyVerseRef {
        val dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        return refs[dayOfYear % refs.size]
    }
}

/** One verse of the daily verse — its number and its text. */
data class DailyVerseLine(val number: Int, val text: String)

/**
 * What the Home screen actually renders — a display-ready reference string and the verses in
 * order. [hasVerseNumbers] is true for a range, where each verse's number is shown before its
 * text so you can tell where one ends and the next begins; a single verse shows no number.
 */
data class DailyVerseUI(val reference: String, val verses: List<DailyVerseLine>) {
    val hasVerseNumbers: Boolean get() = verses.size > 1

    /** Plain text for places that can't style spans (the notification): verse numbers as
     *  Unicode superscripts when [hasVerseNumbers], e.g. "²⁰ For every one… ²¹ But he…". */
    val text: String
        get() = verses.joinToString(" ") { line ->
            if (hasVerseNumbers) "${toSuperscript(line.number)} ${line.text}" else line.text
        }
}

private const val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"

internal fun toSuperscript(number: Int): String =
    number.toString().map { SUPERSCRIPT_DIGITS[it - '0'] }.joinToString("")

/**
 * Resolves [ref] against [chapterVerses] (a whole chapter already loaded for the
 * currently selected translation): for a single verse, that verse alone; for a range
 * ([DailyVerseRef.endVerse] set), every verse from [DailyVerseRef.startVerse] to
 * [DailyVerseRef.endVerse] in order. Returns null if the chapter doesn't
 * contain [DailyVerseRef.startVerse] at all — e.g. the currently selected translation
 * doesn't include this book/chapter — so the caller can fall back to another reference.
 *
 * A pure function (no I/O) so the range logic can be unit tested without
 * a database or network call.
 */
fun resolveDailyVerseUI(bookName: String, ref: DailyVerseRef, chapterVerses: List<VerseUI>): DailyVerseUI? {
    val endVerse = ref.endVerse ?: ref.startVerse
    val versesInRange = chapterVerses
        .filter { it.verse != null && it.verse in ref.startVerse..endVerse }
        .sortedBy { it.verse }
    if (versesInRange.none { it.verse == ref.startVerse }) return null

    val reference = if (ref.endVerse != null && ref.endVerse != ref.startVerse) {
        "$bookName ${ref.chapter}:${ref.startVerse}-${ref.endVerse}"
    } else {
        "$bookName ${ref.chapter}:${ref.startVerse}"
    }
    // Legacy (bundled KJV) verses can open with a paragraph marker — meaningless out of context.
    val lines = versesInRange.map { DailyVerseLine(number = it.verse!!, text = it.text.removePrefix("¶ ")) }
    return DailyVerseUI(reference = reference, verses = lines)
}
