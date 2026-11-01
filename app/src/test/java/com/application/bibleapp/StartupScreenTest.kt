package com.application.bibleapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The startup screen waits for Home's content, but never longer than the cap (#161). */
class StartupScreenTest {

    @Test
    fun `stays up while Home's content is loading`() {
        assertTrue(keepStartupScreen(contentReady = false, elapsedMs = 0))
        assertTrue(keepStartupScreen(contentReady = false, elapsedMs = STARTUP_SCREEN_MAX_WAIT_MS - 1))
    }

    @Test
    fun `goes as soon as the content is ready`() {
        assertFalse(keepStartupScreen(contentReady = true, elapsedMs = 0))
    }

    @Test
    fun `goes at the cap even if the content isn't ready`() {
        assertFalse(keepStartupScreen(contentReady = false, elapsedMs = STARTUP_SCREEN_MAX_WAIT_MS))
        assertFalse(keepStartupScreen(contentReady = false, elapsedMs = 60_000))
    }
}
