package com.application.bibleapp.server.email

import com.application.bibleapp.server.auth.EmailCodeStore

/**
 * The emails the server sends (#156, #157). Plain text: a code needs no design, and plain text
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

    fun resetPassword(to: String, code: String) = EmailMessage(
        to = to,
        subject = "$code is your TreasuredWord password reset code",
        text = """
            Your TreasuredWord password reset code is $code

            Enter it in the app with your new password. It works for $minutes minutes.
            Resetting your password signs you out of TreasuredWord on every device.

            If you didn't ask to reset your password, you can ignore this email: your password
            stays the same.
        """.trimIndent()
    )
}
