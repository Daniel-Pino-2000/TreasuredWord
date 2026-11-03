package com.application.bibleapp.server.routes

import com.application.bibleapp.server.ai.AiMessage
import com.application.bibleapp.server.ai.AiProvider
import com.application.bibleapp.server.ai.AiRole
import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.SystemPrompt
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.auth.currentUserId
import com.application.bibleapp.server.db.tables.AiDailyUsage
import com.application.bibleapp.server.models.AiAskRequest
import com.application.bibleapp.server.models.AiAskResponse
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.plugins.AI_RATE_LIMIT
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.Clock
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

/**
 * The answer without the crisis marker, and whether it had one. The instructions put it first;
 * it's removed wherever it appears, so the app never shows it as raw brackets.
 */
internal fun splitCrisis(text: String): Pair<String, Boolean> =
    if (CRISIS_MARKER in text) text.replace(CRISIS_MARKER, "").trim() to true else text to false

private fun usedOn(userId: UUID, day: LocalDate): Int = transaction {
    AiDailyUsage.selectAll()
        .where { (AiDailyUsage.userId eq userId) and (AiDailyUsage.day eq day) }
        .singleOrNull()?.get(AiDailyUsage.count) ?: 0
}

/** Adds one to the user's count for [day] and returns the new count. */
private fun countAnswer(userId: UUID, day: LocalDate): Int = transaction {
    AiDailyUsage.upsert(onUpdate = { it[AiDailyUsage.count] = AiDailyUsage.count + 1 }) {
        it[AiDailyUsage.userId] = userId
        it[AiDailyUsage.day] = day
        it[AiDailyUsage.count] = 1
    }
    usedOn(userId, day)
}

/**
 * The Bible Helper (#124). Signed in only, 5 questions a minute per user (the `ai` rate-limit
 * zone) and [AI_DAILY_LIMIT] answers per UTC day. [aiProvider] is null when the AI_* settings
 * aren't set, and every question then answers AI_UNAVAILABLE. [clock] decides the day; tests
 * move it forward.
 *
 * Question and answer text is never logged, here or in the provider (#124).
 */
fun Route.aiRoutes(aiProvider: AiProvider?, clock: Clock) {
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

                val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                if (usedOn(userId, today) >= AI_DAILY_LIMIT) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("AI_DAILY_LIMIT", "Today's $AI_DAILY_LIMIT questions are used up"))
                    return@post
                }
                if (aiProvider == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI_UNAVAILABLE", "The Bible Helper isn't set up on this server"))
                    return@post
                }

                val conversation = listOf(AiMessage(AiRole.SYSTEM, SystemPrompt.forLanguage(request.language))) +
                    request.messages.map { AiMessage(if (it.role == "user") AiRole.USER else AiRole.ASSISTANT, it.content) }
                // Neither failure counts toward the day's questions.
                val completion = try {
                    aiProvider.complete(conversation, AI_MAX_TOKENS)
                } catch (e: QuotaExhausted) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI_RESTING", "The Bible Helper is resting until 00:00 UTC"))
                    return@post
                } catch (e: Unavailable) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI_UNAVAILABLE", "Couldn't get an answer"))
                    return@post
                }

                val (answer, crisis) = splitCrisis(completion.text)
                val used = countAnswer(userId, today)
                call.respond(
                    HttpStatusCode.OK,
                    AiAskResponse(
                        answer = answer,
                        crisis = crisis,
                        remainingToday = (AI_DAILY_LIMIT - used).coerceAtLeast(0),
                        promptVersion = SystemPrompt.PROMPT_VERSION
                    )
                )
            }
        }
    }
}
