package com.application.bibleapp.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The swipe-to-delete gate in Library: half the card's width, whatever the flick's speed (#243, #334). */
class SwipeToDeleteTest {

    private val width = 1000

    @Test
    fun `a drag past half the width deletes`() {
        assertTrue(swipeChangeAllowed(toDismissed = true, offset = -500f, rowWidth = width))
        assertTrue(swipeChangeAllowed(toDismissed = true, offset = -900f, rowWidth = width))
    }

    @Test
    fun `a short drag or flick does not delete`() {
        assertFalse(swipeChangeAllowed(toDismissed = true, offset = -499f, rowWidth = width))
        assertFalse(swipeChangeAllowed(toDismissed = true, offset = -40f, rowWidth = width))
        assertFalse(swipeChangeAllowed(toDismissed = true, offset = 0f, rowWidth = width))
    }

    @Test
    fun `springing back is always allowed`() {
        assertTrue(swipeChangeAllowed(toDismissed = false, offset = -40f, rowWidth = width))
        assertTrue(swipeChangeAllowed(toDismissed = false, offset = -900f, rowWidth = width))
    }
}
