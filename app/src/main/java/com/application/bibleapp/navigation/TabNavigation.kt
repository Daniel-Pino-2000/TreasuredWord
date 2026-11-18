package com.application.bibleapp.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

/**
 * A tap on a tab in the bottom bar or the landscape rail (#336). Before, each tap pushed a new copy
 * of the screen: tapping the tab you were on reloaded it with a blink, and Back walked through
 * every tab you'd tapped.
 *
 * Now, Android's pattern for bottom navigation:
 * - Tapping the tab you're on does nothing.
 * - Only one tab sits above Home at a time, so Back from a tab goes to Home, then leaves the app.
 * - Each tab keeps what was open on it (saveState/restoreState) for when you come back.
 */
fun NavController.navigateToTab(route: String) {
    if (isTabReselect(currentDestination?.route, route)) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    // A tab's saved screens can end in another tab's page: a search result, the Bible Helper's
    // "Read chapter" or Library's "Open in Bible" open the reader on top of Search, Ask or You.
    // Tapping Search again should show Search, not the reader left on top of it.
    if (needsTrimToTabRoot(currentDestination?.route, route)) popBackStack(route, inclusive = false)
}

/**
 * Back to the reader the book, verse or version picker was opened from, instead of a second
 * reader on top of the pickers: Back from it walked through the verse and book pickers again
 * (#336). Opens the reader when it isn't underneath (the version picker from Settings).
 */
fun NavController.returnToReader() {
    if (!popBackStack(Screen.Bible.route, inclusive = false)) navigate(Screen.Bible.route)
}

/** The tab tapped is the screen showing, so nothing should reload or be added to Back. */
internal fun isTabReselect(currentRoute: String?, tappedRoute: String): Boolean = currentRoute == tappedRoute

/** After restoring [tabRoute]'s saved screens, something other than the tab's own page is on top. */
internal fun needsTrimToTabRoot(topRoute: String?, tabRoute: String): Boolean = topRoute != tabRoute
