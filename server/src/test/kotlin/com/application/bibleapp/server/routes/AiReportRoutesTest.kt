package com.application.bibleapp.server.routes

import com.application.bibleapp.server.db.tables.AiReports
import com.application.bibleapp.server.models.AiReportRequest
import com.application.bibleapp.server.models.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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
import kotlin.test.assertTrue

/** POST /api/v1/ai/reports (#125). */
class AiReportRoutesTest {

    /** A question no other test or run uses, to find this test's report among the others. */
    private fun report(
        question: String = "Is baptism required for salvation? ${UUID.randomUUID()}",
        reason: String = "WRONG_VERSE",
        comment: String? = "The verse cited is about something else."
    ) = AiReportRequest(
        question = question,
        answer = "No; salvation is by grace through faith. [[EPH 2:8-9]]",
        reason = reason,
        comment = comment,
        language = "en",
        promptVersion = "1"
    )

    private suspend fun HttpClient.sendReport(user: TestUser?, request: AiReportRequest): HttpResponse =
        post("/api/v1/ai/reports") {
            contentType(ContentType.Application.Json)
            user?.let { bearerAuth(it.auth.accessToken) }
            setBody(request)
        }

    private fun storedWith(question: String) = transaction {
        AiReports.selectAll().where { AiReports.question eq question }.toList()
    }

    @Test
    fun withoutATokenTheAnswerIs401() = testApplication {
        val client = startWithFakeAi()

        assertEquals(HttpStatusCode.Unauthorized, client.sendReport(user = null, report()).status)
    }

    @Test
    fun aReportIsStoredWithoutWhoSentIt() = testApplication {
        val clock = MutableClock()
        val client = startWithFakeAi(clock = clock)
        val user = client.registerTestUser()
        val request = report()

        assertEquals(HttpStatusCode.NoContent, client.sendReport(user, request).status)

        val row = storedWith(request.question).single()
        assertEquals("WRONG_VERSE", row[AiReports.reason])
        assertEquals(request.answer, row[AiReports.answer])
        assertEquals(request.comment, row[AiReports.comment])
        assertEquals("en", row[AiReports.language])
        assertEquals("1", row[AiReports.promptVersion])
        // The table has no column that could say who sent it.
        val columns = transaction {
            TransactionManager.current().exec(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'ai_reports'"
            ) { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }!!
        }
        assertFalse(columns.any { "user" in it }, columns.toString())
        assertFalse(row.toString().contains(user.auth.userId))
        transaction { AiReports.deleteWhere { AiReports.question eq request.question } }
    }

    @Test
    fun anInvalidReportAnswers400() = testApplication {
        val client = startWithFakeAi()
        val user = client.registerTestUser()

        val invalid = mapOf(
            "an unknown reason" to report(reason = "BORING"),
            "a blank question" to report(question = " "),
            "a long question" to report(question = "a".repeat(1_001)),
            "a long answer" to report().copy(answer = "a".repeat(4_001)),
            "a long comment" to report(comment = "a".repeat(501)),
            "an unknown language" to report().copy(language = "xx")
        )
        for ((case, request) in invalid) {
            assertTrue(validateReport(request).isNotEmpty(), case)
        }
        assertEquals(emptyMap(), validateReport(report(comment = null)))
        assertEquals(emptyMap(), validateReport(report(question = "a".repeat(1_000), comment = "a".repeat(500)).copy(answer = "a".repeat(4_000))))

        val response = client.sendReport(user, report(reason = "BORING"))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("VALIDATION_ERROR", error.code)
        assertTrue("reason" in error.fieldErrors.orEmpty())
    }

    @Test
    fun reportsOlderThan90DaysAreRemovedWhenANewOneArrives() = testApplication {
        val clock = MutableClock()
        val client = startWithFakeAi(clock = clock)
        val user = client.registerTestUser()
        val old = report()
        val recent = report()
        transaction {
            for ((request, age) in listOf(old to Duration.ofDays(91), recent to Duration.ofDays(89))) {
                AiReports.insert {
                    it[createdAt] = clock.now.minus(age)
                    it[reason] = request.reason
                    it[question] = request.question
                    it[answer] = request.answer
                    it[language] = request.language
                }
            }
        }
        val new = report()

        assertEquals(HttpStatusCode.NoContent, client.sendReport(user, new).status)

        assertTrue(storedWith(old.question).isEmpty())
        assertEquals(1, storedWith(recent.question).size)
        assertEquals(1, storedWith(new.question).size)
        transaction { for (q in listOf(recent.question, new.question)) AiReports.deleteWhere { AiReports.question eq q } }
    }

    @Test
    fun theEleventhReportInAnHourIsRateLimited() = testApplication {
        val client = startWithFakeAi()
        val user = client.registerTestUser()
        val sent = List(10) { report() }

        for (request in sent) assertEquals(HttpStatusCode.NoContent, client.sendReport(user, request).status)
        assertEquals(HttpStatusCode.TooManyRequests, client.sendReport(user, report()).status)
        transaction { for (request in sent) AiReports.deleteWhere { AiReports.question eq request.question } }
    }
}
