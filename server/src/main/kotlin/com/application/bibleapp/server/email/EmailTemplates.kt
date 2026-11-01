package com.application.bibleapp.server.email

import com.application.bibleapp.server.auth.EmailCodeStore

/**
 * The emails the server sends (#156). Plain text: a code needs no design, and plain text
 * is less likely to be marked as spam. The code also goes first in the subject, so it can be
 * read from the phone's notification without opening the email.
 */
object EmailTemplates {
    private val minutes = EmailCodeStore.CODE_TTL.toMinutes()

    fun verifyEmail(to: String, code: String) = EmailMessage(
        to = to,
        subject = "$code is your TreasuredWord code",
        text = """
            Your TreasuredWord verification code is $code

            Enter it in the app to verify your email address. It works for $minutes minutes.

            If you didn't create a TreasuredWord account, you can ignore this email.
        """.trimIndent()
    )
}
