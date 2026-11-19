package com.application.bibleapp.data.repository

import com.application.bibleapp.data.remote.BackendHttpException
import com.application.bibleapp.data.remote.CreateMemoryVerseRequestDto
import com.application.bibleapp.data.remote.HttpClientProvider
import com.application.bibleapp.data.remote.MemoryProgressDto
import com.application.bibleapp.data.remote.MemoryVerseListResponseDto
import com.application.bibleapp.data.remote.MemoryVerseResponseDto
import com.application.bibleapp.data.remote.UpdateMemoryVerseRequestDto
import com.application.bibleapp.worker.MemoryVerseSyncApi
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

/** The backend side of the Memorize list (#362) — see server/docs/api_contract.md, "Memorize".
 *  Needs a signed-in account, like [NoteRepository]; only the sync pass calls it. */
class MemoryVerseRepository : MemoryVerseSyncApi {

    private val client get() = HttpClientProvider.client
    private val baseUrl get() = HttpClientProvider.BASE_URL

    override suspend fun listMemoryVersesChangedSince(updatedSince: String?): Result<MemoryVerseListResponseDto> =
        runCatching {
            val response = client.get("$baseUrl/memory-verses") {
                url { updatedSince?.let { parameters.append("updatedSince", it) } }
            }
            requireSuccess(response)
            response.body()
        }

    override suspend fun createMemoryVerse(request: CreateMemoryVerseRequestDto): Result<MemoryVerseResponseDto> =
        runCatching {
            val response = client.post("$baseUrl/memory-verses") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            requireSuccess(response)
            response.body()
        }

    override suspend fun updateMemoryProgress(id: String, progress: MemoryProgressDto): Result<MemoryVerseResponseDto> =
        runCatching {
            val response = client.patch("$baseUrl/memory-verses/$id") {
                contentType(ContentType.Application.Json)
                setBody(UpdateMemoryVerseRequestDto(progress))
            }
            requireSuccess(response)
            response.body()
        }

    override suspend fun deleteMemoryVerse(id: String): Result<Unit> = runCatching {
        val response = client.delete("$baseUrl/memory-verses/$id")
        requireSuccess(response)
    }

    private fun requireSuccess(response: HttpResponse) {
        if (!response.status.isSuccess()) throw BackendHttpException(response.status.value, "Memory verse")
    }
}
