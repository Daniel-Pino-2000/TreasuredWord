package com.application.bibleapp.navigation

sealed class Screen(val route: String, val title: String) {
    object Home : Screen("home", "Home")
    object Bible : Screen("bible", "Bible")
    object Search : Screen("search", "Search")
    /** The personal tab: profile, continue reading, and the library. Settings sit one level
     *  below it, behind the top bar's gear. */
    object You : Screen("you", "You")
    object BookPicker: Screen("book_picker", "Select Book")

    /**
     * Carries the chosen book/chapter as path args instead of through the shared
     * [com.application.bibleapp.viewmodel.BibleViewModel] state, so backing out of the verse
     * picker without picking a verse can't leave the ViewModel's currentBook/currentChapter
     * pointing at a chapter whose verses were never actually loaded.
     */
    object VersePicker: Screen("verse_picker/{bookId}/{chapter}", "Select Verse") {
        fun createRoute(bookId: Int, chapter: Int) = "verse_picker/$bookId/$chapter"
    }

    object VersionPicker: Screen("version_picker", "Select Version")

    object Login: Screen("login", "Sign In")
    object Register: Screen("register", "Create Account")

    /** [tab] is "highlights" or "notes" — which of Library's segmented tabs opens pre-selected,
     *  so the You tab's library rows can deep-link straight to either one. */
    object Library: Screen("library/{tab}", "Library") {
        const val TAB_HIGHLIGHTS = "highlights"
        const val TAB_NOTES = "notes"
        fun createRoute(tab: String) = "library/$tab"
    }

    /** A single Library card's full detail — reached by tapping it in [Library], one tap short
     *  of actually jumping into the reading view. [type] is "highlight" or "note"; [localId]
     *  looks the item back up from BibleViewModel's already-loaded library StateFlows rather
     *  than carrying the whole object through the nav graph. */
    object LibraryItemDetail: Screen("library_item/{type}/{localId}", "Detail") {
        const val TYPE_HIGHLIGHT = "highlight"
        const val TYPE_NOTE = "note"
        fun createRoute(type: String, localId: Long) = "library_item/$type/$localId"
    }

    /** Every setting on one grouped page, reached from the You tab's gear. */
    object Settings: Screen("settings", "Settings")
}
