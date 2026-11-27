package com.application.bibleapp.data.model

/**
 * The licensed Bibles TreasuredWord reads through the YouVersion Platform (#366). Each one is
 * here only because its publisher's license was accepted in the YouVersion Platform portal;
 * docs/bible-licensing.md records which license, when, why, and what it requires. Adding a
 * version means accepting its license there first, then adding it here.
 *
 * [notice] is the copyright notice the license itself prescribes, word for word (Lockman's
 * "Free Distribution Permission Agreement", for a whole Bible or book), shown at the end of
 * every chapter with a link to [licenseUrl]. A null notice falls back to the `copyright` text
 * the API sends for the version (what YouVersion's docs require when a license names none).
 *
 * Checked against `GET /v1/bibles/{id}` on 2026-11-21.
 */
data class YouVersionBible(
    /** YouVersion's numeric Bible id. */
    val yvId: Int,
    val abbreviation: String,
    val title: String,
    /** English language name, as the version picker groups by (same as bible.helloao.org's). */
    val languageName: String,
    /** ISO 639-3, as bible.helloao.org's catalog uses. */
    val languageCode: String,
    val publisher: String,
    val licenseUrl: String,
    val notice: String?,
    /** Whether the license lets AI features show this text next to their answers (see
     *  [LicensedBibles.allowsAi]); false for one that bars any AI use of the text (#377, #379). */
    val allowsAi: Boolean = true
) {
    /** The id the rest of the app uses; the prefix keeps it apart from bible.helloao.org's ids. */
    val id: String get() = "$ID_PREFIX$yvId"

    fun toTranslation(): BibleTranslation = BibleTranslation(
        id = id,
        displayName = abbreviation,
        nativeName = title,
        languageName = languageName,
        languageCode = languageCode,
        textDirection = TextDirection.LTR,
        license = TranslationLicense(url = licenseUrl, attributionRequired = true, notice = notice)
    )

    companion object {
        const val ID_PREFIX = "yv-"
    }
}

object YouVersionBibles {

    private const val LOCKMAN_URL = "https://www.lockman.org"

    val all: List<YouVersionBible> = listOf(
        // Lockman Fast-track Bible License v1, accepted 2026-11-21.
        YouVersionBible(
            yvId = 89,
            abbreviation = "LBLA",
            title = "La Biblia de las Américas",
            languageName = "Spanish",
            languageCode = "spa",
            publisher = "The Lockman Foundation",
            licenseUrl = LOCKMAN_URL,
            notice = "La Biblia de las Américas - LBLA Copyright © 1986, 1995, 1997 by The Lockman Foundation " +
                "La Habra, California. Used by permission. All rights reserved. Derechos Reservados - All Rights " +
                "Reserved www.NuevaBiblia.com (Español) www.Lockman.org (English)"
        ),
        YouVersionBible(
            yvId = 103,
            abbreviation = "NBLA",
            title = "Nueva Biblia de las Américas",
            languageName = "Spanish",
            languageCode = "spa",
            publisher = "The Lockman Foundation",
            licenseUrl = LOCKMAN_URL,
            notice = "Nueva Biblia de las Américas - NBLA Copyright © 2005 by The Lockman Foundation La Habra, " +
                "California. Used by permission. All rights reserved. Derechos Reservados - All Rights Reserved " +
                "www.NuevaBiblia.com (Español) www.Lockman.org (English)"
        ),
        YouVersionBible(
            yvId = 2692,
            abbreviation = "NASB 2020",
            title = "New American Standard Bible 2020",
            languageName = "English",
            languageCode = "eng",
            publisher = "The Lockman Foundation",
            licenseUrl = LOCKMAN_URL,
            notice = "New American Standard Bible – NASB 2020 Copyright © 1960, 1971, 1977, 1995, 2020 by The " +
                "Lockman Foundation, La Habra, Calif. Used by permission. All rights reserved. For Permission to " +
                "Quote Information visit www.Lockman.org"
        ),
        YouVersionBible(
            yvId = 100,
            abbreviation = "NASB 1995",
            title = "New American Standard Bible 1995",
            languageName = "English",
            languageCode = "eng",
            publisher = "The Lockman Foundation",
            licenseUrl = LOCKMAN_URL,
            notice = "New American Standard Bible – NASB 1995 Copyright © 1960, 1971, 1977, 1995 by The Lockman " +
                "Foundation, La Habra, Calif. Used by permission. All rights reserved. For Permission to Quote " +
                "Information visit www.Lockman.org"
        ),
        YouVersionBible(
            yvId = 1588,
            abbreviation = "AMP",
            title = "Amplified Bible",
            languageName = "English",
            languageCode = "eng",
            publisher = "The Lockman Foundation",
            licenseUrl = LOCKMAN_URL,
            notice = "Amplified® Bible Copyright © 2015 by The Lockman Foundation, La Habra, Calif. Used by " +
                "permission. All rights reserved. For Permission to Quote Information visit www.Lockman.org"
        )
    )

    private val byId: Map<String, YouVersionBible> = all.associateBy { it.id }

    fun isYouVersion(versionId: String): Boolean = versionId.startsWith(YouVersionBible.ID_PREFIX)

    fun find(versionId: String): YouVersionBible? = byId[versionId]

    /**
     * A YouVersion version the app no longer offers, such as the EasyEnglish Bible (removed
     * before release, #381) or one whose license ended: its saved book list and chapters are
     * deleted at startup, since a license that ends leaves no right to keep the text.
     */
    fun isWithdrawn(versionId: String): Boolean = isYouVersion(versionId) && versionId !in byId
}
