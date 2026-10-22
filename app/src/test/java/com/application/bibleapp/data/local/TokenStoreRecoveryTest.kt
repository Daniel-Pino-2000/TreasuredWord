package com.application.bibleapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

class TokenStoreRecoveryTest {

    @Test
    fun `opens normally without resetting`() {
        var resets = 0
        val result = openOrResetOnce(open = { "prefs" }, reset = { resets++ })
        assertEquals("prefs", result)
        assertEquals(0, resets)
    }

    @Test
    fun `unreadable store is reset once and reopened`() {
        // Same shape as the restored-without-its-key crash: first open fails MAC verification.
        val failure = GeneralSecurityException("Signature/MAC verification failed")
        var opens = 0
        var resetWith: Exception? = null
        val result = openOrResetOnce(
            open = { if (opens++ == 0) throw failure else "fresh prefs" },
            reset = { resetWith = it }
        )
        assertEquals("fresh prefs", result)
        assertEquals(2, opens)
        assertSame(failure, resetWith)
    }

    @Test
    fun `second failure is thrown instead of looping`() {
        var resets = 0
        val thrown = runCatching {
            openOrResetOnce<String>(
                open = { throw IllegalStateException("keystore broken") },
                reset = { resets++ }
            )
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(1, resets)
    }
}
