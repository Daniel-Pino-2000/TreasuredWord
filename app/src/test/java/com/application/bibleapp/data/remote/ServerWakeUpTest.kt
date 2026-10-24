package com.application.bibleapp.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ServerWakeUpTest {

    private var clock = 0L
    private var pings = 0

    private fun wakeUp(fail: Boolean = false) = ServerWakeUp(
        ping = {
            pings++
            if (fail) throw IOException("offline")
        },
        now = { clock },
        minIntervalMillis = 1_000
    )

    @Test
    fun `pings once, then not again until the interval has passed`() = runTest {
        val wakeUp = wakeUp()
        wakeUp.wakeUp()
        clock = 999
        wakeUp.wakeUp()
        assertEquals(1, pings)

        clock = 1_000
        wakeUp.wakeUp()
        assertEquals(2, pings)
    }

    @Test
    fun `a failed ping is swallowed and still counts as an attempt`() = runTest {
        val wakeUp = wakeUp(fail = true)
        wakeUp.wakeUp()
        wakeUp.wakeUp()
        assertEquals(1, pings)
    }
}
