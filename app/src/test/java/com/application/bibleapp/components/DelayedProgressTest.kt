package com.application.bibleapp.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Timing of the delayed progress indicator Search uses so a quick search doesn't flash it (#337). */
class DelayedProgressTest {

    @Test
    fun `a load that starts while hidden shows the bar after the delay`() {
        assertEquals(
            ProgressChange(visible = true, afterMillis = PROGRESS_SHOW_AFTER_MILLIS),
            nextProgressChange(loading = true, visible = false, shownForMillis = 0)
        )
    }

    @Test
    fun `a load that ends hides the bar only once it has been up the minimum`() {
        assertEquals(
            ProgressChange(visible = false, afterMillis = 400),
            nextProgressChange(loading = false, visible = true, shownForMillis = 100)
        )
        assertEquals(
            ProgressChange(visible = false, afterMillis = 0),
            nextProgressChange(loading = false, visible = true, shownForMillis = 2_000)
        )
    }

    @Test
    fun `nothing changes while the bar already matches the load`() {
        assertNull(nextProgressChange(loading = true, visible = true, shownForMillis = 100))
        assertNull(nextProgressChange(loading = false, visible = false, shownForMillis = 0))
    }

    @Test
    fun `a quick search never shows the bar`() {
        // Word search on the phone's own Bible: done well inside the delay, typed twice.
        val shown = simulate(0L to true, 120L to false, 600L to true, 780L to false)
        assertEquals(emptyList<Pair<Long, Boolean>>(), shown)
    }

    @Test
    fun `a slow search shows the bar after the delay and keeps it the minimum`() {
        // Shown at 500 ms; the search ends at 650, but the bar stays until 1000.
        val shown = simulate(0L to true, 650L to false)
        assertEquals(listOf(500L to true, 1_000L to false), shown)
    }

    @Test
    fun `a long search hides the bar as soon as it ends`() {
        val shown = simulate(0L to true, 3_000L to false)
        assertEquals(listOf(500L to true, 3_000L to false), shown)
    }

    @Test
    fun `a new search while the bar is held keeps it up without a gap`() {
        // The debounce starts the next search just after the last one ended.
        val shown = simulate(0L to true, 600L to false, 700L to true, 1_500L to false)
        assertEquals(listOf(500L to true, 1_500L to false), shown)
    }

    /**
     * Plays a timeline of loading changes through [nextProgressChange] the way
     * [rememberDelayedProgress] does (a change of loading cancels the pending one), and returns
     * when the bar showed or hid.
     */
    private fun simulate(vararg loadingAt: Pair<Long, Boolean>): List<Pair<Long, Boolean>> {
        val changes = mutableListOf<Pair<Long, Boolean>>()
        var visible = false
        var shownAt = 0L
        var pending: Pair<Long, Boolean>? = null
        fun firePendingUpTo(time: Long) {
            val (at, show) = pending ?: return
            if (at > time) return
            visible = show
            if (show) shownAt = at
            changes += at to show
            pending = null
        }
        for ((time, loading) in loadingAt) {
            firePendingUpTo(time)
            pending = nextProgressChange(loading, visible, shownForMillis = time - shownAt)
                ?.let { time + it.afterMillis to it.visible }
        }
        firePendingUpTo(Long.MAX_VALUE)
        return changes
    }
}
