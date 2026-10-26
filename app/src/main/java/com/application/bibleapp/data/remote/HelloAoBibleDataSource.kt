package com.application.bibleapp.data.remote

import android.util.Log
import com.application.bibleapp.data.model.BibleTranslation
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.IOException

/** The translation genuinely doesn't exist (404) — permanent, never retried. */
class TranslationNotFoundException(url: String) : IOException("Translation not found: $url")

/**
 * Client for the Free Use Bible API (bible.helloao.org). Replaces the old
 * per-chapter wldeh client: a whole translation is now one `complete.json`
 * request instead of ~1,189 individual chapter requests.
 *
 * Only two of the API's endpoints are used:
 * - `GET /api/available_translations.json` — the full translation catalog, used
 *   once to populate the version picker (see [getAvailableTranslations]).
 * - `GET /api/{translationId}/complete.json` — the whole Bible for one
 *   translation in one response (see [downloadTranslation]).
 *
 * The API also exposes `GET /api/{translationId}/books.json` (book list without
 * verse content) and `GET /api/{translationId}/{book}/{chapter}.json`
 * (single-chapter content) — neither is called here. `complete.json` already
 * contains every book's chapters and verses in one shot, so a separate
 * books-only or chapter-by-chapter call would just be redundant round trips
 * for data this client already has.
 */
class HelloAoBibleDataSource(
    private val baseUrl: String = "https://bible.helloao.org",
    private val client: HttpClient = HttpClientProvider.client
) : BibleRemoteDataSource {

    companion object {
        private const val TAG = "HelloAoAPI"
        private const val MAX_FETCH_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 800L

        // A whole translation is a single, multi-MB request — needs far more
        // headroom than HttpClientProvider's default per-request timeout.
        private const val DOWNLOAD_TIMEOUT_MS = 120_000L
        private const val DEFAULT_BUFFER_SIZE_BYTES = 1 shl 20 // 1 MB, used if Content-Length is missing

        private val TRANSLATION_ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")
    }

    private fun isValidTranslationId(id: String) = id.isNotBlank() && TRANSLATION_ID_PATTERN.matches(id)

    /** Not retried on failure — this is a small (~KB) request the caller can just retry manually. */
    override suspend fun getAvailableTranslations(): List<BibleTranslation> {
        val url = "$baseUrl/api/available_translations.json"
        Log.d(TAG, "Fetching translations from $url")
        val response = client.get(url)
        if (response.status != HttpStatusCode.OK) {
            throw IOException("Failed to load translations: server returned ${response.status}")
        }
        val dto: HelloAoTranslationsResponseDto = response.body()
        Log.d(TAG, "Loaded ${dto.translations.size} translations")
        return dto.translations.map { it.toBibleTranslation() }
    }

    /**
     * Fetches, parses, and maps [translationId]'s complete.json. Transient
     * network failures are retried with backoff; a genuine 404 is not.
     */
    override suspend fun downloadTranslation(
        translationId: String,
        onProgress: (Float) -> Unit
    ): DownloadedTranslation {
        require(isValidTranslationId(translationId)) { "Invalid translation id: $translationId" }

        val url = "$baseUrl/api/$translationId/complete.json"
        var lastError: Exception = IOException("Unknown error downloading $translationId")

        for (attempt in 1..MAX_FETCH_ATTEMPTS) {
            try {
                val dto = fetchComplete(url, onProgress)
                val mapping = HelloAoDownloadMapper.map(dto)
                return DownloadedTranslation(
                    translationId = dto.translation.id,
                    translationName = dto.translation.shortName?.takeIf { it.isNotBlank() }
                        ?: dto.translation.englishName,
                    verses = mapping.verses,
                    footnotes = mapping.footnotes,
                    bookNames = mapping.bookNames,
                    chapterInfo = mapping.chapterInfo,
                    downloadedChapterCount = mapping.downloadedChapterCount,
                    skippedBookCount = mapping.skippedBookCount,
                    fullName = dto.translation.name,
                    license = dto.translation.toTranslationLicense()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationNotFoundException) {
                throw e // permanent — never retry
            } catch (e: Exception) {
                lastError = e
                if (!isRetriableNetworkError(e)) {
                    Log.e(TAG, "Non-retriable error downloading $translationId: ${e::class.simpleName} - ${e.message}")
                    throw e
                }
                if (attempt < MAX_FETCH_ATTEMPTS) {
                    val backoffMs = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                    Log.w(TAG, "Retrying $translationId download in ${backoffMs}ms (attempt $attempt failed: ${e.message})")
                    delay(backoffMs)
                }
            }
        }

        Log.e(TAG, "Giving up on $translationId after $MAX_FETCH_ATTEMPTS attempts: ${lastError.message}")
        throw lastError
    }

    /**
     * Reads the response body in chunks (rather than one `.body<T>()` call) so we
     * can report real byte-based progress via Content-Length as a multi-MB file
     * downloads, then decodes the fully-buffered JSON in one pass.
     */
    private suspend fun fetchComplete(url: String, onProgress: (Float) -> Unit): HelloAoCompleteTranslationDto {
        Log.d(TAG, "Fetching complete translation from $url")
        val response: HttpResponse = client.get(url) {
            timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS }
        }

        when (response.status) {
            HttpStatusCode.OK -> {}
            HttpStatusCode.NotFound -> throw TranslationNotFoundException(url)
            else -> throw IOException("Server returned ${response.status}")
        }

        val expectedBytes = response.contentLength() ?: -1L
        val channel: ByteReadChannel = response.bodyAsChannel()
        val buffer = ByteArrayOutputStream(if (expectedBytes > 0) expectedBytes.toInt() else DEFAULT_BUFFER_SIZE_BYTES)
        val chunk = ByteArray(32 * 1024)
        var totalRead = 0L

        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(chunk)
            if (read == -1) break
            if (read > 0) {
                buffer.write(chunk, 0, read)
                totalRead += read
                if (expectedBytes > 0) {
                    // Cap below 1.0 — parsing/DB write still has to happen after this.
                    onProgress((totalRead.toFloat() / expectedBytes).coerceIn(0f, 0.95f))
                }
            }
        }

        val json = buffer.toByteArray().toString(Charsets.UTF_8)
        return HttpClientProvider.json.decodeFromString(HelloAoCompleteTranslationDto.serializer(), json)
    }
}
