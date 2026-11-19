package com.application.bibleapp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which address the You tab's avatar loads (#335). */
class ProfilePictureTest {

    @Test
    fun `no picture shows the initial`() {
        assertNull(profilePictureRequestUrl(null, 144))
        assertNull(profilePictureRequestUrl("", 144))
        assertNull(profilePictureRequestUrl("   ", 144))
    }

    @Test
    fun `only https addresses are loaded`() {
        assertNull(profilePictureRequestUrl("http://lh3.googleusercontent.com/a/abc=s96-c", 144))
        assertNull(profilePictureRequestUrl("file:///sdcard/me.png", 144))
        assertNull(profilePictureRequestUrl("content://media/1", 144))
    }

    @Test
    fun `a Google address is asked for at the avatar's size`() {
        assertEquals(
            "https://lh3.googleusercontent.com/a/ACg8ocAbc=s144-c",
            profilePictureRequestUrl("https://lh3.googleusercontent.com/a/ACg8ocAbc=s96-c", 144)
        )
    }

    @Test
    fun `other addresses are loaded as they are`() {
        val url = "https://lh3.googleusercontent.com/a/ACg8ocAbc"
        assertEquals(url, profilePictureRequestUrl(url, 144))
        assertEquals(
            "https://example.com/p.png?s=96-c",
            profilePictureRequestUrl("https://example.com/p.png?s=96-c", 144)
        )
    }

    @Test
    fun `an unknown size keeps Google's own`() {
        val url = "https://lh3.googleusercontent.com/a/ACg8ocAbc=s96-c"
        assertEquals(url, profilePictureRequestUrl(url, 0))
    }

    @Test
    fun `surrounding spaces are dropped`() {
        assertEquals(
            "https://lh3.googleusercontent.com/a/x=s48-c",
            profilePictureRequestUrl("  https://lh3.googleusercontent.com/a/x=s96-c ", 48)
        )
    }
}
