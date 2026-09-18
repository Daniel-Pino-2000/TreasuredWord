package com.application.bibleapp.navigation

sealed class Screen(val route: String, val title: String) {
    object Home : Screen("home", "Home")
    object Bible : Screen("bible", "Bible")
    object Search : Screen("search", "Search")
    object More : Screen("more", "More")
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
     *  so Settings' "My Library" rows (Phase G) can deep-link straight to either one. */
    object Library: Screen("library/{tab}", "Library") {
        const val TAB_HIGHLIGHTS = "highlights"
        const val TAB_NOTES = "notes"
        fun createRoute(tab: String) = "library/$tab"
    }
}
