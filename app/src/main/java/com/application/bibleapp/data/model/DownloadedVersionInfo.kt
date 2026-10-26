package com.application.bibleapp.data.model

/**
 * What we know locally about a translation that has already been downloaded.
 *
 * [schemaVersion] records which shape of [BibleDatabaseManager.downloadAndSaveVersion]
 * wrote this data. It lets the version picker tell a translation downloaded before
 * a feature like footnotes existed apart from one that already has it, without
 * guessing from row counts.
 */
data class DownloadedVersionInfo(
    val id: String,
    val schemaVersion: Int,
    // The name stored at download time — labels a download the catalog no longer lists (#63).
    val name: String = "",
    // Saved with the download so the reader's credit works offline (#98); empty/null for
    // downloads made before that until the catalog next loads (BibleRepository.getAllVersions).
    val fullName: String = "",
    val license: TranslationLicense? = null
) {
    val isUpToDate: Boolean get() = schemaVersion >= CURRENT_DOWNLOAD_SCHEMA_VERSION

    companion object {
        /**
         * Bump this whenever a download starts capturing genuinely new data (not just a
         * new way of rendering data already stored). 1 = plain text only. 2 = adds
         * rich_content (red-letter/poems/headings) and the footnotes table. 3 = strips
         * the leading pilcrow (¶) out of run text into [VerseRun.paragraphBreakBefore] —
         * rows downloaded before this still carry the literal glyph in their text. 4 =
         * adds the downloaded_book_names table (each book's name in the translation's
         * own language) — versions downloaded before this have no rows there and fall
         * back to BibleBooks' English names until re-downloaded. 5 = adds the
         * downloaded_chapter_info table (per-chapter verse counts, as this translation's
         * own versification actually has them) — versions downloaded before this fall
         * back to BibleBooks' KJV-based chapter/verse structure until re-downloaded.
         */
        const val CURRENT_DOWNLOAD_SCHEMA_VERSION = 5
    }
}
