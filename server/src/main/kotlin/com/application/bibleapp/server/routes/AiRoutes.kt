package com.application.bibleapp.server.routes

import com.application.bibleapp.server.ai.AiCompletion
import com.application.bibleapp.server.ai.AiMessage
import com.application.bibleapp.server.ai.AiProvider
import com.application.bibleapp.server.ai.AiProviderException
import com.application.bibleapp.server.ai.AiRole
import com.application.bibleapp.server.ai.Busy
import com.application.bibleapp.server.ai.PassageText
import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.StarterQuestions
import com.application.bibleapp.server.ai.SystemPrompt
import com.application.bibleapp.server.ai.TopicGuideMatcher
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.ai.UsfmBookCodes
import com.application.bibleapp.server.auth.currentUserId
import com.application.bibleapp.server.db.dbQuery
import com.application.bibleapp.server.db.tables.AiDailyUsage
import com.application.bibleapp.server.db.tables.AiReports
import com.application.bibleapp.server.db.tables.AiStarterAnswers
import com.application.bibleapp.server.models.AiAskRequest
import com.application.bibleapp.server.models.AiAskResponse
import com.application.bibleapp.server.models.AiReportRequest
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.plugins.AI_RATE_LIMIT
import com.application.bibleapp.server.plugins.AI_REPORT_RATE_LIMIT
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
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
internal const val CRISIS_MARKER = "[[CRISIS]]"
private const val MAX_PASSAGE_CHARS = 2_000

/** A USFM reference within one chapter: "JHN 3", "JHN 3:16", "JHN 3:16-18" or "JHN 3:16,18-20". */
private val PASSAGE_REFERENCE = Regex("""([0-9A-Z]{3}) [1-9]\d{0,2}(?::[1-9]\d{0,2}(?:-[1-9]\d{0,2})?(?:,[1-9]\d{0,2}(?:-[1-9]\d{0,2})?)*)?""")

/** A version's name as the app shows it ("NASB 2020", "LBLA"), which goes in the instructions
 *  (#383): letters, digits, spaces, dots and dashes only, so it can't carry instructions of its own. */
private val PASSAGE_VERSION = Regex("""[\p{L}\p{N}][\p{L}\p{N} .\-]{0,19}""")

private val REPORT_REASONS =setOf("WRONG_TEACHING", "WRONG_VERSE", "HARMFUL", "OTHER")
private const val MAX_REPORT_QUESTION_CHARS = 1_000
private const val MAX_REPORT_ANSWER_CHARS = 4_000
private const val MAX_REPORT_COMMENT_CHARS = 500
private const val MAX_PROMPT_VERSION_CHARS = 20
/** The privacy policy's 90 days. Also used by db/RetentionCleanup.kt, for when no new report comes in (#223). */
internal val REPORT_RETENTION: Duration = Duration.ofDays(90)

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
    request.passage?.let { passage ->
        val code = PASSAGE_REFERENCE.matchEntire(passage.reference)?.groupValues?.get(1)
        val passageError = when {
            code == null || code !in UsfmBookCodes.codes -> "passage reference must be a USFM reference such as JHN 3:16-18"
            passage.text != null && passage.text.isBlank() -> "passage text must not be blank"
            passage.text != null && passage.text.length > MAX_PASSAGE_CHARS -> "passage text must be at most $MAX_PASSAGE_CHARS characters"
            passage.version != null && !PASSAGE_VERSION.matches(passage.version) ->
                "passage version must be a short version name such as NASB 2020"
            else -> null
        }
        if (passageError != null) errors["passage"] = passageError
    }
    if (request.language !in SystemPrompt.languageNames) {
        errors["language"] = "language must be one of ${SystemPrompt.languageNames.keys.joinToString(", ")}"
    }
    request.starter?.let { starter ->
        val starterError = when {
            starter !in StarterQuestions.ids -> "starter must be one of ${StarterQuestions.ids.joinToString(", ")}"
            request.messages.size != 1 -> "a starter is only the first question of a conversation"
            request.passage != null -> "a starter has no passage"
            else -> null
        }
        if (starterError != null) errors["starter"] = starterError
    }
    return errors
}

/**
 * The stored answer to [starter] in [language] for instructions [version] (#284), or null. A
 * starter with a topic guide is stored under the guides' version (#319), so turning the guides on
 * or off never serves an answer the other instructions wrote.
 */
private suspend fun readyStarterAnswer(starter: String, language: String, version: String): String? = dbQuery {
    AiStarterAnswers.selectAll()
        .where {
            (AiStarterAnswers.starter eq starter) and (AiStarterAnswers.language eq language) and
                (AiStarterAnswers.promptVersion eq version)
        }
        .singleOrNull()?.get(AiStarterAnswers.answer)
}

/** Stores [answer] as the ready one; when two people asked at once, the first stored stays. */
private suspend fun storeStarterAnswer(starter: String, language: String, version: String, answer: String, now: java.time.Instant) = dbQuery {
    AiStarterAnswers.insertIgnore {
        it[AiStarterAnswers.starter] = starter
        it[AiStarterAnswers.language] = language
        it[promptVersion] = version
        it[AiStarterAnswers.answer] = answer
        it[createdAt] = now
    }
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
private suspend fun reserveAnswer(userId: UUID, day: LocalDate): Int? = dbQuery {
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

/** How many answers [userId] has had on the UTC day [clock] is in: what the `ai` rate limit
 *  reads to let someone who's out of questions hear so (#271). */
internal suspend fun aiAnswersUsedToday(userId: UUID, clock: Clock): Int = dbQuery {
    val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    AiDailyUsage.selectAll()
        .where { (AiDailyUsage.userId eq userId) and (AiDailyUsage.day eq today) }
        .singleOrNull()?.get(AiDailyUsage.count) ?: 0
}

/** Gives back an answer taken by [reserveAnswer] when none came: only answers count. */
private suspend fun releaseAnswer(userId: UUID, day: LocalDate) = dbQuery {
    AiDailyUsage.update({ (AiDailyUsage.userId eq userId) and (AiDailyUsage.day eq day) and (AiDailyUsage.count greater 0) }) {
        it[AiDailyUsage.count] = AiDailyUsage.count - 1
    }
}

/** The error the app gets when the AI gave no answer: resting until midnight UTC, busy for a few minutes, or try again. */
internal fun errorFor(e: AiProviderException): ErrorResponse = when (e) {
    is QuotaExhausted -> ErrorResponse("AI_RESTING", "The Bible Helper is resting until 00:00 UTC")
    is Busy -> ErrorResponse("AI_BUSY", "The Bible Helper is busy, try again in a few minutes")
    is Unavailable -> ErrorResponse("AI_UNAVAILABLE", "Couldn't get an answer")
}

/**
 * Busy is a 429 (#344): every app version shows a 429 that isn't AI_DAILY_LIMIT as "Please wait a
 * moment", which is what it is. The others are 503s, as before.
 */
internal fun statusFor(e: AiProviderException): HttpStatusCode =
    if (e is Busy) HttpStatusCode.TooManyRequests else HttpStatusCode.ServiceUnavailable

private suspend fun ApplicationCall.respondNoAnswer(e: AiProviderException) =
    respond(statusFor(e), errorFor(e))

/**
 * Gets the AI's answer to [conversation] and sends [finish]'s response: all at once, or, when
 * [streaming], piece by piece as it's written (#285, [respondAnswerStream]). [onNoAnswer] runs
 * when there's no answer, before the error is sent.
 */
private suspend fun ApplicationCall.respondAnswer(
    provider: AiProvider,
    conversation: List<AiMessage>,
    streaming: Boolean,
    onNoAnswer: suspend () -> Unit,
    finish: suspend (AiCompletion) -> AiAskResponse
) {
    if (streaming) {
        respondAnswerStream(provider, conversation, AI_MAX_TOKENS, onNoAnswer, respondError = { respondNoAnswer(it) }, finish = finish)
        return
    }
    val completion = try {
        // Streamed from the provider even when the app gets it all at once, so a stalled answer
        // is given up on and asked again after 12 s, not waited for 45 s (#345).
        provider.stream(conversation, AI_MAX_TOKENS) { }
    } catch (e: Throwable) {
        // Also when the app hung up and this coroutine is cancelled: the count still goes back (#346).
        withContext(NonCancellable) { onNoAnswer() }
        if (e is AiProviderException) respondNoAnswer(e) else throw e
        return
    }
    respond(HttpStatusCode.OK, finish(completion))
}

/** A ready answer as a stream (#285): only its `done` event, since there's nothing to wait for. */
private suspend fun ApplicationCall.respondReady(response: AiAskResponse, streaming: Boolean) {
    if (!streaming) {
        respond(HttpStatusCode.OK, response)
        return
    }
    respondBytesWriter(contentType = ContentType.Text.EventStream) {
        writeStringUtf8("event: done\ndata: ${Json.encodeToString(response)}\n\n")
        flush()
    }
}

/**
 * `POST /ai/ask`, and with [streaming] `POST /ai/ask/stream` (#285): the same checks, limits and
 * answer, the second sent as it's written. With [guides] (#319), a question without a passage
 * gets the topic guides its latest message matches.
 */
private suspend fun ApplicationCall.answerQuestion(aiProvider: AiProvider?, clock: Clock, streaming: Boolean, guides: TopicGuideMatcher?) {
    val userId = currentUserId()
    val request = try {
        receive<AiAskRequest>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "The request body isn't a valid question"))
        return
    }
    val fieldErrors = validateAsk(request)
    if (fieldErrors.isNotEmpty()) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Invalid question", fieldErrors))
        return
    }

    request.starter?.let { starter ->
        // A starter question (#284): the same ready answer for everyone, which doesn't use a
        // daily question, so it works after the day's 15 too.
        val remaining = AI_DAILY_LIMIT - aiAnswersUsedToday(userId, clock)
        // Asked in the server's own words, so only these questions are ever stored.
        val question = StarterQuestions.question(starter, request.language)!!
        val matched = guides?.matchStarter("$starter/${request.language}", question).orEmpty()
        val version = SystemPrompt.versionFor(matched)
        readyStarterAnswer(starter, request.language, version)?.let { ready ->
            respondReady(AiAskResponse(ready, crisis = false, remainingToday = remaining, promptVersion = version), streaming)
            return
        }
        if (aiProvider == null) {
            respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI_UNAVAILABLE", "The Bible Helper isn't set up on this server"))
            return
        }
        val instructions = SystemPrompt.withGuides(request.language, matched)
        val conversation = listOf(AiMessage(AiRole.SYSTEM, instructions), AiMessage(AiRole.USER, question))
        respondAnswer(aiProvider, conversation, streaming, onNoAnswer = {}) { completion ->
            val (text, crisis) = splitCrisis(completion.text)
            // Only a whole, ordinary answer is kept for everyone.
            if (!crisis && !completion.cutOff && text.isNotBlank()) {
                storeStarterAnswer(starter, request.language, version, text, clock.instant())
            }
            val answer = if (completion.cutOff) "$text …" else text
            AiAskResponse(answer, crisis, remaining, version)
        }
        return
    }

    if (aiProvider == null) {
        respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("AI_UNAVAILABLE", "The Bible Helper isn't set up on this server"))
        return
    }
    val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    // Topic guides (#319): one embedding of the latest question, matched while the daily count is
    // taken (#347), and cancelled, never used, when the question is refused. A question about a
    // selected passage has its passage instead.
    val passage = request.passage
    // A licensed version's passage comes without its text (#383): quote the BSB instead, before
    // the question is counted. No BSB verse there means the reference names verses that don't exist.
    val passageText = when {
        passage == null -> null
        passage.text != null -> passage.text
        else -> PassageText.bsb(passage.reference, MAX_PASSAGE_CHARS) ?: run {
            respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("VALIDATION_ERROR", "Invalid question", mapOf("passage" to "passage reference names no verses"))
            )
            return
        }
    }
    val reserved = coroutineScope {
        val matching = if (guides != null && passage == null) async { guides.match(request.messages.last().content) } else null
        val used = reserveAnswer(userId, today)
        if (used == null) {
            matching?.cancel()
            null
        } else {
            used to matching?.await().orEmpty()
        }
    }
    if (reserved == null) {
        respond(HttpStatusCode.TooManyRequests, ErrorResponse("AI_DAILY_LIMIT", "Today's $AI_DAILY_LIMIT questions are used up"))
        return
    }
    val (used, matched) = reserved
    val version = SystemPrompt.versionFor(matched)
    val instructions = if (passage != null && passageText != null) {
        // A version is named only for a quoted BSB, so the model knows the reader's wording differs.
        val otherVersion = if (passage.text == null) passage.version ?: "" else null
        SystemPrompt.forLanguage(request.language, passage.reference, passageText, otherVersion)
    } else {
        SystemPrompt.withGuides(request.language, matched)
    }
    val conversation = listOf(AiMessage(AiRole.SYSTEM, instructions)) +
        request.messages.map { AiMessage(if (it.role == "user") AiRole.USER else AiRole.ASSISTANT, it.content) }
    // No answer, no count: every failure gives the reserved answer back, and so does a stream
    // that broke partway (#285).
    respondAnswer(aiProvider, conversation, streaming, onNoAnswer = { releaseAnswer(userId, today) }) { completion ->
        var (answer, crisis) = splitCrisis(completion.text)
        var counted = used
        if (answer.isBlank()) {
            // Only the crisis marker came back: the help card still shows, and it isn't counted.
            releaseAnswer(userId, today)
            counted -= 1
        } else if (completion.cutOff) {
            // It ran out of tokens mid-sentence; the ellipsis shows the person it's unfinished.
            answer = "$answer …"
        }
        AiAskResponse(answer = answer, crisis = crisis, remainingToday = AI_DAILY_LIMIT - counted, promptVersion = version)
    }
}

/**
 * The Bible Helper (#124). Signed in only, 5 questions a minute per user (the `ai` rate-limit
 * zone, which doesn't count someone whose day is used up, #271) and [AI_DAILY_LIMIT] answers
 * per UTC day. Reports of bad answers (#125) are stored
 * without the user's ID, 10 an hour per user, and kept [REPORT_RETENTION]. [aiProvider] is null when the AI_* settings
 * aren't set, and every question then answers AI_UNAVAILABLE. [clock] decides the day; tests
 * move it forward. `/ai/ask/stream` sends the answer as it's written (#285); `/ai/ask` stays for
 * app versions before it. [guides] is null unless AI_TOPIC_GUIDES is on and search by meaning is
 * set up (#319); then each question gets the topic guides it matches, and its answer reports
 * [SystemPrompt.GUIDES_VERSION].
 *
 * Question and answer text is never logged, here or in the provider (#124).
 */
fun Route.aiRoutes(aiProvider: AiProvider?, clock: Clock, guides: TopicGuideMatcher? = null) {
    // Reads the instruction files now, so a missing one stops the server from starting instead
    // of failing every question after a deploy that looked healthy.
    SystemPrompt.forLanguage("en")

    authenticate("auth-jwt") {
        rateLimit(AI_RATE_LIMIT) {
            post("/ai/ask") { call.answerQuestion(aiProvider, clock, streaming = false, guides) }
            post("/ai/ask/stream") { call.answerQuestion(aiProvider, clock, streaming = true, guides) }
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
                dbQuery {
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
