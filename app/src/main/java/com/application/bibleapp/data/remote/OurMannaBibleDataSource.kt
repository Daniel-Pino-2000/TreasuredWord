package com.application.bibleapp.data.remote

import android.util.Log
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.DailyVerseRef
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.io.IOException

/**
 * Client for OurManna's verse-of-the-day endpoint — used only for the Home screen's
 * daily verse. Returns a [DailyVerseRef] (not verse text): the reference is resolved
 * against whatever translation the user has selected, via [BibleBooks], so the daily
 * verse always shows in the user's own language/version rather than OurManna's NIV.
 *
 * `order=daily` changes at about **00:00 UTC**, not at the phone's midnight (measured by logging
 * it every 5 minutes on 2026-11-14/15: Ecclesiastes 5:15 until 23:57 UTC, Psalm 119:165 from
 * then on). So a phone east of UTC fetching just after its own midnight still gets the verse it
 * was served the day before; [com.application.bibleapp.data.repository.verseToKeepForToday]
 * keeps that from showing two days running (#298).
 */
class OurMannaBibleDataSource(
    private val baseUrl: String = "https://beta.ourmanna.com/api/v1/get",
    private val client: HttpClient = HttpClientProvider.client
) : DailyVerseDataSource {

    override suspend fun getDailyVerse(): DailyVerseRef {
        val url = "$baseUrl?format=json&order=daily"
        // Shorter than HttpClientProvider's default 15s: this is decorative Home-screen
        // content, not a real download — it should fail fast into the local fallback
        // rather than hold up the app waiting on a slow response.
        val response = client.get(url) {
            timeout { requestTimeoutMillis = DAILY_VERSE_TIMEOUT_MS }
        }

        if (response.status != HttpStatusCode.OK) {
            throw IOException("Failed to load daily verse: server returned ${response.status}")
        }

        val dto: OurMannaResponseDto = response.body()
        return parseReference(dto.verse.details.reference)
    }

    // internal (not private) so unit tests can exercise the parsing directly; still
    // outside DailyVerseDataSource's public contract.
    internal fun parseReference(rawReference: String): DailyVerseRef {
        val match = REFERENCE_PATTERN.matchEntire(rawReference)
            ?: throw IllegalArgumentException("Invalid reference: $rawReference")

        val book = match.groupValues[1]
        val chapter = match.groupValues[2].toInt()
        val startVerse = match.groupValues[3].toInt()
        val endVerse = match.groupValues[4].takeIf { it.isNotEmpty() }?.toInt()

        val bookId = BibleBooks.getBookByName(book)?.id
            ?: throw IllegalArgumentException("Unknown Bible book: $book")

        return DailyVerseRef(
            bookId = bookId,
            chapter = chapter,
            startVerse = startVerse,
            endVerse = endVerse
        )
    }

    private companion object {
        const val DAILY_VERSE_TIMEOUT_MS = 5_000L

        // e.g. "John 3:16" or "John 3:20-21" — book names can contain a leading
        // number ("1 John") so the book group is matched non-greedily up to the
        // first " <digits>:<digits>" it finds.
        val REFERENCE_PATTERN = Regex("""^(.+?)\s+(\d+):(\d+)(?:-(\d+))?$""")
    }
}