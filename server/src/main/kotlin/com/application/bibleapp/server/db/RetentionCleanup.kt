package com.application.bibleapp.server.db

import com.application.bibleapp.server.auth.EmailCodeStore
import com.application.bibleapp.server.db.tables.AiDailyUsage
import com.application.bibleapp.server.db.tables.AiReports
import com.application.bibleapp.server.db.tables.EmailCodes
import com.application.bibleapp.server.routes.REPORT_RETENTION
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours

/** How often [startRetentionCleanup] runs while the server is awake. */
private val CLEANUP_EVERY = 6.hours

/**
 * Deletes what the privacy policy says is gone by [now] (#223): emailed codes older than a day,
 * Bible Helper reports older than 90 days, and daily answer counts from before yesterday (UTC;
 * only today's counts toward the limit). The deletes on insert in EmailCodeStore and AiRoutes
 * stay, but they only run when the same table gets a new row, which may not happen for weeks.
 */
fun deleteExpiredRows(now: Instant): ExpiredRows = transaction {
    val yesterday = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(1)
    ExpiredRows(
        emailCodes = EmailCodes.deleteWhere { createdAt less now.minus(EmailCodeStore.KEEP_FOR) },
        aiReports = AiReports.deleteWhere { createdAt less now.minus(REPORT_RETENTION) },
        aiDailyUsage = AiDailyUsage.deleteWhere { day less yesterday }
    )
}

/** How many rows [deleteExpiredRows] deleted from each table. */
data class ExpiredRows(val emailCodes: Int, val aiReports: Int, val aiDailyUsage: Int) {
    val total get() = emailCodes + aiReports + aiDailyUsage
}

/**
 * Runs [deleteExpiredRows] now and then every [CLEANUP_EVERY] until the server stops (#223).
 * Render's free plan sleeps after 15 idle minutes and starts again on the next request, so the
 * run at startup does most of the work; the timer covers days the server stays awake. A failure
 * (Neon briefly unreachable, say) is logged and tried again next time, never thrown: it must
 * not stop the server. Call it after configureDatabases(), which connects the database.
 */
fun Application.startRetentionCleanup(clock: Clock) {
    // Exposed's transaction blocks its thread while it waits for the database: IO's threads are for that.
    launch(Dispatchers.IO) {
        while (true) {
            try {
                val deleted = deleteExpiredRows(clock.instant())
                if (deleted.total > 0) log.info("Retention cleanup deleted {}", deleted)
            } catch (e: CancellationException) {
                throw e // The server is stopping: let the coroutine end.
            } catch (e: Exception) {
                log.error("Retention cleanup failed; trying again in $CLEANUP_EVERY", e)
            }
            delay(CLEANUP_EVERY)
        }
    }
}
