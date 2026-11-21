package com.application.bibleapp.data.remote

import android.util.Log
import com.application.bibleapp.BuildConfig
import com.application.bibleapp.data.model.UsfmBookCodes
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * A book's name for the book list. YouVersion's `title` is what a chapter is called ("Salmo",
 * "Psalm"), and `full_title` the book ("Salmos", "Psalms"); but `full_title` sometimes drops
 * accents ("Amos" for LBLA's "Amós"), so it only wins when it just extends the title.
 */
internal fun bookName(title: String?, fullTitle: String?): String? {
    val short = title?.trim()?.takeIf { it.isNotEmpty() } ?: return fullTitle?.trim()?.takeIf { it.isNotEmpty() }
    val full = fullTitle?.trim().orEmpty()
    return if (full.length > short.length && full.length - short.length <= 2 && full.startsWith(short)) full else short
}

/** YouVersion said too many requests for the app's key (HTTP 429); worth trying again later (#366). */
class YouVersionBusyException(val retryAfterSeconds: Long?) : IOException("YouVersion rate limit reached")

/** The key isn't licensed for this Bible (403) or isn't valid (401): only a new license or key fixes it. */
class YouVersionNotLicensedException(status: Int) : IOException("YouVersion refused the request: HTTP $status")

/** A Bible's book names and per-chapter verse counts, saved when the version is added. */
data class YouVersionStructure(
    val bookNames: List<MappedBookName>,
    val chapterInfo: List<MappedChapterInfo>
)

/**
 * Reads the licensed Bibles (data/model/YouVersionBibles.kt) from the YouVersion Platform API
 * (https://developers.youversion.com, #366). Chapters are fetched one at a time as they're read,
 * never a whole Bible at once: the API's rate limit is per app key and unpublished, so a
 * download of all 1,189 chapters per reader could lock every reader out.
 *
 * Endpoints used: `GET /v1/bibles/{id}` (the copyright text), `GET /v1/bibles/{id}/index`
 * (book names and verse counts, once per version), and
 * `GET /v1/bibles/{id}/passages/{BOOK.CHAPTER}?format=html` (one chapter).
 */
class YouVersionBibleSource(
    private val appKey: String = BuildConfig.YOUVERSION_APP_KEY,
    private val baseUrl: String = "https://api.youversion.com",
    private val client: HttpClient = HttpClientProvider.client
) {
    /** False in a build made without the key (a fork's CI): the versions aren't offered then. */
    val isConfigured: Boolean get() = appKey.isNotBlank()

    suspend fun fetchStructure(yvId: Int): YouVersionStructure {
        // The index lists every verse id of the Bible, about 1.5 MB, so it gets the long timeout.
        val index = HttpClientProvider.json.decodeFromString(IndexDto.serializer(), get("/v1/bibles/$yvId/index", INDEX_TIMEOUT_MS))
        val bookNames = mutableListOf<MappedBookName>()
        val chapterInfo = mutableListOf<MappedChapterInfo>()
        index.books.forEach { book ->
            val bookId = UsfmBookCodes.bookId(book.id) ?: return@forEach
            bookName(book.title, book.fullTitle)?.let { bookNames += MappedBookName(bookId, it) }
            book.chapters.forEach { chapter ->
                val number = chapter.id.toIntOrNull() ?: return@forEach // skips "INTRO"
                val verseCount = chapter.verses.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: return@forEach
                chapterInfo += MappedChapterInfo(bookId, number, verseCount)
            }
        }
        return YouVersionStructure(bookNames, chapterInfo)
    }

    /** The version's own copyright line, for a license that prescribes no notice of its own. */
    suspend fun fetchCopyright(yvId: Int): String? =
        HttpClientProvider.json.decodeFromString(BibleDto.serializer(), get("/v1/bibles/$yvId")).copyright
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun fetchChapter(yvId: Int, bookId: Int, chapter: Int): YouVersionHtmlParser.Chapter {
        val code = requireNotNull(UsfmBookCodes.code(bookId)) { "No book $bookId" }
        val body = get("/v1/bibles/$yvId/passages/$code.$chapter?format=html&include_headings=true&include_notes=true")
        val passage = HttpClientProvider.json.decodeFromString(PassageDto.serializer(), body)
        return YouVersionHtmlParser.parse(passage.content)
    }

    private suspend fun get(path: String, timeoutMs: Long = CHAPTER_TIMEOUT_MS): String {
        if (!isConfigured) throw YouVersionNotLicensedException(401)
        val response: HttpResponse = client.get(baseUrl + path) {
            header("X-YVP-App-Key", appKey)
            timeout { requestTimeoutMillis = timeoutMs }
        }
        when (response.status) {
            HttpStatusCode.OK -> return response.bodyAsText()
            HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                Log.w(TAG, "Rate limited on $path, retry after ${retryAfter}s")
                throw YouVersionBusyException(retryAfter)
            }
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> throw YouVersionNotLicensedException(response.status.value)
            HttpStatusCode.NotFound -> throw NoSuchElementException("Not found: $path")
            else -> throw IOException("YouVersion returned ${response.status}")
        }
    }

    @Serializable
    private data class BibleDto(val copyright: String? = null)

    @Serializable
    private data class PassageDto(val content: String = "")

    @Serializable
    private data class IndexDto(val books: List<BookDto> = emptyList())

    @Serializable
    private data class BookDto(
        val id: String,
        val title: String? = null,
        @SerialName("full_title") val fullTitle: String? = null,
        val chapters: List<ChapterDto> = emptyList()
    )

    @Serializable
    private data class ChapterDto(val id: String, val verses: List<VerseDto> = emptyList())

    @Serializable
    private data class VerseDto(val id: String, @SerialName("passage_id") val passageId: String? = null)

    private companion object {
        const val TAG = "YouVersionAPI"
        const val CHAPTER_TIMEOUT_MS = 20_000L
        const val INDEX_TIMEOUT_MS = 60_000L
    }
}
