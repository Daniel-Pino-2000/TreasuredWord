package com.application.bibleapp.server.db

import com.application.bibleapp.server.db.tables.AiDailyUsage
import com.application.bibleapp.server.db.tables.AiReports
import com.application.bibleapp.server.db.tables.EmailCodePurpose
import com.application.bibleapp.server.db.tables.EmailCodes
import com.application.bibleapp.server.routes.registerTestUser
import com.application.bibleapp.server.routes.startWithFakeAi
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [deleteExpiredRows] keeps the privacy policy's deadlines without waiting for the next insert
 * (#223). `now` is the real time, not a fixed date: the server also runs the cleanup when it
 * starts, and would delete a "recent" row dated years ago before the test looks at it.
 */
class RetentionCleanupTest {

    @Test
    fun anEmailedCodeOlderThanADayIsDeletedEvenWithoutANewCode() = testApplication {
        val client = startWithFakeAi()
        val userId = UUID.fromString(client.registerTestUser().auth.userId)
        val now = Instant.now()
        transaction {
            for ((hash, age) in listOf("old" to Duration.ofDays(1).plusMinutes(1), "recent" to Duration.ofHours(23))) {
                EmailCodes.insert {
                    it[EmailCodes.userId] = userId
                    it[purpose] = EmailCodePurpose.VERIFY_EMAIL.dbValue
                    it[codeHash] = hash
                    it[expiresAt] = now.minus(age).plus(Duration.ofMinutes(15))
                    it[createdAt] = now.minus(age)
                }
            }
        }

        deleteExpiredRows(now)

        val left = transaction {
            EmailCodes.selectAll().where { EmailCodes.userId eq userId }.map { it[EmailCodes.codeHash] }
        }
        assertEquals(listOf("recent"), left)
    }

    @Test
    fun aReportOlderThan90DaysIsDeletedEvenWithoutANewReport() = testApplication {
        startWithFakeAi()
        val now = Instant.now()
        val old = "Old report ${UUID.randomUUID()}"
        val recent = "Recent report ${UUID.randomUUID()}"
        transaction {
            for ((question, age) in listOf(old to Duration.ofDays(91), recent to Duration.ofDays(89))) {
                AiReports.insert {
                    it[createdAt] = now.minus(age)
                    it[reason] = "OTHER"
                    it[AiReports.question] = question
                    it[answer] = "An answer."
                    it[language] = "en"
                }
            }
        }

        deleteExpiredRows(now)

        fun stored(question: String) = transaction { AiReports.selectAll().where { AiReports.question eq question }.count() }
        assertEquals(0, stored(old))
        assertEquals(1, stored(recent))
        transaction { AiReports.deleteWhere { AiReports.question eq recent } }
    }

    @Test
    fun dailyCountsFromBeforeYesterdayAreDeleted() = testApplication {
        val client = startWithFakeAi()
        val userId = UUID.fromString(client.registerTestUser().auth.userId)
        val now = Instant.now()
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        transaction {
            for (day in listOf(today.minusDays(2), today.minusDays(1), today)) {
                AiDailyUsage.insert {
                    it[AiDailyUsage.userId] = userId
                    it[AiDailyUsage.day] = day
                    it[count] = 3
                }
            }
        }

        deleteExpiredRows(now)

        val days = transaction {
            AiDailyUsage.selectAll().where { AiDailyUsage.userId eq userId }.map { it[AiDailyUsage.day] }.sorted()
        }
        assertEquals(listOf(today.minusDays(1), today), days)
        transaction { AiDailyUsage.deleteWhere { AiDailyUsage.userId eq userId } }
    }
}
