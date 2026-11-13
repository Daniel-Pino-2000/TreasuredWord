package com.application.bibleapp.server.ai

/** Who wrote a message: our fixed instructions ([SYSTEM]), the person asking, or the AI. */
enum class AiRole(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant")
}

/** One message of a conversation sent to the AI. */
data class AiMessage(val role: AiRole, val content: String)

/**
 * The AI's answer.
 *
 * [cutOff] means it ran out of tokens before finishing (`finish_reason: "length"`), so the text
 * ends mid-thought. [neurons] is what Cloudflare counted against the free daily 10,000, when the
 * provider reports it; it's logged for the cost watch in the plan's section 3.
 */
data class AiCompletion(val text: String, val cutOff: Boolean, val neurons: Double?)

/** Why there's no answer. The two kinds get different messages in the app (#124). */
sealed class AiProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The free daily allowance is used up (Cloudflare error 3036, HTTP 429). It comes back at 00:00 UTC. */
class QuotaExhausted(message: String) : AiProviderException(message)

/**
 * A timeout, a server error, or an answer we couldn't read. Worth trying again later.
 * [transient] marks a hiccup that's usually over in a moment (no connection, a 5xx, Cloudflare's
 * "capacity temporarily exceeded"), which the provider tries once more by itself (#280).
 */
class Unavailable(message: String, cause: Throwable? = null, val transient: Boolean = false) : AiProviderException(message, cause)

/**
 * Sends a conversation to an AI and returns its answer (#123).
 *
 * An interface so the route doesn't care which company answers: in production it's
 * [OpenAiCompatibleProvider] (Cloudflare Workers AI), and tests use a fake that never touches the
 * network or spends quota. Adding Groq as a backup later is a settings change.
 */
interface AiProvider {
    /** @throws QuotaExhausted or [Unavailable] when there's no answer. */
    suspend fun complete(messages: List<AiMessage>, maxTokens: Int): AiCompletion

    /**
     * Like [complete], but hands each piece of the answer to [onText] as the AI writes it (#285),
     * then returns the whole answer. A provider that can't stream gives the whole answer as one
     * piece, which is what this default does (and what the tests' fake relies on).
     *
     * @throws QuotaExhausted or [Unavailable] when there's no answer, also after some pieces came.
     */
    suspend fun stream(messages: List<AiMessage>, maxTokens: Int, onText: suspend (String) -> Unit): AiCompletion =
        complete(messages, maxTokens).also { onText(it.text) }
}
