package com.application.bibleapp.server.ai.eval

import com.application.bibleapp.server.ai.TopicGuide
import com.application.bibleapp.server.ai.UsfmBookCodes
import kotlin.math.roundToInt

/**
 * A passage of one book (id 1–66), from a chapter and verse to a chapter and verse. A missing
 * verse means the whole chapter, so "1 Corinthians 12–14" is chapters 12 to 14.
 */
data class Passage(
    val book: Int,
    val startChapter: Int,
    val startVerse: Int?,
    val endChapter: Int = startChapter,
    val endVerse: Int? = startVerse
) {
    private val start: Int get() = startChapter * 1000 + (startVerse ?: 0)
    private val end: Int get() = endChapter * 1000 + (endVerse ?: 999)

    /** Chapters and verses start at 1, and the passage doesn't run backwards. */
    val isValid: Boolean get() = startChapter >= 1 && (startVerse ?: 1) >= 1 && start <= end

    /** The same book, and some verse in common; a whole chapter has every verse of it in common. */
    fun overlaps(other: Passage): Boolean = book == other.book && start <= other.end && other.start <= end

    /** As in a marker: "JHN 3:16-18", "PSA 23", "1CO 12-14". */
    override fun toString(): String {
        val from = if (startVerse == null) "$startChapter" else "$startChapter:$startVerse"
        val to = when {
            endChapter == startChapter && endVerse == startVerse -> null
            endChapter == startChapter -> "$endVerse"
            endVerse == null -> "$endChapter"
            else -> "$endChapter:$endVerse"
        }
        return "${UsfmBookCodes.codes[book - 1]} $from${to?.let { "-$it" } ?: ""}"
    }
}

/** One answer's grading-free numbers (#321). */
data class CitationCount(
    /** The passages the question's Must line names. */
    val named: List<Passage>,
    /** Those of [named] the answer cites. */
    val namedCited: List<Passage>,
    /** Every passage the answer cites, once each. */
    val cited: List<Passage>,
    /** Those of [cited] that are in the question's guides; null when the run sends no guides. */
    val fromGuide: List<Passage>?,
    /**
     * Those of [cited] neither on the Must line nor in a guide: they come from the model's memory,
     * so a person checks each one makes the point it's cited for (the audit's section 1.3).
     */
    val toCheck: List<Passage>
)

/**
 * The evaluation's grading-free numbers (#321): which passages an answer cites, measured the same
 * way in every run, so a change in them is a change in the answers, not in who graded them
 * (`docs/ai_quality_audit.md`, section 1.1). A passage counts as cited when a marker names the
 * same book and chapter with a verse in common, or the whole chapter: the audit's `must_refs.py`.
 */
object EvalCitations {

    // An English name and chapter, as the Must lines write them: "Psalm 34:18", "Acts 8:36-38",
    // "1 Corinthians 12–14" (chapters), "Song of Solomon 2:4".
    private val named = Regex(
        """(?<![\p{L}\d])((?:[1-3] )?\p{Lu}\p{Ll}+(?: of \p{Lu}\p{Ll}+)?) (\d{1,3})(?::(\d{1,3}))?(?:\s*[-–]\s*(\d{1,3})(?::(\d{1,3}))?)?"""
    )
    // A chapter and verse of the same book right after one: "1 Timothy 2:12 and 3:1-7".
    private val sameBook = Regex("""\s*(?:,\s*(?:and\s+|or\s+)?|and\s+|or\s+)(\d{1,3}):(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?""")

    /**
     * The passages [must] names, once each: in English ("Romans 6:1-2", "1 Timothy 2:12 and
     * 3:1-7") and as markers (`[[PSA 23:1-6]]`). Only full book names and the app's abbreviations
     * count, so a word that only starts like a book ("Is 1") is never read as one.
     */
    fun namedOn(must: String): List<Passage> {
        val found = mutableListOf<Passage>()
        var index = 0
        while (true) {
            val match = named.find(must, index) ?: break
            index = match.range.last + 1
            val book = EvalBookNames.exact(match.groupValues[1]) ?: continue
            val (_, chapterText, verseText, toText, toVerseText) = match.destructured
            val chapter = chapterText.toInt()
            val verse = verseText.toIntOrNull()
            val to = toText.toIntOrNull()
            val toVerse = toVerseText.toIntOrNull()
            found += when {
                to == null -> Passage(book, chapter, verse)
                toVerse != null -> Passage(book, chapter, verse, to, toVerse) // "Matthew 5:1-7:29"
                verse != null -> Passage(book, chapter, verse, chapter, to) // "Romans 6:1-2"
                else -> Passage(book, chapter, null, to, null) // "1 Corinthians 12–14"
            }
            while (true) {
                val next = sameBook.find(must, index)?.takeIf { it.range.first == index } ?: break
                val (nextChapter, nextVerse, nextTo) = next.destructured
                found += Passage(book, nextChapter.toInt(), nextVerse.toInt(), nextChapter.toInt(), nextTo.toIntOrNull() ?: nextVerse.toInt())
                index = next.range.last + 1
            }
        }
        found += EvalChecks.passagesIn(must)
        return found.filter { it.isValid }.distinct()
    }

    /** [answer]'s numbers against [must] and, in a guided run, the [guides] sent with it. */
    fun count(must: String?, answer: String, guides: List<TopicGuide>?): CitationCount {
        val named = must?.let(::namedOn).orEmpty()
        val cited = EvalChecks.passagesIn(answer).distinct()
        val guidePassages = guides?.flatMap { guide -> guide.passages.flatMap { (reference, _) -> EvalChecks.passagesIn("[[$reference]]") } }
        return CitationCount(
            named = named,
            namedCited = named.filter { passage -> cited.any { it.overlaps(passage) } },
            cited = cited,
            fromGuide = guidePassages?.let { pool -> cited.filter { passage -> pool.any { it.overlaps(passage) } } },
            toCheck = cited.filter { passage ->
                named.none { it.overlaps(passage) } && guidePassages.orEmpty().none { it.overlaps(passage) }
            }
        )
    }

    /** "41 of 65 (63%)", or "0 of 0". */
    fun share(part: Int, whole: Int): String =
        if (whole == 0) "0 of 0" else "$part of $whole (${(100.0 * part / whole).roundToInt()}%)"
}
