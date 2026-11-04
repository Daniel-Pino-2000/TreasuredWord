package com.application.bibleapp.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the app shows Play's update dialog again after "No thanks" (#202). */
class InAppUpdatesTest {
    private val declinedAt = 1_000_000_000L

    @Test
    fun `offers an update nobody has declined`() {
        assertTrue(shouldOfferUpdate(availableVersionCode = 6, declinedVersionCode = null, declinedAtMs = 0, nowMs = declinedAt))
    }

    @Test
    fun `doesn't ask again for a declined version during the snooze`() {
        assertFalse(shouldOfferUpdate(6, declinedVersionCode = 6, declinedAtMs = declinedAt, nowMs = declinedAt))
        assertFalse(shouldOfferUpdate(6, 6, declinedAt, nowMs = declinedAt + UPDATE_DECLINE_SNOOZE_MS - 1))
    }

    @Test
    fun `asks again once the snooze is over`() {
        assertTrue(shouldOfferUpdate(6, 6, declinedAt, nowMs = declinedAt + UPDATE_DECLINE_SNOOZE_MS))
    }

    @Test
    fun `offers a newer version than the declined one straight away`() {
        assertTrue(shouldOfferUpdate(7, declinedVersionCode = 6, declinedAtMs = declinedAt, nowMs = declinedAt))
    }

    @Test
    fun `a clock set backwards ends the snooze`() {
        assertTrue(shouldOfferUpdate(6, 6, declinedAt, nowMs = declinedAt - 1))
    }
}
