package com.application.bibleapp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun `parses our own millisecond timestamps`() {
        assertEquals(1_000_123L, parseIsoTimestamp("1970-01-01T00:16:40.123Z"))
    }

    @Test
    fun `parses the server's microsecond and fraction-less timestamps`() {
        // java.time.Instant.toString() on the server: microseconds, or no fraction at all (#84).
        assertEquals(1_000_123L, parseIsoTimestamp("1970-01-01T00:16:40.123456Z"))
        assertEquals(1_000_000L, parseIsoTimestamp("1970-01-01T00:16:40Z"))
        assertEquals(1_000_500L, parseIsoTimestamp("1970-01-01T00:16:40.5Z"))
    }

    @Test
    fun `rejects anything that isn't a UTC instant`() {
        assertNull(parseIsoTimestamp(""))
        assertNull(parseIsoTimestamp("2026-10-23"))
        assertNull(parseIsoTimestamp("2026-10-23T10:00:00+02:00"))
        assertNull(parseIsoTimestamp("2026-13-45T10:00:00Z"))
    }

    @Test
    fun `format and parse round-trip`() {
        val millis = 1_792_000_000_123L
        assertEquals(millis, parseIsoTimestamp(formatIsoTimestamp(millis)))
    }
}
