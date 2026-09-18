package com.application.bibleapp.data.repository

import com.application.bibleapp.data.remote.CreateNoteRequestDto
import com.application.bibleapp.data.remote.HttpClientProvider
import com.application.bibleapp.data.remote.NoteListResponseDto
import com.application.bibleapp.data.remote.NoteResponseDto
import com.application.bibleapp.data.remote.UpdateNoteRequestDto
import com.application.bibleapp.data.remote.VerseLocationDto
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * The backend-synced counterpart to a note — see server/docs/api_contract.md. Requires being
 * signed in (AuthRepository); every call here relies on HttpClientProvider's Auth plugin to
 * attach the access token automatically.
 */
class NoteRepository {

    private val client get() = HttpClientProvider.client
    private val baseUrl get() = HttpClientProvider.BASE_URL

    /** [updatedSince] (ISO-8601) also returns soft-deleted tombstones for anything changed since
     *  then — see server/docs/api_contract.md's `GET /notes` and the sync worker (Phase E). */
    suspend fun listNotes(
        bookId: Int? = null,
        chapter: Int? = null,
        updatedSince: String? = null
    ): Result<List<NoteResponseDto>> =
        runCatching {
            val response = client.get("$baseUrl/notes") {
                url {
                    bookId?.let { parameters.append("bookId", it.toString()) }
                    chapter?.let { parameters.append("chapter", it.toString()) }
                    updatedSince?.let { parameters.append("updatedSince", it) }
                }
            }
            requireSuccess(response)
            response.body<NoteListResponseDto>().notes
        }

    suspend fun getNote(id: String): Result<NoteResponseDto> = runCatching {
        val response = client.get("$baseUrl/notes/$id")
        requireSuccess(response)
        response.body()
    }

    suspend fun createNote(
        versionId: String,
        verses: List<VerseLocationDto>,
        text: String
    ): Result<NoteResponseDto> = runCatching {
        val response = client.post("$baseUrl/notes") {
            contentType(ContentType.Application.Json)
            setBody(CreateNoteRequestDto(versionId, verses, text))
        }
        requireSuccess(response)
        response.body()
    }

    suspend fun updateNote(id: String, verses: List<VerseLocationDto>, text: String): Result<NoteResponseDto> =
        runCatching {
            val response = client.put("$baseUrl/notes/$id") {
                contentType(ContentType.Application.Json)
                setBody(UpdateNoteRequestDto(verses, text))
            }
            requireSuccess(response)
            response.body()
        }

    suspend fun deleteNote(id: String): Result<Unit> = runCatching {
        val response = client.delete("$baseUrl/notes/$id")
        requireSuccess(response)
    }

    private suspend fun requireSuccess(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            error("Note request failed: ${response.status.value}")
        }
    }
}
