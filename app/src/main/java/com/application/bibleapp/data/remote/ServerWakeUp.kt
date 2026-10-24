package com.application.bibleapp.data.remote

import android.os.SystemClock
import kotlinx.coroutines.CancellationException

/**
 * Starts waking our server before the user needs it (#87). Render's free tier puts it to sleep
 * after 15 minutes without requests, and the next one waits ~30–60 s while it starts. Calling
 * [wakeUp] when the sign-in screen opens lets that happen while the email and password are
 * being typed, instead of after Sign in is tapped.
 *
 * Fire and forget: failures are ignored, since the real request reports its own. At most one
 * ping per [minIntervalMillis], because a server that answered (or was asked) that recently is
 * awake or already starting.
 */
class ServerWakeUp(
    private val ping: suspend () -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val minIntervalMillis: Long = MIN_INTERVAL_MILLIS
) {
    private var lastPingAt: Long? = null

    suspend fun wakeUp() {
        if (!claimPing()) return
        try {
            ping()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or still asleep: the sign-in request itself will say so.
        }
    }

    @Synchronized
    private fun claimPing(): Boolean {
        val time = now()
        val last = lastPingAt
        if (last != null && time - last < minIntervalMillis) return false
        lastPingAt = time
        return true
    }

    private companion object {
        /** Well inside the 15 minutes Render waits before putting the server back to sleep. */
        const val MIN_INTERVAL_MILLIS = 5 * 60 * 1000L
    }
}
