package com.application.bibleapp.data.remote

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * Every way a Bible Helper request can end (#127), so the chat screen (#128) has one `when` with
 * one message per case instead of status codes. The failures are also [AiReportResult]s.
 */
sealed interface AiAskResult {
    data class Answer(
        val answer: String,
        val crisis: Boolean,
        val remainingToday: Int?,
        val promptVersion: String?
    ) : AiAskResult

    sealed interface Failure : AiAskResult, AiReportResult

    /** This user has asked today's 15 questions. [resetsAtMillis] is the next 00:00 UTC. */
    data class DailyLimit(val resetsAtMillis: Long) : Failure

    /** Cloudflare's free daily quota is used up for everyone, until [resetsAtMillis] (00:00 UTC). */
    data class Resting(val resetsAtMillis: Long) : Failure

    /** More than 5 questions in a minute: "Please wait a moment". */
    data object RateLimited : Failure

    /** The AI failed or took too long, or something else went wrong on the way ("Try again"). */
    data object Unavailable : Failure

    /** The phone couldn't reach the server. */
    data object Offline : Failure

    /** Not signed in, or the session couldn't be refreshed: the sign-in nudge. */
    data object SignedOut : Failure
}

/** How reporting an answer (#130) ended: [Sent], or one of [AiAskResult.Failure]. */
sealed interface AiReportResult {
    data object Sent : AiReportResult
}

/**
 * `POST /api/v1/ai/ask` and `POST /api/v1/ai/reports` (docs/ai_helper_plan.md section 5, #127).
 * Uses the shared signed-in client, whose Auth plugin attaches the token and refreshes it on a 401
 * by itself — so a 401 that still reaches us means the session is gone. [client] and [now] are
 * swappable for tests (MockEngine, a fixed clock).
 */
class AiHelperApi(
    private val client: () -> HttpClient = { HttpClientProvider.client },
    private val baseUrl: String = HttpClientProvider.BASE_URL,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.w(TAG, it) }
) {

    suspend fun ask(request: AiAskRequestDto): AiAskResult = try {
        val response = client().post("$baseUrl/ai/ask") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.isSuccess()) {
            val body = response.body<AiAskResponseDto>()
            AiAskResult.Answer(body.answer, body.crisis, body.remainingToday, body.promptVersion)
        } else {
            failureFor(response)
        }
    } catch (e: Exception) {
        failureFor(e, "ask")
    }

    suspend fun report(request: AiReportRequestDto): AiReportResult = try {
        val response = client().post("$baseUrl/ai/reports") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.isSuccess()) AiReportResult.Sent else failureFor(response)
    } catch (e: Exception) {
        failureFor(e, "report")
    }

    /** A non-2xx answer from the server, by status and the error body's `code`. */
    private suspend fun failureFor(response: HttpResponse): AiAskResult.Failure {
        val code = errorCode(response)
        return when (response.status) {
            HttpStatusCode.Unauthorized -> AiAskResult.SignedOut
            HttpStatusCode.TooManyRequests ->
                if (code == CODE_DAILY_LIMIT) AiAskResult.DailyLimit(nextUtcMidnight()) else AiAskResult.RateLimited
            HttpStatusCode.ServiceUnavailable ->
                if (code == CODE_RESTING) AiAskResult.Resting(nextUtcMidnight()) else AiAskResult.Unavailable
            else -> {
                // A 400 VALIDATION_ERROR is our own bug (the app trims to the contract's sizes), not
                // something the person can fix, so it gets the same "Try again" as any other.
                log("AI request rejected: ${response.status.value} $code")
                AiAskResult.Unavailable
            }
        }
    }

    /** A request that never got an answer: the phone's connection ([AiAskResult.Offline]) unless
     *  the server took the request and didn't answer in time — Render waking up, or the AI slow. */
    private fun failureFor(e: Exception, what: String): AiAskResult.Failure {
        if (e is CancellationException) throw e
        log("AI $what failed: ${e.javaClass.simpleName}: ${e.message}")
        return when {
            isServerResponseTimeout(e) -> AiAskResult.Unavailable
            isRetriableNetworkError(e) -> AiAskResult.Offline
            // e.g. a 200 whose body isn't an answer
            else -> AiAskResult.Unavailable
        }
    }

    private suspend fun errorCode(response: HttpResponse): String? = try {
        HttpClientProvider.json.decodeFromString(BackendErrorDto.serializer(), response.bodyAsText()).code
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null // no body (Ktor's rate limiter) or not JSON
    }

    /** The daily limits reset at 00:00 UTC (plan section 5); the screen shows it in local time. */
    private fun nextUtcMidnight(): Long = (now() / DAY_MILLIS + 1) * DAY_MILLIS

    private companion object {
        const val TAG = "AiHelperApi"
        const val CODE_DAILY_LIMIT = "AI_DAILY_LIMIT"
        const val CODE_RESTING = "AI_RESTING"
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
