package com.application.bibleapp.server.routes

import com.application.bibleapp.server.ai.AiCompletion
import com.application.bibleapp.server.ai.AiMessage
import com.application.bibleapp.server.ai.AiProvider
import com.application.bibleapp.server.ai.AiProviderException
import com.application.bibleapp.server.models.AiAskResponse
import com.application.bibleapp.server.models.AiStreamText
import com.application.bibleapp.server.models.ErrorResponse
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Takes a streamed answer's pieces as they come and gives back the text that's safe to show
 * (#285): without the crisis marker, which [crisis] then reports. The instructions put the marker
 * first, but it's removed wherever it appears, as [splitCrisis] does for a whole answer. A piece
 * that could be the start of a marker ("[[CR") is held back until the next piece says whether it
 * is one; "[[JHN 3:16]]" goes on at once, since it can't be.
 */
internal class CrisisMarkerFilter {
    private val pending = StringBuilder()
    private var started = false

    var crisis = false
        private set

    /** The text from [piece] (and what was held back before it) that can be shown now. */
    fun push(piece: String): String {
        pending.append(piece)
        var index = pending.indexOf(CRISIS_MARKER)
        while (index >= 0) {
            crisis = true
            pending.delete(index, index + CRISIS_MARKER.length)
            index = pending.indexOf(CRISIS_MARKER)
        }
        val held = (minOf(pending.length, CRISIS_MARKER.length - 1) downTo 1)
            .firstOrNull { CRISIS_MARKER.startsWith(pending.substring(pending.length - it)) } ?: 0
        return release(pending.length - held)
    }

    /** What's still held back once the answer has ended: it wasn't a marker after all. */
    fun finish(): String = release(pending.length)

    private fun release(count: Int): String {
        var text = pending.substring(0, count)
        pending.delete(0, count)
        // The answer starts at its first word, as splitCrisis trims it ("[[CRISIS]] You…").
        if (!started) {
            text = text.trimStart()
            started = text.isNotEmpty()
        }
        return text
    }
}

/** What happened next while the AI writes an answer. */
private sealed interface AnswerEvent {
    data class Text(val text: String) : AnswerEvent
    data object Crisis : AnswerEvent
    data class Done(val completion: AiCompletion) : AnswerEvent
    data class Failed(val error: AiProviderException) : AnswerEvent
}

private val eventJson = Json { encodeDefaults = true }

/**
 * Answers `POST /ai/ask/stream` (#285) with server-sent events: `text` for each piece of the
 * answer (`{"text": "..."}`), `crisis` once the crisis marker is seen (so the app can put the
 * help card first), then `done` with the same body as `/ai/ask` ([finish]'s), or `error` with an
 * [ErrorResponse] if the AI stopped partway. If the AI fails before writing anything, the
 * answer is the same JSON error and status as `/ai/ask` gives, so the app handles both alike.
 *
 * [onNoAnswer] runs once, when the question ends without an answer: the AI failed, partway or
 * not, or the app hung up before the end. That's where the daily question is given back.
 * [respondError] sends the plain error for a failure before anything was written.
 */
internal suspend fun ApplicationCall.respondAnswerStream(
    provider: AiProvider,
    conversation: List<AiMessage>,
    maxTokens: Int,
    onNoAnswer: () -> Unit,
    respondError: suspend (AiProviderException) -> Unit,
    finish: (AiCompletion) -> AiAskResponse
) = coroutineScope {
    var settled = false
    fun noAnswer() {
        if (!settled) onNoAnswer()
        settled = true
    }
    val events = answerEvents(provider, conversation, maxTokens)
    try {
        val first = events.receive()
        if (first is AnswerEvent.Failed) {
            noAnswer()
            respondError(first.error)
            return@coroutineScope
        }
        // Proxies such as nginx hold a response back until it ends unless told not to; Render's
        // edge passes event streams on as they come.
        response.cacheControl(CacheControl.NoCache(null))
        response.header("X-Accel-Buffering", "no")
        respondBytesWriter(contentType = ContentType.Text.EventStream) {
            var event = first
            while (true) {
                when (event) {
                    is AnswerEvent.Text -> writeEvent("text", eventJson.encodeToString(AiStreamText(event.text)))
                    AnswerEvent.Crisis -> writeEvent("crisis", "{}")
                    is AnswerEvent.Failed -> {
                        noAnswer()
                        writeEvent("error", eventJson.encodeToString(errorFor(event.error)))
                        return@respondBytesWriter
                    }
                    is AnswerEvent.Done -> {
                        // The answer is complete: from here on it counts like one from /ai/ask.
                        settled = true
                        writeEvent("done", eventJson.encodeToString(finish(event.completion)))
                        return@respondBytesWriter
                    }
                }
                event = events.receive()
            }
        }
    } finally {
        // The app hung up, or writing failed: the person didn't get the whole answer.
        noAnswer()
        events.cancel()
    }
}

/** The AI's answer as [AnswerEvent]s, filtered by [CrisisMarkerFilter]. */
private fun kotlinx.coroutines.CoroutineScope.answerEvents(
    provider: AiProvider,
    conversation: List<AiMessage>,
    maxTokens: Int
): ReceiveChannel<AnswerEvent> = produce(capacity = Channel.UNLIMITED) {
    val filter = CrisisMarkerFilter()
    suspend fun pass(text: String, wasCrisis: Boolean) {
        if (filter.crisis && !wasCrisis) send(AnswerEvent.Crisis)
        if (text.isNotEmpty()) send(AnswerEvent.Text(text))
    }
    try {
        val completion = provider.stream(conversation, maxTokens) { piece ->
            val wasCrisis = filter.crisis
            pass(filter.push(piece), wasCrisis)
        }
        pass(filter.finish(), filter.crisis)
        send(AnswerEvent.Done(completion))
    } catch (e: AiProviderException) {
        send(AnswerEvent.Failed(e))
    }
}

/** One server-sent event. [data] is one line of JSON, so a line break in the answer can't end it. */
private suspend fun ByteWriteChannel.writeEvent(name: String, data: String) {
    writeStringUtf8("event: $name\ndata: $data\n\n")
    flush()
}
