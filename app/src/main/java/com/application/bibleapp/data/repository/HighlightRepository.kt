package com.application.bibleapp.data.repository

import com.application.bibleapp.data.remote.CreateHighlightRequestDto
import com.application.bibleapp.data.remote.HighlightListResponseDto
import com.application.bibleapp.data.remote.HighlightResponseDto
import com.application.bibleapp.data.remote.HttpClientProvider
import com.application.bibleapp.data.remote.UpdateHighlightRequestDto
import com.application.bibleapp.data.remote.VerseLocationDto
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * The backend-synced counterpart to a highlight — see server/docs/api_contract.md. Requires
 * being signed in (AuthRepository); every call here relies on HttpClientProvider's Auth
 * plugin to attach the access token automatically.
 */
class HighlightRepository {

    private val client get() = HttpClientProvider.client
    private val baseUrl get() = HttpClientProvider.BASE_URL

    /** [updatedSince] (ISO-8601) also returns soft-deleted tombstones for anything changed since
     *  then — see server/docs/api_contract.md's `GET /highlights` and the sync worker (Phase E). */
    suspend fun listHighlights(
        bookId: Int? = null,
        chapter: Int? = null,
        updatedSince: String? = null
    ): Result<List<HighlightResponseDto>> =
        runCatching {
            val response = client.get("$baseUrl/highlights") {
                url {
                    bookId?.let { parameters.append("bookId", it.toString()) }
                    chapter?.let { parameters.append("chapter", it.toString()) }
                    updatedSince?.let { parameters.append("updatedSince", it) }
                }
            }
            requireSuccess(response)
            response.body<HighlightListResponseDto>().highlights
        }

    suspend fun createHighlight(
        versionId: String,
        verses: List<VerseLocationDto>,
        color: Int
    ): Result<HighlightResponseDto> = runCatching {
        val response = client.post("$baseUrl/highlights") {
            contentType(ContentType.Application.Json)
            setBody(CreateHighlightRequestDto(versionId, verses, color))
        }
        requireSuccess(response)
        response.body()
    }

    suspend fun recolorHighlight(id: String, color: Int): Result<HighlightResponseDto> = runCatching {
        val response = client.patch("$baseUrl/highlights/$id") {
            contentType(ContentType.Application.Json)
            setBody(UpdateHighlightRequestDto(color))
        }
        requireSuccess(response)
        response.body()
    }

    suspend fun deleteHighlight(id: String): Result<Unit> = runCatching {
        val response = client.delete("$baseUrl/highlights/$id")
        requireSuccess(response)
    }

    private suspend fun requireSuccess(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            error("Highlight request failed: ${response.status.value}")
        }
    }
}
