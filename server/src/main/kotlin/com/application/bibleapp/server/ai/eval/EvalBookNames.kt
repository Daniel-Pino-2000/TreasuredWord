package com.application.bibleapp.server.ai.eval

import com.application.bibleapp.server.ai.UsfmBookCodes
import java.text.Normalizer

/**
 * Finds the book a marker names the way the app does (#207): the USFM code first, then an English
 * name or abbreviation, then a prefix long enough to name a single book. A copy of the app's
 * `AiAnswerParser.kt` and `findBook` in `VerseReferenceParser.kt` (#190), which turn markers into
 * verse cards: keep the two in step, so the evaluation flags only what the app can't show.
 */
internal object EvalBookNames {

    private const val MIN_PREFIX_LENGTH = 2

    /** The app's `BibleBooks` names, in book id order 1–66. */
    private val names = listOf(
        "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy", "Joshua", "Judges", "Ruth",
        "1 Samuel", "2 Samuel", "1 Kings", "2 Kings", "1 Chronicles", "2 Chronicles", "Ezra",
        "Nehemiah", "Esther", "Job", "Psalms", "Proverbs", "Ecclesiastes", "Song of Solomon",
        "Isaiah", "Jeremiah", "Lamentations", "Ezekiel", "Daniel", "Hosea", "Joel", "Amos",
        "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk", "Zephaniah", "Haggai", "Zechariah",
        "Malachi",
        "Matthew", "Mark", "Luke", "John", "Acts", "Romans", "1 Corinthians", "2 Corinthians",
        "Galatians", "Ephesians", "Philippians", "Colossians", "1 Thessalonians",
        "2 Thessalonians", "1 Timothy", "2 Timothy", "Titus", "Philemon", "Hebrews", "James",
        "1 Peter", "2 Peter", "1 John", "2 John", "3 John", "Jude", "Revelation"
    )

    /** The app's `ENGLISH_ABBREVIATIONS`: the ones that aren't just the start of a name. */
    private val abbreviations: List<Pair<String, Int>> = listOf(
        "Gn" to 1, "Lv" to 3, "Nm" to 4, "Nb" to 4, "Dt" to 5, "Jdg" to 7, "Jg" to 7, "Jdgs" to 7,
        "Rth" to 8, "1 Sm" to 9, "2 Sm" to 10, "1 Kgs" to 11, "1 Kg" to 11, "2 Kgs" to 12, "2 Kg" to 12,
        "Pss" to 19, "Prv" to 20, "Qoh" to 21, "SoS" to 22, "Sg" to 22,
        "Song of Songs" to 22, "Jr" to 24, "Ezk" to 26, "Dn" to 27, "Jl" to 29,
        "Hb" to 35, "Zp" to 36, "Hg" to 37, "Zc" to 38,
        "Mt" to 40, "Mk" to 41, "Mrk" to 41, "Lk" to 42, "Jn" to 43, "Jhn" to 43, "Rm" to 45,
        "1 Co" to 46, "2 Co" to 47, "Phil" to 50, "Phi" to 50, "Php" to 50, "Pp" to 50, "1 Th" to 52, "2 Th" to 53,
        "1 Tm" to 54, "2 Tm" to 55, "Tt" to 56, "Phlm" to 57, "Phm" to 57, "Jas" to 59, "Jm" to 59,
        "1 Pt" to 60, "2 Pt" to 61, "1 Jn" to 62, "1 Jhn" to 62, "2 Jn" to 63, "2 Jhn" to 63,
        "3 Jn" to 64, "3 Jhn" to 64, "Jd" to 65, "Rv" to 66, "Revelations" to 66
    )

    private val keys: List<Pair<String, Int>> =
        names.mapIndexed { index, name -> key(name) to index + 1 } +
            abbreviations.map { (abbreviation, id) -> key(abbreviation) to id }

    /** The book id 1–66 [text] names, or null when the app wouldn't recognize it. */
    fun find(text: String): Int? {
        val code = UsfmBookCodes.codes.indexOf(text.replace(" ", "").uppercase())
        if (code >= 0) return code + 1

        val key = key(text)
        if (key.isEmpty()) return null
        keys.firstOrNull { it.first == key }?.let { return it.second }
        if (key.length < MIN_PREFIX_LENGTH) return null
        return keys.filter { it.first.startsWith(key) }.map { it.second }.distinct().singleOrNull()
    }

    /** Lowercase, accents and spaces/punctuation removed: "1 Corinthians" → "1corinthians". */
    private fun key(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase()
            .replace("[^\\p{L}\\p{N}]".toRegex(), "")
}
