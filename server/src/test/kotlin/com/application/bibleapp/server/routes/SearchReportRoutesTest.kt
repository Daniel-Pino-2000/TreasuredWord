package com.application.bibleapp.server.routes

import com.application.bibleapp.server.db.tables.SearchReports
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.SearchReportRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** POST /api/v1/search/reports (#402): searches whose results didn't help. */
class SearchReportRoutesTest {

    /** A query no other test or run uses, to find this test's report among the others. */
    private fun report(q: String = "worried about my job ${UUID.randomUUID().toString().take(8)}") = SearchReportRequest(
        q = q,
        language = "en",
        bibleVersion = "BSB",
        appVersion = "1.9",
        results = listOf("MAT 6:34", "PHP 4:6"),
        keyPassages = listOf("MAT 6:25-34", "PSA 73")
    )

    private suspend fun HttpClient.sendReport(request: SearchReportRequest, ip: String? = null, token: String? = null): HttpResponse =
        post("/api/v1/search/reports") {
            contentType(ContentType.Application.Json)
            ip?.let { header("X-Forwarded-For", it) }
            token?.let { bearerAuth(it) }
            setBody(request)
        }

    private fun storedWith(query: String) = transaction {
        SearchReports.selectAll().where { SearchReports.query eq query }.toList()
    }

    private fun deleteStored(queries: List<String>) = transaction {
        for (query in queries) SearchReports.deleteWhere { SearchReports.query eq query }
    }

    @Test
    fun aReportIsStoredWithoutSignInAndWithoutWhoSentIt() = testApplication {
        val client = startWithFakeAi()
        val request = report()

        assertEquals(HttpStatusCode.NoContent, client.sendReport(request, ip = "203.0.113.7").status)

        val row = storedWith(request.q).single()
        assertEquals("en", row[SearchReports.language])
        assertEquals("BSB", row[SearchReports.bibleVersion])
        assertEquals("1.9", row[SearchReports.appVersion])
        assertEquals("MAT 6:34, PHP 4:6", row[SearchReports.results])
        assertEquals("MAT 6:25-34, PSA 73", row[SearchReports.keyPassages])
        // The table has no column that could say who sent it, and the IP isn't anywhere in the row.
        val columns = transaction {
            TransactionManager.current().exec(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'search_reports'"
            ) { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }!!
        }
        assertFalse(columns.any { "user" in it || "ip" in it.split('_') }, columns.toString())
        assertFalse(row.toString().contains("203.0.113.7"))
        deleteStored(listOf(request.q))
    }

    @Test
    fun aSignedInReportIsStillStoredWithoutTheAccount() = testApplication {
        val client = startWithFakeAi()
        val user = client.registerTestUser()
        val request = report()

        assertEquals(HttpStatusCode.NoContent, client.sendReport(request, token = user.auth.accessToken).status)

        assertFalse(storedWith(request.q).single().toString().contains(user.auth.userId))
        deleteStored(listOf(request.q))
    }

    @Test
    fun aSearchWithNoResultsCanBeReported() = testApplication {
        val client = startWithFakeAi()
        val request = report().copy(appVersion = null, results = emptyList(), keyPassages = emptyList())

        assertEquals(HttpStatusCode.NoContent, client.sendReport(request).status)

        val row = storedWith(request.q).single()
        assertEquals("", row[SearchReports.results])
        assertEquals("", row[SearchReports.keyPassages])
        assertNull(row[SearchReports.appVersion])
        deleteStored(listOf(request.q))
    }

    @Test
    fun anInvalidReportAnswers400() = testApplication {
        val client = startWithFakeAi()

        val invalid = mapOf(
            "a blank query" to report(q = "  "),
            "a long query" to report(q = "a".repeat(201)),
            "an unknown language" to report().copy(language = "xx"),
            "no version" to report().copy(bibleVersion = ""),
            "a long version" to report().copy(bibleVersion = "a".repeat(41)),
            "a long app version" to report().copy(appVersion = "a".repeat(21)),
            "a verse's text" to report().copy(results = listOf("For God so loved the world")),
            "too many results" to report().copy(results = List(21) { "JHN 3:${it + 1}" }),
            "a lowercase book" to report().copy(keyPassages = listOf("psa 23"))
        )
        for ((case, request) in invalid) {
            assertTrue(validateSearchReport(request).isNotEmpty(), case)
        }
        assertEquals(emptyMap(), validateSearchReport(report()))
        assertEquals(emptyMap(), validateSearchReport(report(q = "a".repeat(200)).copy(results = List(20) { "1CO 13:${it + 1}" })))

        val response = client.sendReport(report().copy(results = listOf("not a reference")))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("VALIDATION_ERROR", error.code)
        assertTrue("results" in error.fieldErrors.orEmpty())
    }

    @Test
    fun reportsOlderThan90DaysAreRemovedWhenANewOneArrives() = testApplication {
        val clock = MutableClock()
        val client = startWithFakeAi(clock = clock)
        val old = report()
        val recent = report()
        transaction {
            for ((request, age) in listOf(old to Duration.ofDays(91), recent to Duration.ofDays(89))) {
                SearchReports.insert {
                    it[createdAt] = clock.now.minus(age)
                    it[query] = request.q
                    it[language] = request.language
                    it[bibleVersion] = request.bibleVersion
                    it[results] = ""
                    it[keyPassages] = ""
                }
            }
        }
        val new = report()

        assertEquals(HttpStatusCode.NoContent, client.sendReport(new).status)

        assertTrue(storedWith(old.q).isEmpty())
        assertEquals(1, storedWith(recent.q).size)
        assertEquals(1, storedWith(new.q).size)
        deleteStored(listOf(recent.q, new.q))
    }

    @Test
    fun theEleventhReportInAnHourFromOneIpIsRateLimited() = testApplication {
        val client = startWithFakeAi()
        val sent = List(10) { report() }

        for (request in sent) assertEquals(HttpStatusCode.NoContent, client.sendReport(request, ip = "198.51.100.1").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.sendReport(report(), ip = "198.51.100.1").status)
        // Another IP still gets through: the limit is per IP, not for the whole server (#162).
        val other = report()
        assertEquals(HttpStatusCode.NoContent, client.sendReport(other, ip = "198.51.100.2").status)
        deleteStored(sent.map { it.q } + other.q)
    }
}
