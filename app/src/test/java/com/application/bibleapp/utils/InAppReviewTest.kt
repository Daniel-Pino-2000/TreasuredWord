package com.application.bibleapp.utils

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the app asks Play for its rating card (#426), and what a review flow does. No network. */
class InAppReviewTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L
    private val usedEnough = ReviewPromptState(daysUsed = REVIEW_MIN_DAYS_USED, lastDayUsed = "2026-331")

    @Test
    fun `a new day of use counts once`() {
        val first = ReviewPromptState().usedOn("2026-331")
        assertEquals(1, first.daysUsed)
        assertEquals(first, first.usedOn("2026-331"))
        assertEquals(2, first.usedOn("2026-332").daysUsed)
    }

    @Test
    fun `never asks in the first days of use`() {
        var state = ReviewPromptState()
        assertFalse(shouldAskForReview(state, now))
        state = state.usedOn("2026-331").usedOn("2026-332")
        // Opening the app again and again on one day doesn't add days.
        repeat(5) { state = state.usedOn("2026-332") }
        assertFalse(shouldAskForReview(state, now))
        assertTrue(shouldAskForReview(state.usedOn("2026-333"), now))
    }

    @Test
    fun `asks at most once every 90 days`() {
        val asked = usedEnough.copy(lastAskedAtMs = now)
        assertFalse(shouldAskForReview(asked, now))
        assertFalse(shouldAskForReview(asked, now + 89 * day))
        assertFalse(shouldAskForReview(asked, now + REVIEW_ASK_INTERVAL_MS - 1))
        assertTrue(shouldAskForReview(asked, now + REVIEW_ASK_INTERVAL_MS))
        assertEquals(90 * day, REVIEW_ASK_INTERVAL_MS)
    }

    @Test
    fun `a clock that went backwards ends the wait`() {
        assertTrue(shouldAskForReview(usedEnough.copy(lastAskedAtMs = now), now - day))
    }

    @Test
    fun `a review flow launches Play's card and starts the wait`() = runTest {
        val events = mutableListOf<String>()
        val launched = runReviewFlow(
            request = { "info" },
            launch = { events += "launched $it" },
            stillWanted = { true },
            onAsked = { events += "asked" }
        )
        assertTrue(launched)
        // The wait starts before the card opens, so it counts even if Play shows nothing.
        assertEquals(listOf("asked", "launched info"), events)
    }

    @Test
    fun `nothing is launched or counted when the person moved on meanwhile`() = runTest {
        val events = mutableListOf<String>()
        val launched = runReviewFlow(
            request = { "info" },
            launch = { events += "launched" },
            stillWanted = { false },
            onAsked = { events += "asked" }
        )
        assertFalse(launched)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a failed request (offline, not from Play) is ignored and doesn't start the wait`() = runTest {
        val events = mutableListOf<String>()
        val launched = runReviewFlow<String>(
            request = { throw IllegalStateException("No Play Store") },
            launch = { events += "launched" },
            stillWanted = { true },
            onAsked = { events += "asked" }
        )
        assertFalse(launched)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a card that fails to open still counts as asked`() = runTest {
        var asked = 0
        val launched = runReviewFlow(
            request = { "info" },
            launch = { throw IllegalStateException("Play closed") },
            stillWanted = { true },
            onAsked = { asked++ }
        )
        assertTrue(launched)
        assertEquals(1, asked)
    }
}
