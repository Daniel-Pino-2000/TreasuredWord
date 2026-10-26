package com.application.bibleapp.data.remote

import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.TextDirection
import com.application.bibleapp.data.model.TranslationLicense
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DTOs for the Free Use Bible API (bible.helloao.org). Shapes below were verified
 * against the live API, not assumed from documentation alone — see
 * GET https://bible.helloao.org/api/available_translations.json and
 * GET https://bible.helloao.org/api/{translation}/complete.json.
 */

/** Top-level shape of `GET /api/available_translations.json` — the full translation catalog, ~1,200 entries. */
@Serializable
data class HelloAoTranslationsResponseDto(
    val translations: List<HelloAoTranslationDto> = emptyList()
)

@Serializable
data class HelloAoTranslationDto(
    val id: String,
    val name: String,
    val englishName: String,
    val shortName: String? = null,
    val language: String,
    val languageName: String? = null,
    val languageEnglishName: String? = null,
    val textDirection: String? = null,
    // Verified live: every catalog entry carries this, but default to 0 (never
    // mistaken for a complete 66-book canon) in case one ever doesn't.
    val numberOfBooks: Int = 0,
    // Verified live 2026-10-26: all 207 complete Bibles have both (#98).
    val licenseUrl: String? = null,
    val license: HelloAoLicenseDto? = null,
    val licenseNotice: String? = null
)

/** The catalog's summary of a translation's license, e.g. `{"attribution": "required", ...}`. */
@Serializable
data class HelloAoLicenseDto(
    val attribution: String? = null,    // "required" | "not-required"
    val commercialUse: String? = null,  // "allowed" | "not-allowed"
    val publicDomain: Boolean = false
)

/** Null without an https license page, the only kind [ExternalLinks][com.application.bibleapp.utils.ExternalLinks] opens. */
fun HelloAoTranslationDto.toTranslationLicense(): TranslationLicense? {
    val url = licenseUrl?.trim()?.takeIf { it.startsWith("https://") } ?: return null
    return TranslationLicense(
        url = url,
        // Missing terms are read as the strictest ones: credit it, don't sell it.
        attributionRequired = license?.attribution != "not-required",
        publicDomain = license?.publicDomain == true,
        commercialUseAllowed = license?.commercialUse == "allowed",
        notice = licenseNotice?.trim()?.takeIf { it.isNotEmpty() }
    )
}

fun HelloAoTranslationDto.toBibleTranslation(): BibleTranslation = BibleTranslation(
    id = id,
    displayName = shortName?.takeIf { it.isNotBlank() } ?: englishName,
    nativeName = name,
    languageName = languageEnglishName?.takeIf { it.isNotBlank() } ?: languageName ?: language,
    languageCode = language,
    textDirection = if (textDirection == "rtl") TextDirection.RTL else TextDirection.LTR,
    numberOfBooks = numberOfBooks,
    license = toTranslationLicense()
)

/**
 * Top-level shape of `GET /api/{translationId}/complete.json` — the entire Bible
 * (all 66 books, every chapter and verse) in a single response, typically 5-10MB.
 * This is the one request [HelloAoBibleDataSource.downloadTranslation] makes per
 * translation; there is no per-chapter endpoint in this client at all.
 */
@Serializable
data class HelloAoCompleteTranslationDto(
    val translation: HelloAoTranslationDto,
    val books: List<HelloAoCompleteBookDto> = emptyList()
)

@Serializable
data class HelloAoCompleteBookDto(
    val id: String,
    val name: String,
    val order: Int,
    val numberOfChapters: Int,
    val isApocryphal: Boolean = false,
    val chapters: List<HelloAoChapterEntryDto> = emptyList()
)

@Serializable
data class HelloAoChapterEntryDto(
    val numberOfVerses: Int,
    val chapter: HelloAoChapterContentDto
)

/**
 * [content] is a heterogeneous array ("heading", "line_break", "verse",
 * "hebrew_subtitle" items, each shaped differently) — kept as raw [JsonElement]s
 * and walked manually by [HelloAoContentParser] rather than forced into a
 * polymorphic sealed hierarchy we don't otherwise need. [footnotes] is the
 * chapter-wide footnote text table that inline {"noteId":N} markers inside
 * [content] reference.
 */
@Serializable
data class HelloAoChapterContentDto(
    val number: Int,
    val content: List<JsonElement> = emptyList(),
    val footnotes: List<HelloAoFootnoteDto> = emptyList()
)

@Serializable
data class HelloAoFootnoteDto(
    val noteId: Int,
    val text: String,
    val caller: String? = null,
    val reference: HelloAoFootnoteReferenceDto? = null
)

@Serializable
data class HelloAoFootnoteReferenceDto(
    val chapter: Int,
    val verse: Int
)
