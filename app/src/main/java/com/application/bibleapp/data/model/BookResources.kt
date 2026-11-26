package com.application.bibleapp.data.model

/**
 * Outside resources for each book, shown in the reader's "About this book" sheet (#48, #55):
 * GotQuestions.org's book summary and BibleProject's overview videos, plus BibleProject's videos
 * about single passages (#407, see [passageEntries]). Link-only — their content is never copied,
 * cached or re-hosted here.
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

    /**
     * YouTube's own still for [videoId] (#365): `maxresdefault` (1280×720) for Home's Watch card,
     * `mqdefault` (320×180) for its small "More overviews" ones. Both are 16:9 with no black bars,
     * so they're shown whole and unedited. Every ID below has both sizes (checked 2026-11-21).
     */
    fun videoThumbnailUrl(videoId: String, large: Boolean): String =
        "https://i.ytimg.com/vi/$videoId/${if (large) "maxresdefault" else "mqdefault"}.jpg"

    /**
     * Up to [count] books after [bookId] with an overview video, each with its first video, for
     * Home's "More overviews" row (#365). Wraps from Revelation to Genesis, and skips a video
     * already listed or covering [bookId] too, so 1 Kings is followed by 1 Chronicles, not 2 Kings.
     */
    fun nextOverviews(bookId: Int, spanish: Boolean, count: Int = 3): List<Pair<Int, String>> {
        val seen = overviewVideos(bookId, spanish).toMutableSet()
        val result = mutableListOf<Pair<Int, String>>()
        for (step in 1 until BOOK_COUNT) {
            val book = (bookId - 1 + step) % BOOK_COUNT + 1
            val video = overviewVideos(book, spanish).firstOrNull() ?: continue
            if (!seen.add(video)) continue
            result += book to video
            if (result.size == count) break
        }
        return result
    }

    private const val BOOK_COUNT = 66

    /** The books [videoId] covers: 11..12 for the one 1–2 Kings video, or just [bookId]. */
    fun booksCovered(videoId: String, bookId: Int, spanish: Boolean): IntRange {
        val videos = if (spanish && bookId in spanishVideos) spanishVideos else englishVideos
        val books = videos.filterValues { videoId in it }.keys
        return if (books.isEmpty()) bookId..bookId else books.min()..books.max()
    }

    /**
     * BibleProject's videos about [chapter] of [bookId] itself rather than the whole book (#407):
     * ones about a run of chapters or the whole chapter first, then ones about some of its
     * verses, in verse order. Usually none or one; Exodus 20 and Matthew 5–7 have many. Spanish
     * uses Proyecto Biblia's version, or the English video if there's none, as [overviewVideos]
     * does.
     */
    fun passageVideos(bookId: Int, chapter: Int, spanish: Boolean): List<PassageVideo> =
        passageVideos(passageEntries, bookId, chapter, spanish)

    internal fun passageVideos(
        entries: List<PassageEntry>,
        bookId: Int,
        chapter: Int,
        spanish: Boolean
    ): List<PassageVideo> = entries
        .filter { it.bookId == bookId && chapter in it.chapters }
        // Stable, so a series about the same verses keeps its episode order.
        .sortedWith(compareBy({ it.verses != null }, { it.chapters.first }, { it.verses?.first }, { it.verses?.last }))
        .map { PassageVideo(it.bookId, it.chapters, it.verses, (if (spanish) it.spanish else null) ?: it.english) }

    /** One video in [passageEntries]: the chapters it's about, and the [verses] when it's about
     *  part of one chapter; its YouTube ID on BibleProject's channel and, where there is one,
     *  Proyecto Biblia's. */
    internal data class PassageEntry(
        val bookId: Int,
        val chapters: IntRange,
        val verses: IntRange?,
        val english: String,
        val spanish: String?
    )

    /**
     * BibleProject videos whose subject is one passage (#407), from the video pages listed in
     * bibleproject.com's sitemap. The passage is the one BibleProject names on the video's page
     * or on YouTube; where they name only the chapter, the verses are what the video is about
     * there (the Beatitudes are Matthew 5:3–12, the 6th Commandment Exodus 20:13).
     *
     * Left out: theme and word-study videos that quote a chapter along the way (they'd show on
     * dozens of chapters); the Sermon on the Mount episodes whose page names no passage (1, 3,
     * 10); BibleProject's short commentaries that aren't on YouTube; and a second Lord's Prayer
     * video for the same verses as episode 7.
     *
     * YouTube IDs were matched to each page by topic, runtime and episode number, and every one
     * checked 2026-11-25 with oEmbed (author @bibleproject, or @ProyectoBibliaOficial for
     * Spanish); each has both thumbnail sizes. A null Spanish ID means Proyecto Biblia hasn't
     * published that video.
     */
    internal val passageEntries: List<PassageEntry> = listOf(
        // Creation series' visual commentary, "Genesis 1".
        PassageEntry(1, 1..1, null, "afVN-7vY0KA", "z4A52O-41aI"),
        // The 10 Commandments series ("given to Moses at Mount Sinai"; the 1st names Exodus 20:2),
        // then one verse range per commandment.
        PassageEntry(2, 20..20, 1..17, "4M9BsOvx6cs", "rH_Zgi3FJv8"), // The 10 Commandments as wisdom
        PassageEntry(2, 20..20, 2..3, "JGI8nNVkZpA", "_-FJ0bjRApY"),
        PassageEntry(2, 20..20, 4..6, "vXHDUs28rPM", "tchcdZAhqug"),
        PassageEntry(2, 20..20, 7..7, "elhazm4fZeE", "QRu9Kj7tH2U"),
        PassageEntry(2, 20..20, 8..11, "VsAmFJ6quZk", "mT_hQM4D5ZA"),
        PassageEntry(2, 20..20, 12..12, "Q7PgVAN2MPo", "445--Xj6DRc"),
        PassageEntry(2, 20..20, 13..13, "uAQ5KaEd98Q", "0ycWEE9T_WY"),
        PassageEntry(2, 20..20, 14..14, "Pyk64lwOLpw", "HJUrEJKlq1c"),
        PassageEntry(2, 20..20, 15..15, "WylKQKFI_4M", "ypwfpkgOxlw"),
        PassageEntry(2, 20..20, 16..16, "SSBRI49NYmY", "5Yj3Ug951bA"),
        PassageEntry(2, 20..20, 17..17, "185KpeCc-CY", "y8zkufr8iMQ"),
        // Character of God series, all on "Exodus 34:6-7", in episode order: the visual
        // commentary, then compassion, grace, slow to anger, loyal love, faithful.
        PassageEntry(2, 34..34, 6..7, "nxwzq1PJImM", "oHMicgEfIrY"),
        PassageEntry(2, 34..34, 6..7, "qJEtyAiAQik", "55Jp07kZLtI"),
        PassageEntry(2, 34..34, 6..7, "ABPVVw_aw44", "CZtgCMtuoJo"),
        PassageEntry(2, 34..34, 6..7, "TeQ1nq_YJD0", "KPNc-0mI0TM"),
        PassageEntry(2, 34..34, 6..7, "UfbyFLgs_NM", "GBoth3FRATU"),
        PassageEntry(2, 34..34, 6..7, "HCLuq_5o7_o", "7rvNyFm1bpA"),
        // Shema word studies, one per word of the prayer in Deuteronomy 6:4–5, in its order:
        // listen, YHWH, love, heart, soul, strength (their "final installment").
        PassageEntry(5, 6..6, 4..5, "6KQLOuIKaRA", "pkGvJWE_sDk"),
        PassageEntry(5, 6..6, 4..5, "eLrGM26pmM0", "7Ajug_ZAfW4"),
        PassageEntry(5, 6..6, 4..5, "HV_LUs2lnIQ", "nEAYHpSx78M"),
        PassageEntry(5, 6..6, 4..5, "aS4iM6KpPYo", "wiSZUO1TKmQ"),
        PassageEntry(5, 6..6, 4..5, "g_igCcWAMAM", "WJtfCFyvYr8"),
        PassageEntry(5, 6..6, 4..5, "9aaVy1AmFX4", "BUChIW2Jj04"),
        // Visual commentaries: "Psalm 1", the Creation series' "Psalm 8", "Psalm 148" and
        // "Proverbs 8", and "Isaiah 61".
        PassageEntry(19, 1..1, null, "E7k01kfBx6Y", "JKssLyqHhYA"),
        PassageEntry(19, 8..8, null, "d_-xvaK4wIw", "wtrUhJIX0Fc"),
        PassageEntry(19, 148..148, null, "XgCrFl4Mc5Q", "LAhwgVeizHg"),
        PassageEntry(20, 8..8, null, "k8P-x34iYRE", "xOtwUgUil1I"),
        PassageEntry(23, 61..61, null, "d_Q6WkD_Pas", "o--UeqSsJu4"),
        // Sermon on the Mount: the visual commentary on "Matthew 5-7" and on 5:3-16 and 5:17-20,
        // and the video series' episodes 2 and 4–9 (Spanish has no 6, 8 or 9).
        PassageEntry(40, 5..7, null, "ajwehw_AT0s", "1wRqEhIMaAw"),
        PassageEntry(40, 5..5, 3..16, "W9fR7sHw9Y8", "RJiC3T_Lesc"),
        PassageEntry(40, 5..5, 17..20, "Bpk-sI4MY58", "YOray6bUXDs"),
        PassageEntry(40, 5..5, 3..12, "s9246LGlngs", "E4wSe7ejBEY"), // 2: the Beatitudes
        PassageEntry(40, 5..5, 21..32, "okFibMvn3t0", "ha1Masb-Tnc"), // 4: murder, adultery, divorce
        PassageEntry(40, 5..5, 33..48, "3EkD-alQhT8", "wsPLzDXGjvg"), // 5: oaths, revenge, enemies
        PassageEntry(40, 6..6, 1..8, "wCo2LN7E6bo", null), // 6: "Matthew 6:1-8"
        PassageEntry(40, 6..6, 9..13, "3-YlqQfKkKk", "GNgZ9v_NqvY"), // 7: the Lord's Prayer
        PassageEntry(40, 6..6, 19..34, "GpqOdHV3dmU", null), // 8: wealth and worry
        PassageEntry(40, 7..7, 1..12, "PqEiqCuIsvw", null), // 9: "Matthew 7:1-12"
        // Luke–Acts series: their own chapter ranges, which meet on Luke 9 and 19.
        PassageEntry(42, 1..2, null, "_OLezoUvOEQ", "DTL5QW6Ez7s"), // The Birth of Jesus
        PassageEntry(42, 3..9, null, "0k4GbvZUPuo", "OfZRqfWs1lg"), // The Baptism of Jesus
        PassageEntry(42, 9..19, null, "jUCCUHurV0I", "sm8-GQl9P6w"), // The Prodigal Son
        PassageEntry(42, 19..23, null, "_unHmAf7INk", "KBE2nLYyCdc"), // The Crucifixion of Jesus
        PassageEntry(42, 24..24, null, "Vb24Lk1Oh5M", "WHlHoQy3bVk"), // The Resurrection of Jesus
        // Creation series: "John 1".
        PassageEntry(43, 1..1, null, "XgslCbXOOIE", "rkSRDC49Dtc"),
        PassageEntry(44, 1..7, null, "JQhkWmFJKnA", "OisTpQEle6E"), // Pentecost
        PassageEntry(44, 8..12, null, "oiVAbkINtRU", "sEhT1mA-HU0"), // The Apostle Paul
        PassageEntry(44, 13..20, null, "fglsbcGSr3A", "v0t5OzcBgPY"), // Paul's Missionary Journeys
        PassageEntry(44, 21..28, null, "FJsiwOB0Pvg", "jWG-433qdWM"), // Bound for Rome
    )

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

/** A BibleProject video about [chapters] of [bookId], or [verses] of its one chapter (#407),
 *  from [BookResources.passageVideos]. */
data class PassageVideo(val bookId: Int, val chapters: IntRange, val verses: IntRange?, val videoId: String) {
    /** The passage as the app writes references elsewhere (`utils/VerseRefFormat.kt`): chapters
     *  with an en dash, "Luke 1–2", "Psalms 8"; verses with a hyphen, "Exodus 20:8-11", "Exodus 20:7". */
    fun reference(bookName: String): String = when {
        verses != null && verses.first == verses.last -> "$bookName ${chapters.first}:${verses.first}"
        verses != null -> "$bookName ${chapters.first}:${verses.first}-${verses.last}"
        chapters.first == chapters.last -> "$bookName ${chapters.first}"
        else -> "$bookName ${chapters.first}–${chapters.last}"
    }
}
