package com.application.bibleapp.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class TextDirection { LTR, RTL }

/**
 * A downloadable Bible translation, independent of whichever remote API supplied it.
 * The version picker and download flow depend on this, not on a source-specific DTO,
 * so swapping the backing API doesn't ripple into UI/ViewModel code.
 *
 * [Serializable] so [BibleRepository][com.application.bibleapp.data.repository.BibleRepository]
 * can cache the catalog response to disk between app launches, not because this crosses
 * the network itself — [com.application.bibleapp.data.remote.HelloAoTranslationDto] is
 * what's actually deserialized from the API.
 */
@Serializable
data class BibleTranslation(
    val id: String,
    val displayName: String,
    val nativeName: String,
    val languageName: String,
    val languageCode: String,
    val textDirection: TextDirection,
    val numberOfBooks: Int = FULL_CANON_BOOK_COUNT,
    // Null for the bundled KJV and for entries cached before licenses were kept (#98).
    val license: TranslationLicense? = null
) {
    /** True for a standard 66-book (Protestant canon) Bible — the only kind this app supports reading. */
    val isCompleteCanon: Boolean get() = numberOfBooks == FULL_CANON_BOOK_COUNT

    companion object {
        const val FULL_CANON_BOOK_COUNT = 66
    }
}

/**
 * How a translation may be used, as bible.helloao.org's catalog states it (#98). Most of its Bibles
 * (146 of 207 complete ones on 2026-10-26) require a credit, which the reader shows at the end of
 * every chapter. 49 forbid commercial use: fine while the app is free with no ads or purchases,
 * but those Bibles would have to go before anything paid is added.
 */
@Serializable
data class TranslationLicense(
    /** The license page (eBible.org's page for the translation, usually); always https. */
    val url: String,
    val attributionRequired: Boolean = true,
    val publicDomain: Boolean = false,
    val commercialUseAllowed: Boolean = false,
    /** Wording the license asks to be shown with the text (only the NET Bible has one so far). */
    val notice: String? = null
)

/**
 * The KJV that ships inside the app ([DEFAULT_VERSION]). The remote catalog never lists it, so
 * the version picker adds it itself; without it there was no way back to the bundled Bible once
 * you'd switched away (#56). Always available offline, and never deletable.
 */
val BUNDLED_TRANSLATION = BibleTranslation(
    id = DEFAULT_VERSION.id,
    displayName = "KJV",
    nativeName = "King James Version",
    languageName = "English",
    languageCode = "eng",
    textDirection = TextDirection.LTR
)
