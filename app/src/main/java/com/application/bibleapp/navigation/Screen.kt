package com.application.bibleapp.navigation

import androidx.annotation.StringRes
import com.application.bibleapp.R

sealed class Screen(val route: String, @StringRes val titleRes: Int) {
    object Home : Screen("home", R.string.title_home)
    object Bible : Screen("bible", R.string.title_bible)
    object Search : Screen("search", R.string.title_search)
    /** The personal tab: profile, continue reading, and the library. Settings sit one level
     *  below it, behind the top bar's gear. */
    object You : Screen("you", R.string.title_you)
    object BookPicker: Screen("book_picker", R.string.title_select_book)

    /**
     * Carries the chosen book/chapter as path args instead of through the shared
     * [com.application.bibleapp.viewmodel.BibleViewModel] state, so backing out of the verse
     * picker without picking a verse can't leave the ViewModel's currentBook/currentChapter
     * pointing at a chapter whose verses were never actually loaded.
     */
    object VersePicker: Screen("verse_picker/{bookId}/{chapter}", R.string.title_select_verse) {
        fun createRoute(bookId: Int, chapter: Int) = "verse_picker/$bookId/$chapter"
    }

    object VersionPicker: Screen("version_picker", R.string.title_select_version)

    object Login: Screen("login", R.string.title_sign_in)
    object Register: Screen("register", R.string.title_create_account)

    /** [tab] is "highlights" or "notes" — which of Library's segmented tabs opens pre-selected,
     *  so the You tab's library rows can deep-link straight to either one. */
    object Library: Screen("library/{tab}", R.string.title_library) {
        const val TAB_HIGHLIGHTS = "highlights"
        const val TAB_NOTES = "notes"
        fun createRoute(tab: String) = "library/$tab"
    }

    /** A single Library card's full detail — reached by tapping it in [Library], one tap short
     *  of actually jumping into the reading view. [type] is "highlight" or "note"; [localId]
     *  looks the item back up from BibleViewModel's already-loaded library StateFlows rather
     *  than carrying the whole object through the nav graph. */
    object LibraryItemDetail: Screen("library_item/{type}/{localId}", R.string.title_library_item) {
        const val TYPE_HIGHLIGHT = "highlight"
        const val TYPE_NOTE = "note"
        fun createRoute(type: String, localId: Long) = "library_item/$type/$localId"
    }

    /** Every setting on one grouped page, reached from the You tab's gear. */
    object Settings: Screen("settings", R.string.title_settings)

    /** The app's UI language, one level below Settings. */
    object LanguagePicker: Screen("settings/language", R.string.title_language)

    /** Every library the app ships, with its license (#99), one level below Settings. */
    object Licenses: Screen("settings/licenses", R.string.settings_open_source_licenses)
}
