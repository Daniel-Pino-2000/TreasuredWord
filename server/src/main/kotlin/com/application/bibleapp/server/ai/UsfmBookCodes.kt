package com.application.bibleapp.server.ai

/**
 * The 66 books' USFM codes, the only ones the Bible Helper may use in `[[JHN 3:16]]` markers.
 * The same list as the app's `data/model/UsfmBookCodes.kt` (#129), which turns the markers into
 * verse cards: keep the two in step. A code's position is its book id, 1–66.
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
}
