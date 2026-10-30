package com.application.bibleapp.data.model

/**
 * A Bible Helper conversation (#127). Conversations live only on this phone: nothing about them
 * is synced, and signing out or deleting the account removes them. [title] is the first question,
 * shortened by [conversationTitle].
 */
data class AiConversation(
    val id: Long,
    val title: String,
    val createdAt: String,
    val updatedAt: String
)

/** One question or answer in an [AiConversation]. [crisis] and [promptVersion] are only set on
 *  answers: the server's crisis flag (#130) and which version of its instructions wrote it. */
data class AiMessage(
    val id: Long,
    val conversationId: Long,
    val role: AiRole,
    val content: String,
    val crisis: Boolean = false,
    val promptVersion: String? = null,
    val createdAt: String
)

/** The only two roles the server accepts, so the app can never send instructions of its own
 *  (plan section 5). [stored] is both the database value and the API's `role`. */
enum class AiRole(val stored: String) {
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromStored(value: String): AiRole = entries.firstOrNull { it.stored == value } ?: USER
    }
}

/** How long a conversation's title can be before it's cut, in characters. */
const val AI_CONVERSATION_TITLE_LENGTH = 60

/** The first question, on one line, cut at a word near [AI_CONVERSATION_TITLE_LENGTH] with "…". */
fun conversationTitle(question: String): String {
    val oneLine = question.trim().replace(Regex("\\s+"), " ")
    if (oneLine.length <= AI_CONVERSATION_TITLE_LENGTH) return oneLine
    val cut = oneLine.take(AI_CONVERSATION_TITLE_LENGTH)
    // Back up to the last space unless that loses too much (e.g. a language without spaces).
    val lastSpace = cut.lastIndexOf(' ')
    val shortened = if (lastSpace >= AI_CONVERSATION_TITLE_LENGTH / 2) cut.take(lastSpace) else cut
    return shortened.trimEnd(' ', ',', '.', ';', ':') + "…"
}
