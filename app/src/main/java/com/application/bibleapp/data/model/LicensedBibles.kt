package com.application.bibleapp.data.model

/**
 * The English Standard Version, read through Crossway's free, non-commercial ESV API
 * (https://api.esv.org, #377). Not on the YouVersion Platform, so it has its own source
 * ([com.application.bibleapp.data.remote.EsvBibleSource]); docs/bible-licensing.md records the
 * API's conditions and how the app keeps to them.
 *
 * Unlike the YouVersion versions, only [MAX_STORED_VERSES] verses may be kept on the phone, and
 * never more than half of any book ([EsvStorage]); and Crossway bars its text from any AI system,
 * so it never appears next to AI answers ([allowsAi], #377).
 */
object EsvBible {
    const val ID = "esv-api"
    const val ABBREVIATION = "ESV"
    const val TITLE = "English Standard Version"
    const val PUBLISHER = "Crossway"
    const val URL = "https://www.esv.org"

    /** "You may not locally store more than 500 verses or one-half of any book" (api.esv.org). */
    const val MAX_STORED_VERSES = 500

    /**
     * Crossway's standard ESV notice, word for word as its permissions page gives it for digital
     * use (crossway.org/permissions, checked 2026-11-27), shown at the end of every chapter with
     * a link to esv.org ("Each page on which you use the text must include a link to www.esv.org").
     */
    const val NOTICE = "Scripture quotations are from the ESV® Bible (The Holy Bible, English Standard Version®), " +
        "© 2001 by Crossway, a publishing ministry of Good News Publishers. ESV Text Edition: 2025. The ESV text " +
        "may not be quoted in any publication made available to the public by a Creative Commons license. The " +
        "ESV may not be translated in whole or in part into any other language. Used by permission. All rights reserved."

    fun isEsv(versionId: String): Boolean = versionId == ID

    fun toTranslation(): BibleTranslation = BibleTranslation(
        id = ID,
        displayName = ABBREVIATION,
        nativeName = TITLE,
        languageName = "English",
        languageCode = "eng",
        textDirection = TextDirection.LTR,
        license = TranslationLicense(url = URL, attributionRequired = true, notice = NOTICE)
    )
}

/**
 * Every licensed Bible, whichever service it comes from: the YouVersion Platform's
 * ([YouVersionBibles], #366) and Crossway's ESV API ([EsvBible], #377). These are read a chapter
 * at a time, never downloaded whole, and credited to their publisher. Code that only cares that a
 * version is licensed, its publisher's link or whether AI features may show it asks here.
 */
object LicensedBibles {

    fun isLicensed(versionId: String): Boolean = YouVersionBibles.isYouVersion(versionId) || EsvBible.isEsv(versionId)

    /** The version's tag ("NASB 2020", "ESV"), for the server and for links; null for any other version. */
    fun abbreviation(versionId: String): String? =
        if (EsvBible.isEsv(versionId)) EsvBible.ABBREVIATION else YouVersionBibles.find(versionId)?.abbreviation

    /** The publisher's site a quoted verse's tag links to (Lockman's and Crossway's rule); null otherwise. */
    fun publisherUrl(versionId: String): String? =
        if (EsvBible.isEsv(versionId)) EsvBible.URL else YouVersionBibles.find(versionId)?.licenseUrl

    /** The notice a licensed version's text is shown with; null when it has none of its own here. */
    fun notice(versionId: String): String? =
        if (EsvBible.isEsv(versionId)) EsvBible.NOTICE else YouVersionBibles.find(versionId)?.notice

    fun languageCode(versionId: String): String? =
        if (EsvBible.isEsv(versionId)) "eng" else YouVersionBibles.find(versionId)?.languageCode

    /**
     * Whether Bible Helper and search by meaning may show [versionId]'s text next to their answers
     * (never sending it to the AI, #383). False for a version whose terms bar any AI use of its
     * text, such as the ESV (#377): those features show the bundled KJV instead. True for every
     * version that isn't licensed.
     */
    fun allowsAi(versionId: String): Boolean = when {
        EsvBible.isEsv(versionId) -> false
        else -> YouVersionBibles.find(versionId)?.allowsAi ?: true
    }
}

/**
 * Which ESV chapters may stay on the phone (#377): at most [EsvBible.MAX_STORED_VERSES] verses
 * in all, and no more than half of any one book. A chapter that is more than half of its book on
 * its own (a one-chapter book such as Jude, or Haggai 2) is shown but never saved. Pure, so it's
 * unit tested.
 */
object EsvStorage {

    /** One saved chapter and how many verses it holds. */
    data class SavedChapter(val bookId: Int, val chapter: Int, val verses: Int)

    /** Whether a chapter of [verses] may be saved at all, in a book of [bookVerses] verses. */
    fun canStore(verses: Int, bookVerses: Int): Boolean =
        verses in 1..EsvBible.MAX_STORED_VERSES && verses * 2 <= bookVerses

    /**
     * The chapters to delete from [saved] (oldest first, the newest last) so the rest keeps to the
     * limits: the newest are kept first, and an older chapter that no longer fits is dropped.
     */
    fun toEvict(saved: List<SavedChapter>, bookVerses: (bookId: Int) -> Int): List<SavedChapter> {
        var total = 0
        val perBook = mutableMapOf<Int, Int>()
        val evict = mutableListOf<SavedChapter>()
        saved.asReversed().forEach { chapter ->
            val inBook = perBook[chapter.bookId] ?: 0
            val fits = total + chapter.verses <= EsvBible.MAX_STORED_VERSES &&
                (inBook + chapter.verses) * 2 <= bookVerses(chapter.bookId)
            if (fits) {
                total += chapter.verses
                perBook[chapter.bookId] = inBook + chapter.verses
            } else {
                evict += chapter
            }
        }
        return evict
    }
}
