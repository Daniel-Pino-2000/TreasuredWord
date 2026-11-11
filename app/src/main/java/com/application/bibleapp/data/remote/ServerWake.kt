package com.application.bibleapp.data.remote

import android.os.SystemClock
import io.ktor.client.request.get
import kotlinx.coroutines.CancellationException

/**
 * When the app last had an answer from our server, as [SystemClock.elapsedRealtime]. Every
 * response from it counts (HttpClientProvider records them), so a server that answered recently
 * is known to be awake without asking it.
 */
object BackendContact {
    @Volatile
    private var lastHeardAt: Long? = null

    fun heard(at: Long = SystemClock.elapsedRealtime()) {
        lastHeardAt = at
    }

    fun heardWithin(millis: Long, now: Long = SystemClock.elapsedRealtime()): Boolean =
        lastHeardAt?.let { now - it < millis } ?: false
}

/**
 * Wakes our server before a Bible Helper question is sent (#280). Render's free plan stops it after
 * 15 minutes without requests, and starting again takes about a minute. Waiting for that first,
 * on its own, lets the screen say the server is waking up, and gives the question its whole
 * timeout for the answer instead of sharing it with the start.
 */
interface ServerWake {
    /** True when the server answered recently enough that it's surely still running. */
    fun isAwake(): Boolean

    /** Returns once the server answers, or the request fails; the question then reports its own error. */
    suspend fun wake()
}

/** [ServerWake] for the deployed server: its health check, through the shared client. */
object RenderServerWake : ServerWake {
    /** Render stops the server 15 minutes after the last request; 10 leaves a margin. */
    private const val AWAKE_FOR_MILLIS = 10 * 60 * 1000L

    override fun isAwake(): Boolean = BackendContact.heardWithin(AWAKE_FOR_MILLIS)

    override suspend fun wake() {
        try {
            HttpClientProvider.client.get(HttpClientProvider.HEALTH_URL)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline, or still not up: the question itself will say what went wrong.
        }
    }
}
