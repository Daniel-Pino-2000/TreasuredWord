package com.application.bibleapp.data.repository

import com.application.bibleapp.data.remote.BackendHttpException
import com.application.bibleapp.data.remote.HttpClientProvider
import com.application.bibleapp.data.remote.ReadingProgressResponseDto
import com.application.bibleapp.data.remote.UpdateReadingProgressRequestDto
import com.application.bibleapp.worker.ReadingProgressSyncApi
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * The backend-synced "where I left off" position — a singleton per user, not a list, matching
 * server/docs/api_contract.md decisions 7-8. Requires being signed in.
 */
class ReadingProgressRepository : ReadingProgressSyncApi {

    private val client get() = HttpClientProvider.client
    private val baseUrl get() = HttpClientProvider.BASE_URL

    /** Null (not a failure) means the user has never synced a position from any device yet —
     *  the backend's documented 404 case for a brand-new account. */
    override suspend fun getProgress(): Result<ReadingProgressResponseDto?> = runCatching {
        val response = client.get("$baseUrl/reading-progress")
        when {
            response.status == HttpStatusCode.NotFound -> null
            response.status.isSuccess() -> response.body()
            else -> throw BackendHttpException(response.status.value, "Reading progress")
        }
    }

    /** Pushes a position stamped [readAt]: when it was actually read on this device, never
     *  "now" — an old position sent as "now" would beat a newer one from another device (#82).
     *  The server silently ignores this if it already has a newer position (the stale-write
     *  guard) — the returned value is always whatever is now actually current, whether that's
     *  this update or the one the server kept. */
    override suspend fun updateProgress(
        versionId: String,
        bookId: Int,
        chapter: Int,
        verse: Int,
        readAt: String
    ): Result<ReadingProgressResponseDto> =
        runCatching {
            val response = client.put("$baseUrl/reading-progress") {
                contentType(ContentType.Application.Json)
                setBody(
                    UpdateReadingProgressRequestDto(
                        versionId = versionId,
                        bookId = bookId,
                        chapter = chapter,
                        verse = verse,
                        readAt = readAt
                    )
                )
            }
            if (!response.status.isSuccess()) throw BackendHttpException(response.status.value, "Reading progress update")
            response.body()
        }
}
