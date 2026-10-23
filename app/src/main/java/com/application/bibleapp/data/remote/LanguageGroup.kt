package com.application.bibleapp.data.remote

import com.application.bibleapp.data.model.BUNDLED_TRANSLATION
import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.DownloadedVersionInfo
import com.application.bibleapp.data.model.TextDirection

/**
 * Translations available in one language, ready for display under a section header.
 */
data class LanguageGroup(
    val languageName: String,
    val languageCode: String,
    val translations: List<BibleTranslation>
)

/**
 * Groups [translations] by language (sorted alphabetically by language name),
 * optionally filtered first by [query] against language name, display name, and
 * native name. Pure function — no Android/network dependency — so it's plain-JVM
 * testable.
 */
fun groupVersionsByLanguage(translations: List<BibleTranslation>, query: String = ""): List<LanguageGroup> {
    val trimmedQuery = query.trim()
    val filtered = if (trimmedQuery.isEmpty()) {
        translations
    } else {
        translations.filter { translation ->
            translation.languageName.contains(trimmedQuery, ignoreCase = true) ||
                translation.displayName.contains(trimmedQuery, ignoreCase = true) ||
                translation.nativeName.contains(trimmedQuery, ignoreCase = true)
        }
    }

    return filtered
        .groupBy { it.languageName }
        .entries
        .sortedBy { it.key.lowercase() }
        .map { (languageName, translationsInLanguage) ->
            LanguageGroup(
                languageName = languageName,
                languageCode = translationsInLanguage.first().languageCode,
                translations = translationsInLanguage.sortedBy { it.displayName.lowercase() }
            )
        }
}

/** [catalog] plus the bundled KJV, which the catalog never lists (#56) — so it shows under
 *  English and turns up when searching "KJV" or "King James", like any other translation. */
fun withBundledTranslation(catalog: List<BibleTranslation>): List<BibleTranslation> =
    if (catalog.any { it.id == BUNDLED_TRANSLATION.id }) catalog else catalog + BUNDLED_TRANSLATION

/**
 * The version picker's "Downloaded" shortcut section: the bundled KJV first (#56), then every
 * download — those in [groups] in the order they're listed there, then any the catalog doesn't
 * list (an older id for the same Bible, or every download when the catalog couldn't load
 * offline), labelled with the name stored at download time so they can still be selected or
 * removed (#63).
 */
fun downloadedSection(
    groups: List<LanguageGroup>,
    downloaded: Map<String, DownloadedVersionInfo>
): List<BibleTranslation> {
    val listed = groups.flatMap { it.translations }.filter { it.id != BUNDLED_TRANSLATION.id }
    val listedIds = listed.mapTo(HashSet()) { it.id }
    val unlisted = downloaded.values
        .filter { it.id !in listedIds && it.id != BUNDLED_TRANSLATION.id }
        .map { info ->
            BibleTranslation(
                id = info.id,
                displayName = info.name.ifBlank { info.id },
                nativeName = "",
                languageName = "",
                languageCode = "",
                textDirection = TextDirection.LTR
            )
        }
        .sortedBy { it.displayName.lowercase() }
    return listOf(BUNDLED_TRANSLATION) + listed.filter { downloaded.containsKey(it.id) } + unlisted
}
