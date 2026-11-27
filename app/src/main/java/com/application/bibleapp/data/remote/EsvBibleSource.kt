package com.application.bibleapp.data.remote

import android.util.Log
import com.application.bibleapp.BuildConfig
import com.application.bibleapp.data.model.BibleBooks
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.io.IOException

/** Crossway throttled the app's key (HTTP 429): over 60 a minute, 1,000 an hour or 5,000 a day (#377). */
class EsvBusyException(val retryAfterSeconds: Long?) : IOException("ESV API rate limit reached")

/** One verse of a Crossway search result (#377): its reference and its text, shown but never saved. */
data class EsvSearchResult(val ref: VerseRef, val text: String)

/** One page of Crossway's search; [hasMore] when there are later pages. */
data class EsvSearchPage(val results: List<EsvSearchResult>, val hasMore: Boolean)

/**
 * Reads the ESV from Crossway's free, non-commercial ESV API (https://api.esv.org, #377). The key
 * is shared by every user of the app, and the API allows it 5,000 queries a day, 1,000 an hour and
 * 60 a minute; above that it throttles (HTTP 429) and charges nothing. So a chapter is fetched only
 * when it's opened and isn't saved (no read-ahead), and search asks for one page at a time.
 *
 * Endpoints: `GET /v3/passage/html/` (one chapter) and `GET /v3/passage/search/` (word search:
 * the words go to Crossway, see docs/privacy-policy.md). What may be kept on the phone is decided
 * by [com.application.bibleapp.data.model.EsvStorage].
 */
class EsvBibleSource(
    private val apiKey: String = BuildConfig.ESV_API_KEY,
    private val baseUrl: String = "https://api.esv.org",
    private val client: HttpClient = HttpClientProvider.client
) {
    /** False in a build made without the key (a fork's CI): ESV isn't offered then. */
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /** [bookId] [chapter] as Crossway's HTML, parsed; its verse count is what the chapter really has. */
    suspend fun fetchChapter(bookId: Int, chapter: Int): EsvHtmlParser.Chapter {
        val path = "/v3/passage/html/?q=${chapterQuery(bookId, chapter).encodeURLParameter()}&$CHAPTER_OPTIONS"
        val passage = HttpClientProvider.json.decodeFromString(PassageDto.serializer(), get(path))
        return EsvHtmlParser.parse(passage.passages.joinToString(""))
    }

    /** Page [page] (from 1) of Crossway's search for [query]. */
    suspend fun search(query: String, page: Int): EsvSearchPage {
        val path = "/v3/passage/search/?q=${query.take(MAX_QUERY_LENGTH).encodeURLParameter()}" +
            "&page-size=$SEARCH_PAGE_SIZE&page=$page"
        val dto = HttpClientProvider.json.decodeFromString(SearchDto.serializer(), get(path))
        return EsvSearchPage(
            results = dto.results.mapNotNull { result ->
                val ref = parseEsvReference(result.reference) ?: return@mapNotNull null
                val text = result.content.replace(WHITESPACE, " ").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                EsvSearchResult(ref, text)
            },
            hasMore = dto.page < dto.totalPages
        )
    }

    private suspend fun get(path: String): String {
        if (!isConfigured) throw IOException("No ESV API key in this build")
        val response: HttpResponse = client.get(baseUrl + path) {
            header("Authorization", "Token $apiKey")
            timeout { requestTimeoutMillis = TIMEOUT_MS }
        }
        when (response.status) {
            HttpStatusCode.OK -> return response.bodyAsText()
            HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                Log.w(TAG, "Throttled, retry after ${retryAfter}s")
                throw EsvBusyException(retryAfter)
            }
            // A key that was revoked or mistyped: only a new key fixes it (docs/bible-licensing.md).
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> throw IOException("ESV API refused the key: ${response.status}")
            else -> throw IOException("ESV API returned ${response.status}")
        }
    }

    @Serializable
    private data class PassageDto(val passages: List<String> = emptyList())

    @Serializable
    private data class SearchDto(
        val page: Int = 1,
        @SerialName("total_pages") val totalPages: Int = 1,
        val results: List<SearchResultDto> = emptyList()
    )

    @Serializable
    private data class SearchResultDto(val reference: String = "", val content: String = "")

    companion object {
        private const val TAG = "EsvAPI"
        private const val TIMEOUT_MS = 20_000L
        const val SEARCH_PAGE_SIZE = 20
        private const val MAX_QUERY_LENGTH = 100
        private val WHITESPACE = Regex("""\s+""")

        /**
         * What a chapter request asks for: the text with its verse numbers, headings, psalm titles
         * and footnotes, without the parts the reader shows itself (the reference, the copyright,
         * which the chapter's credit carries in full) or doesn't offer (audio, cross-references).
         */
        private const val CHAPTER_OPTIONS = "include-passage-references=false&include-short-copyright=false" +
            "&include-copyright=false&include-audio-link=false&include-crossrefs=false&include-css-link=false" +
            "&include-footnotes=true&include-footnote-body=true&include-headings=true&include-subheadings=true" +
            "&include-verse-numbers=true&include-first-verse-numbers=true&include-chapter-numbers=true" +
            "&include-surrounding-chapters=false&include-book-titles=false&include-verse-anchors=false&wrapping-div=false"

        /**
         * A whole chapter as Crossway's verse ids, `BBCCC001-BBCCC999` (Genesis 1 is
         * `01001001-01001999`), so a one-chapter book like Jude needs no special name.
         */
        internal fun chapterQuery(bookId: Int, chapter: Int): String {
            val prefix = "%02d%03d".format(bookId, chapter)
            return "${prefix}001-${prefix}999"
        }

        /** "John 3:16" or "Song of Solomon 2:4" (a search result's reference) as a verse; null for a range or anything else. */
        internal fun parseEsvReference(reference: String): VerseRef? {
            val match = REFERENCE.matchEntire(reference.trim()) ?: return null
            val bookId = BibleBooks.getBookByName(match.groupValues[1])?.id ?: return null
            val chapter = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: 1
            val verse = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return VerseRef(bookId, chapter, verse)
        }

        // "John 3:16"; a one-chapter book's verse may come as "Jude 3" or "Jude 1:3".
        private val REFERENCE = Regex("""^(.+?)\s+(?:(\d+):)?(\d+)$""")
    }
}
