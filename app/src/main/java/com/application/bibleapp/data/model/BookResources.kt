package com.application.bibleapp.data.model

/**
 * Outside resources for each book, shown in the reader's "About this book" sheet (#48, #55):
 * GotQuestions.org's book summary and BibleProject's overview videos. Link-only — their content
 * is never copied, cached or re-hosted here.
 *
 * Every entry was checked by hand on 2026-10-22: each summary URL returns its book's page, and
 * each video ID belongs to the channel it's listed under (YouTube's oEmbed author: @bibleproject
 * for English, @ProyectoBibliaOficial — BibleProject's own Spanish channel — for Spanish).
 * Re-check any entry you add or change; the unit tests don't go online.
 */
object BookResources {

    private const val GOT_QUESTIONS = "https://www.gotquestions.org/"

    /** GotQuestions.org's front page, its Spanish site for a Spanish UI. */
    fun gotQuestionsHome(spanish: Boolean): String = if (spanish) GOT_QUESTIONS + "Espanol/" else GOT_QUESTIONS

    /** BibleProject's site, where their terms ask the video credit to link. */
    fun bibleProjectHome(spanish: Boolean): String =
        if (spanish) "https://bibleproject.com/es/" else "https://bibleproject.com/"

    /** Whether [bookId] has anything for the sheet; the reader hides its info icon otherwise. */
    fun hasAny(bookId: Int): Boolean = bookId in summaryPages || bookId in englishVideos

    /** GotQuestions.org's summary of [bookId]. English only: no Spanish summary pages are checked yet. */
    fun summaryUrl(bookId: Int): String? = summaryPages[bookId]?.let { GOT_QUESTIONS + it }

    /** YouTube IDs of BibleProject's overview of [bookId], in order — some books take two parts. */
    fun overviewVideos(bookId: Int, spanish: Boolean): List<String> =
        (if (spanish) spanishVideos[bookId] else null) ?: englishVideos[bookId].orEmpty()

    /** The books [videoId] covers: 11..12 for the one 1–2 Kings video, or just [bookId]. */
    fun booksCovered(videoId: String, bookId: Int, spanish: Boolean): IntRange {
        val videos = if (spanish && bookId in spanishVideos) spanishVideos else englishVideos
        val books = videos.filterValues { videoId in it }.keys
        return if (books.isEmpty()) bookId..bookId else books.min()..books.max()
    }

    /** Most pages are Book-of-<Name>.html; Song of Solomon and the four Gospels aren't. */
    internal val summaryPages: Map<Int, String> = mapOf(
        1 to "Book-of-Genesis.html",
        2 to "Book-of-Exodus.html",
        3 to "Book-of-Leviticus.html",
        4 to "Book-of-Numbers.html",
        5 to "Book-of-Deuteronomy.html",
        6 to "Book-of-Joshua.html",
        7 to "Book-of-Judges.html",
        8 to "Book-of-Ruth.html",
        9 to "Book-of-1-Samuel.html",
        10 to "Book-of-2-Samuel.html",
        11 to "Book-of-1-Kings.html",
        12 to "Book-of-2-Kings.html",
        13 to "Book-of-1-Chronicles.html",
        14 to "Book-of-2-Chronicles.html",
        15 to "Book-of-Ezra.html",
        16 to "Book-of-Nehemiah.html",
        17 to "Book-of-Esther.html",
        18 to "Book-of-Job.html",
        19 to "Book-of-Psalms.html",
        20 to "Book-of-Proverbs.html",
        21 to "Book-of-Ecclesiastes.html",
        22 to "Song-of-Solomon.html",
        23 to "Book-of-Isaiah.html",
        24 to "Book-of-Jeremiah.html",
        25 to "Book-of-Lamentations.html",
        26 to "Book-of-Ezekiel.html",
        27 to "Book-of-Daniel.html",
        28 to "Book-of-Hosea.html",
        29 to "Book-of-Joel.html",
        30 to "Book-of-Amos.html",
        31 to "Book-of-Obadiah.html",
        32 to "Book-of-Jonah.html",
        33 to "Book-of-Micah.html",
        34 to "Book-of-Nahum.html",
        35 to "Book-of-Habakkuk.html",
        36 to "Book-of-Zephaniah.html",
        37 to "Book-of-Haggai.html",
        38 to "Book-of-Zechariah.html",
        39 to "Book-of-Malachi.html",
        40 to "Gospel-of-Matthew.html",
        41 to "Gospel-of-Mark.html",
        42 to "Gospel-of-Luke.html",
        43 to "Gospel-of-John.html",
        44 to "Book-of-Acts.html",
        45 to "Book-of-Romans.html",
        46 to "Book-of-1-Corinthians.html",
        47 to "Book-of-2-Corinthians.html",
        48 to "Book-of-Galatians.html",
        49 to "Book-of-Ephesians.html",
        50 to "Book-of-Philippians.html",
        51 to "Book-of-Colossians.html",
        52 to "Book-of-1-Thessalonians.html",
        53 to "Book-of-2-Thessalonians.html",
        54 to "Book-of-1-Timothy.html",
        55 to "Book-of-2-Timothy.html",
        56 to "Book-of-Titus.html",
        57 to "Book-of-Philemon.html",
        58 to "Book-of-Hebrews.html",
        59 to "Book-of-James.html",
        60 to "Book-of-1-Peter.html",
        61 to "Book-of-2-Peter.html",
        62 to "Book-of-1-John.html",
        63 to "Book-of-2-John.html",
        64 to "Book-of-3-John.html",
        65 to "Book-of-Jude.html",
        66 to "Book-of-Revelation.html",
    )

    /** From BibleProject's "Old Testament" and "New Testament" overview playlists. A video that
     *  covers several books (1–2 Kings, 1–2 Chronicles, Ezra–Nehemiah, 1–3 John) is listed
     *  under each of them. */
    internal val englishVideos: Map<Int, List<String>> = mapOf(
        1 to listOf("GQI72THyO5I", "F4isSyennFo"), // Genesis
        2 to listOf("jH_aojNJM3E", "oNpTha80yyE"), // Exodus
        3 to listOf("IJ-FekWUZzE"), // Leviticus
        4 to listOf("tp5MIrMZFqo"), // Numbers
        5 to listOf("q5QEH9bH8AU"), // Deuteronomy
        6 to listOf("JqOqJlFF_eU"), // Joshua
        7 to listOf("kOYy8iCfIJ4"), // Judges
        8 to listOf("0h1eoBeR4Jk"), // Ruth
        9 to listOf("QJOju5Dw0V0"), // 1 Samuel
        10 to listOf("YvoWDXNDJgs"), // 2 Samuel
        11 to listOf("bVFW3wbi9pk"), // 1 Kings
        12 to listOf("bVFW3wbi9pk"), // 2 Kings
        13 to listOf("HR7xaHv3Ias"), // 1 Chronicles
        14 to listOf("HR7xaHv3Ias"), // 2 Chronicles
        15 to listOf("MkETkRv9tG8"), // Ezra
        16 to listOf("MkETkRv9tG8"), // Nehemiah
        17 to listOf("JydNSlufRIs"), // Esther
        18 to listOf("xQwnH8th_fs"), // Job
        19 to listOf("j9phNEaPrv8"), // Psalms
        20 to listOf("AzmYV8GNAIM"), // Proverbs
        21 to listOf("lrsQ1tc-2wk"), // Ecclesiastes
        22 to listOf("4KC7xE4fgOw"), // Song of Solomon
        23 to listOf("d0A6Uchb1F8", "_TzdEPuqgQg"), // Isaiah
        24 to listOf("RSK36cHbrk0"), // Jeremiah
        25 to listOf("p8GDFPdaQZQ"), // Lamentations
        26 to listOf("R-CIPu1nko8", "SDeCWW_Bnyw"), // Ezekiel
        27 to listOf("9cSC9uobtPM"), // Daniel
        28 to listOf("kE6SZ1ogOVU"), // Hosea
        29 to listOf("zQLazbgz90c"), // Joel
        30 to listOf("mGgWaPGpGz4"), // Amos
        31 to listOf("i4ogCrEoG5s"), // Obadiah
        32 to listOf("dLIabZc0O4c"), // Jonah
        33 to listOf("MFEUEcylwLc"), // Micah
        34 to listOf("Y30DanA5EhU"), // Nahum
        35 to listOf("OPMaRqGJPUU"), // Habakkuk
        36 to listOf("oFZknKPNvz8"), // Zephaniah
        37 to listOf("juPvv_xcX-U"), // Haggai
        38 to listOf("_106IfO6Kc0"), // Zechariah
        39 to listOf("HPGShWZ4Jvk"), // Malachi
        40 to listOf("3Dv4-n6OYGI", "GGCF3OPWN14"), // Matthew
        41 to listOf("HGHqu9-DtXk"), // Mark
        42 to listOf("XIb_dCIxzr0", "26z_KhwNdD8"), // Luke
        43 to listOf("G-2e9mMf7E8", "RUfh_wOsauk"), // John
        44 to listOf("CGbNw855ksw", "Z-17KxpjL0Q"), // Acts
        45 to listOf("ej_6dVdJSIU", "0SVTl4Xa5fY"), // Romans
        46 to listOf("yiHf8klCCc4"), // 1 Corinthians
        47 to listOf("3lfPK2vfC54"), // 2 Corinthians
        48 to listOf("vmx4UjRFp0M"), // Galatians
        49 to listOf("Y71r-T98E2Q"), // Ephesians
        50 to listOf("oE9qqW1-BkU"), // Philippians
        51 to listOf("pXTXlDxQsvc"), // Colossians
        52 to listOf("No7Nq6IX23c"), // 1 Thessalonians
        53 to listOf("kbPBDKOn1cc"), // 2 Thessalonians
        54 to listOf("7RoqnGcEjcs"), // 1 Timothy
        55 to listOf("urlvnxCaL00"), // 2 Timothy
        56 to listOf("PUEYCVXJM3k"), // Titus
        57 to listOf("aW9Q3Jt6Yvk"), // Philemon
        58 to listOf("1fNWTZZwgbs"), // Hebrews
        59 to listOf("qn-hLHWwRYY"), // James
        60 to listOf("WhP7AZQlzCg"), // 1 Peter
        61 to listOf("wWLv_ITyKYc"), // 2 Peter
        62 to listOf("l3QkE6nKylM"), // 1 John
        63 to listOf("l3QkE6nKylM"), // 2 John
        64 to listOf("l3QkE6nKylM"), // 3 John
        65 to listOf("6UoCmakZmys"), // Jude
        66 to listOf("5nvVVcYD-0w", "QpnIrbq2bKo"), // Revelation
    )

    /** The same overviews from Proyecto Biblia's "Antiguo Testamento" and "Nuevo Testamento"
     *  playlists. A book missing here falls back to the English video. */
    internal val spanishVideos: Map<Int, List<String>> = mapOf(
        1 to listOf("HQMKA6w5ZZM", "MC8BqGu9B8A"), // Genesis
        2 to listOf("y9pED55wMM4", "U2lUpubEMDI"), // Exodus
        3 to listOf("vKT8eo3hAMw"), // Leviticus
        4 to listOf("dtfbSxNecMk"), // Numbers
        5 to listOf("qfMplfJoYA0"), // Deuteronomy
        6 to listOf("49ggVrdBN_Y"), // Joshua
        7 to listOf("bCwaJz4L2nU"), // Judges
        8 to listOf("f984fG4-xlE"), // Ruth
        9 to listOf("34biq4ivs5I"), // 1 Samuel
        10 to listOf("BEvicPpjQBE"), // 2 Samuel
        11 to listOf("8V-TefJJc0g"), // 1 Kings
        12 to listOf("8V-TefJJc0g"), // 2 Kings
        13 to listOf("lMj--4fDpvc"), // 1 Chronicles
        14 to listOf("lMj--4fDpvc"), // 2 Chronicles
        15 to listOf("OGdiLZ92nQs"), // Ezra
        16 to listOf("OGdiLZ92nQs"), // Nehemiah
        17 to listOf("0NzTOuBKAcI"), // Esther
        18 to listOf("A3ifTHYTmMQ"), // Job
        19 to listOf("8yieTfiw_Ew"), // Psalms
        20 to listOf("pNjt_NfIam8"), // Proverbs
        21 to listOf("X4Brf1Z8N4E"), // Ecclesiastes
        22 to listOf("JeTxGCZH99o"), // Song of Solomon
        23 to listOf("9lWr9EZchWg", "s-K5s7YpUC8"), // Isaiah
        24 to listOf("_Nf4IcDuyTc"), // Jeremiah
        25 to listOf("CWgafyPYpAU"), // Lamentations
        26 to listOf("_XqV69VU3o0", "5_PVGlLfuk8"), // Ezekiel
        27 to listOf("yBO4FjCebrI"), // Daniel
        28 to listOf("f81QQCnEOT4"), // Hosea
        29 to listOf("lcUo6mIBx1E"), // Joel
        30 to listOf("_6VyB2nOH8w"), // Amos
        31 to listOf("aEe3f0vEmVw"), // Obadiah
        32 to listOf("oozILMuVVio"), // Jonah
        33 to listOf("uivwBR1_tLs"), // Micah
        34 to listOf("bDGI0kq3vaE"), // Nahum
        35 to listOf("AJT_ipZplS4"), // Habakkuk
        36 to listOf("I9KvC0w1plE"), // Zephaniah
        37 to listOf("kPOORNkv4AA"), // Haggai
        38 to listOf("pYh4r4bNruQ"), // Zechariah
        39 to listOf("8ZMSBFH-8QM"), // Malachi
        40 to listOf("3jUPGTb80ms", "EobkNEf-ySA"), // Matthew
        41 to listOf("gsXLQkppzUc"), // Mark
        42 to listOf("bPzhlL9zKmg", "CxiSs-SHTuI"), // Luke
        43 to listOf("OU16-2Sg_CQ", "NUW1Frg8DA0"), // John
        44 to listOf("5h7Q0EK-m8I", "ZKaaHQUdVCY"), // Acts
        45 to listOf("0tco1rQGC1o", "ZqScL0dHLsM"), // Romans
        46 to listOf("6f0kym4bUmE"), // 1 Corinthians
        47 to listOf("jV7dTkYFym4"), // 2 Corinthians
        48 to listOf("kMJLgLOWb3I"), // Galatians
        49 to listOf("yJgym5MGzM4"), // Ephesians
        50 to listOf("gks8CQX5mwQ"), // Philippians
        51 to listOf("AG4jqCUapng"), // Colossians
        52 to listOf("_7N4vdUUK2w"), // 1 Thessalonians
        53 to listOf("YXBzJlvH6l4"), // 2 Thessalonians
        54 to listOf("K5xCbQcSsOE"), // 1 Timothy
        55 to listOf("lvNqjbjTytE"), // 2 Timothy
        56 to listOf("SuJmsIu_1-M"), // Titus
        57 to listOf("wNySA_IEHbM"), // Philemon
        58 to listOf("VqVAOqyoKCs"), // Hebrews
        59 to listOf("yDqK_U-ipfc"), // James
        60 to listOf("NXRkCxeO-C4"), // 1 Peter
        61 to listOf("0RJLecW7F08"), // 2 Peter
        62 to listOf("wQtMnuBzb4c"), // 1 John
        63 to listOf("wQtMnuBzb4c"), // 2 John
        64 to listOf("wQtMnuBzb4c"), // 3 John
        65 to listOf("vhPyNSJiRZ8"), // Jude
        66 to listOf("PgV57gK-Xow", "DMXA-GERRzY"), // Revelation
    )
}
