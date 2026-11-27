package com.application.bibleapp.utils

import android.app.Activity
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.testing.FakeReviewManager
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
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
    fun `a fake Play review flow runs and starts the wait`() = runTest {
        var asked = 0
        val launched = runReviewFlow(FakeReviewManager(null), Activity(), stillWanted = { true }, onAsked = { asked++ })
        assertTrue(launched)
        assertEquals(1, asked)
    }

    @Test
    fun `nothing is launched or counted when the person moved on meanwhile`() = runTest {
        var asked = 0
        val launched = runReviewFlow(FakeReviewManager(null), Activity(), stillWanted = { false }, onAsked = { asked++ })
        assertFalse(launched)
        assertEquals(0, asked)
    }

    @Test
    fun `a failed request (offline, not from Play) is ignored and doesn't start the wait`() = runTest {
        var asked = 0
        val launched = runReviewFlow(FailingReviewManager, Activity(), stillWanted = { true }, onAsked = { asked++ })
        assertFalse(launched)
        assertEquals(0, asked)
    }

    private object FailingReviewManager : ReviewManager {
        override fun requestReviewFlow(): Task<ReviewInfo> = Tasks.forException(IllegalStateException("No Play Store"))
        override fun launchReviewFlow(activity: Activity, reviewInfo: ReviewInfo): Task<Void> =
            error("Not reached: the request failed")
    }
}
