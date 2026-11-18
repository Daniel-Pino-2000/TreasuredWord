package com.application.bibleapp.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tab taps in the bottom bar and the landscape rail (#336). */
class TabNavigationTest {

    @Test
    fun `tapping the tab that is showing is a reselect`() {
        bottomNavigationItems.forEach { tab ->
            assertTrue(tab.route, isTabReselect(currentRoute = tab.route, tappedRoute = tab.route))
        }
    }

    @Test
    fun `tapping another tab is a switch`() {
        assertFalse(isTabReselect(currentRoute = Screen.Home.route, tappedRoute = Screen.Search.route))
        assertFalse(isTabReselect(currentRoute = Screen.Bible.route, tappedRoute = Screen.You.route))
        // Nothing showing yet (the first frame): a tap still goes through.
        assertFalse(isTabReselect(currentRoute = null, tappedRoute = Screen.Home.route))
    }

    @Test
    fun `the reader left on top of another tab is trimmed off when that tab comes back`() {
        // Search -> result -> reader, then Home, then Search: Search's saved screens end in the reader.
        assertTrue(needsTrimToTabRoot(topRoute = Screen.Bible.route, tabRoute = Screen.Search.route))
        assertTrue(needsTrimToTabRoot(topRoute = Screen.Bible.route, tabRoute = Screen.You.route))
    }

    @Test
    fun `a tab restored to its own page is left alone`() {
        bottomNavigationItems.forEach { tab ->
            assertFalse(tab.route, needsTrimToTabRoot(topRoute = tab.route, tabRoute = tab.route))
        }
    }

    @Test
    fun `Home, where Back from every tab ends, is the first tab`() {
        // Navigation's start destination; navigateToTab pops back to it.
        assertTrue(bottomNavigationItems.first().route == Screen.Home.route)
    }
}
