package com.application.bibleapp.server.email

/** One plain-text email to one person. */
data class EmailMessage(val to: String, val subject: String, val text: String)

/** The provider refused the email or couldn't be reached; the caller decides what to answer. */
class EmailSendException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Sends email for the server (#154): the codes that verify an address and reset a password.
 *
 * An interface so the routes don't care who delivers it: in production it's [ResendEmailSender],
 * and the tests use a fake that only records what would have been sent, so `:server:test` never
 * sends real mail.
 */
interface EmailSender {
    /** @throws EmailSendException if the email couldn't be handed to the provider. */
    suspend fun send(message: EmailMessage)
}
