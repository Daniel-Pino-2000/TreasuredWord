package com.application.bibleapp.server.routes

import com.application.bibleapp.server.ai.AiMessage
import com.application.bibleapp.server.ai.AiProvider
import com.application.bibleapp.server.ai.AiRole
import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.SystemPrompt
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.auth.currentUserId
import com.application.bibleapp.server.db.tables.AiDailyUsage
import com.application.bibleapp.server.db.tables.AiReports
import com.application.bibleapp.server.models.AiAskRequest
import com.application.bibleapp.server.models.AiAskResponse
import com.application.bibleapp.server.models.AiReportRequest
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.plugins.AI_RATE_LIMIT
import com.application.bibleapp.server.plugins.AI_REPORT_RATE_LIMIT
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsertReturning
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Answers a user can get per UTC day. */
internal const val AI_DAILY_LIMIT = 15

/** Room for an answer: #122's longest was 430 tokens with thinking off, the instructions ask for about 250 words. */
internal const val AI_MAX_TOKENS = 700

private const val MAX_MESSAGES = 10
private const val MAX_USER_CHARS = 1_000
private const val MAX_ASSISTANT_CHARS = 4_000
private const val MAX_TOTAL_CHARS = 8_000
private const val CRISIS_MARKER = "[[CRISIS]]"

private val REPORT_REASONS = setOf("WRONG_TEACHING", "WRONG_VERSE", "HARMFUL", "OTHER")
private const val MAX_REPORT_QUESTION_CHARS = 1_000
private const val MAX_REPORT_ANSWER_CHARS = 4_000
private const val MAX_REPORT_COMMENT_CHARS = 500
private const val MAX_PROMPT_VERSION_CHARS = 20
private val REPORT_RETENTION: Duration = Duration.ofDays(90)

/**
 * The plan's section 5 rules (docs/api_contract.md): which fields are wrong and why, or an
 * empty map. Only "user" and "assistant" roles exist, so the app can never send instructions of
 * its own; the instructions are always the server's.
 */
internal fun validateAsk(request: AiAskRequest): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    val messages = request.messages
    val messagesError = when {
        messages.isEmpty() -> "messages must include at least one entry"
        messages.size > MAX_MESSAGES -> "messages must include at most $MAX_MESSAGES entries"
        messages.any { it.role != "user" && it.role != "assistant" } -> "role must be \"user\" or \"assistant\""
        messages.any { it.content.isBlank() } -> "content must not be blank"
        messages.zipWithNext().any { (a, b) -> a.role == b.role } -> "messages must alternate between user and assistant"
        messages.last().role != "user" -> "the last message must be from the user"
        messages.any { it.role == "user" && it.content.length > MAX_USER_CHARS } ->
            "a user message must be at most $MAX_USER_CHARS characters"
        messages.any { it.role == "assistant" && it.content.length > MAX_ASSISTANT_CHARS } ->
            "an assistant message must be at most $MAX_ASSISTANT_CHARS characters"
        messages.sumOf { it.content.length } > MAX_TOTAL_CHARS -> "messages must be at most $MAX_TOTAL_CHARS characters in all"
        else -> null
    }
    if (messagesError != null) errors["messages"] = messagesError
    if (request.language !in SystemPrompt.languageNames) {
        errors["language"] = "language must be one of ${SystemPrompt.languageNames.keys.joinToString(", ")}"
    }
    return errors
}

/** #125's rules for a report: which fields are wrong and why, or an empty map. */
internal fun validateReport(request: AiReportRequest): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (request.reason !in REPORT_REASONS) errors["reason"] = "reason must be one of ${REPORT_REASONS.joinToString(", ")}"
    when {
        request.question.isBlank() -> errors["question"] = "question must not be blank"
        request.question.length > MAX_REPORT_QUESTION_CHARS -> errors["question"] = "question must be at most $MAX_REPORT_QUESTION_CHARS characters"
    }
    when {
        request.answer.isBlank() -> errors["answer"] = "answer must not be blank"
        request.answer.length > MAX_REPORT_ANSWER_CHARS -> errors["answer"] = "answer must be at most $MAX_REPORT_ANSWER_CHARS characters"
    }
    if ((request.comment?.length ?: 0) > MAX_REPORT_COMMENT_CHARS) {
        errors["comment"] = "comment must be at most $MAX_REPORT_COMMENT_CHARS characters"
    }
    if (request.language !in SystemPrompt.languageNames) {
        errors["language"] = "language must be one of ${SystemPrompt.languageNames.keys.joinToString(", ")}"
    }
    if ((request.promptVersion?.length ?: 0) > MAX_PROMPT_VERSION_CHARS) {
        errors["promptVersion"] = "promptVersion must be at most $MAX_PROMPT_VERSION_CHARS characters"
    }
    return errors
}

/**
 * The answer without the crisis marker, and whether it had one. The instructions put it first;
 * it's removed wherever it appears, so the app never shows it as raw brackets.
 */
internal fun splitCrisis(text: String): Pair<String, Boolean> =
    if (CRISIS_MARKER in text) text.replace(CRISIS_MARKER, "").trim() to true else text to false

/**
 * Takes one of the user's answers for [day] before asking, and returns the new count, or null
 * when all [AI_DAILY_LIMIT] are used. One statement (`INSERT ... ON CONFLICT DO UPDATE ... WHERE
 * count < 15 RETURNING count`), so questions sent at the same moment can't all slip past the
 * limit the way a read followed by a later write would let them.
 */
private fun reserveAnswer(userId: UUID, day: LocalDate): Int? = transaction {
    AiDailyUsage.upsertReturning(
        returning = listOf(AiDailyUsage.count),
        onUpdate = { it[AiDailyUsage.count] = AiDailyUsage.count + 1 },
        where = { AiDailyUsage.count less AI_DAILY_LIMIT }
    ) {
        it[AiDailyUsage.userId] = userId
        it[AiDailyUsage.day] = day
        it[AiDailyUsage.count] = 1
    }.singleOrNull()?.get(AiDailyUsage.count)
}

/** Gives back an answer taken by [reserveAnswer] when none came: only answers count. */
private fun releaseAnswer(userId: UUID, day: LocalDate) = transaction {
    AiDailyUsage.update({ (AiDailyUsage.userId eq userId) and (AiDailyUsage.day eq day) and (AiDailyUsage.count greater 0) }) {
        it[AiDailyUsage.count] = AiDailyUsage.count - 1
    }
}

/**
 * The Bible Helper (#124). Signed in only, 5 questions a minute per user (the `ai` rate-limit
 * zone) and [AI_DAILY_LIMIT] answers per UTC day. Reports of bad answers (#125) are stored
 * without the user's ID, 10 an hour per user, and kept [REPORT_RETENTION]. [aiProvider] is null when the AI_* settings
 * aren't set, and every question then answers AI_UNAVAILABLE. [clock] decides the day; tests
 * move it forward.
 *
 * Question and answer text is never logged, here or in the provider (#124).
 */
fun Route.aiRoutes(aiProvider: AiProvider?, clock: Clock) {
    // Reads the instruction files now, so a missing one stops the server from starting instead
    // of failing every question after a deploy that looked healthy.
    SystemPrompt.forLanguage("en")

    authenticate("auth-jwt") {
        rateLimit(AI_RATE_LIMIT) {
            post("/ai/ask") {
                val userId = call.currentUserId()
                val request = try {
                    call.receive<AiAskRequest>()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "The request body isn't a valid question"))
                    return@post
                }
                val fieldErrors = validateAsk(request)
                if (fieldErrors.isNotEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Invalid question", fieldErrors))
                    return@post
                }

                if (aiProvider == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI_UNAVAILABLE", "The Bible Helper isn't set up on this server"))
                    return@post
                }
                val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                var used = reserveAnswer(userId, today)
                if (used == null) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("AI_DAILY_LIMIT", "Today's $AI_DAILY_LIMIT questions are used up"))
                    return@post
                }

                val conversation = listOf(AiMessage(AiRole.SYSTEM, SystemPrompt.forLanguage(request.language))) +
                    request.messages.map { AiMessage(if (it.role == "user") AiRole.USER else AiRole.ASSISTANT, it.content) }
                // No answer, no count: every failure gives the reserved answer back.
                val completion = try {
                    aiProvider.complete(conversation, AI_MAX_TOKENS)
                } catch (e: Throwable) {
                    releaseAnswer(userId, today)
                    when (e) {
                        is QuotaExhausted -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI_RESTING", "The Bible Helper is resting until 00:00 UTC"))
                        is Unavailable -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI_UNAVAILABLE", "Couldn't get an answer"))
                        else -> throw e
                    }
                    return@post
                }

                var (answer, crisis) = splitCrisis(completion.text)
                if (answer.isBlank()) {
                    // Only the crisis marker came back: the help card still shows, and it isn't counted.
                    releaseAnswer(userId, today)
                    used -= 1
                } else if (completion.cutOff) {
                    // It ran out of tokens mid-sentence; the ellipsis shows the person it's unfinished.
                    answer = "$answer …"
                }
                call.respond(
                    HttpStatusCode.OK,
                    AiAskResponse(
                        answer = answer,
                        crisis = crisis,
                        remainingToday = AI_DAILY_LIMIT - used,
                        promptVersion = SystemPrompt.PROMPT_VERSION
                    )
                )
            }
        }

        rateLimit(AI_REPORT_RATE_LIMIT) {
            post("/ai/reports") {
                val request = try {
                    call.receive<AiReportRequest>()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "The request body isn't a valid report"))
                    return@post
                }
                val fieldErrors = validateReport(request)
                if (fieldErrors.isNotEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Invalid report", fieldErrors))
                    return@post
                }
                val now = clock.instant()
                transaction {
                    AiReports.insert {
                        it[createdAt] = now
                        it[reason] = request.reason
                        it[question] = request.question
                        it[answer] = request.answer
                        it[comment] = request.comment?.takeIf { comment -> comment.isNotBlank() }
                        it[language] = request.language
                        it[promptVersion] = request.promptVersion
                    }
                    // Keeping reports 90 days: a cleanup on each insert needs no scheduler.
                    AiReports.deleteWhere { createdAt less now.minus(REPORT_RETENTION) }
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
