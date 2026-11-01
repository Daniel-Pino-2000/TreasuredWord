package com.application.bibleapp.server.routes

import com.application.bibleapp.server.auth.EmailCodeStore
import com.application.bibleapp.server.auth.PasswordHasher
import com.application.bibleapp.server.auth.currentUserId
import com.application.bibleapp.server.db.tables.EmailCodePurpose
import com.application.bibleapp.server.db.tables.RefreshTokens
import com.application.bibleapp.server.db.tables.Users
import com.application.bibleapp.server.email.EmailSendException
import com.application.bibleapp.server.email.EmailSender
import com.application.bibleapp.server.email.EmailTemplates
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.PasswordResetRequest
import com.application.bibleapp.server.models.PasswordResetSendRequest
import com.application.bibleapp.server.models.VerifyEmailRequest
import com.application.bibleapp.server.plugins.AUTH_RATE_LIMIT
import com.application.bibleapp.server.plugins.EMAIL_RATE_LIMIT
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant

private val log = LoggerFactory.getLogger("com.application.bibleapp.server.routes.EmailRoutes")

/** The answer for a server without RESEND_API_KEY/EMAIL_FROM, or when Resend fails. */
internal suspend fun ApplicationCall.respondEmailUnavailable() = respond(
    HttpStatusCode.ServiceUnavailable,
    ErrorResponse(code = "EMAIL_UNAVAILABLE", message = "The server can't send email right now")
)

/** One answer for a wrong, expired, used or worn-out code, so a guesser learns nothing (#156). */
internal suspend fun ApplicationCall.respondInvalidCode() = respond(
    HttpStatusCode.BadRequest,
    ErrorResponse(code = "INVALID_CODE", message = "The code is wrong or no longer works")
)

/**
 * Emails a reset code to [email] if it has an account (#157). Runs after the request has been
 * answered, so the answer can't depend on whether the account exists, and neither can how long
 * it takes. Failures are only logged: the person can ask again.
 */
private suspend fun sendResetCode(emailSender: EmailSender, codes: EmailCodeStore, email: String) {
    try {
        val (userId, code) = transaction {
            val user = Users.selectAll().where { Users.email eq email }.singleOrNull() ?: return@transaction null
            // null when this account already got its hourly allowance of codes.
            codes.issue(user[Users.id], EmailCodePurpose.RESET_PASSWORD)?.let { user[Users.id] to it }
        } ?: return
        try {
            emailSender.send(EmailTemplates.resetPassword(email, code))
        } catch (e: EmailSendException) {
            transaction { codes.withdrawNewest(userId, EmailCodePurpose.RESET_PASSWORD) }
            log.warn("Couldn't send a password reset code: {}", e.message)
        }
    } catch (e: Exception) {
        // Nobody is waiting for this answer, so an error here would otherwise vanish silently.
        log.error("Sending a password reset code failed", e)
    }
}

/**
 * Verifying an account's email (#156) and resetting a forgotten password (#157) with an emailed
 * code. Verify-email is for a signed-in user: the app calls `send` right after Create account,
 * and again from Settings for accounts that never verified. Password reset is for someone who
 * can't sign in, so it's found by email instead.
 *
 * [emailSender] is null when the server isn't set up to send email; [codes] makes and checks
 * the codes.
 */
fun Route.emailRoutes(emailSender: EmailSender?, codes: EmailCodeStore) {
    authenticate("auth-jwt") {
        // Sends an email, so it's in the strict email zone, not the auth one.
        rateLimit(EMAIL_RATE_LIMIT) {
            post("/auth/verify-email/send") {
                if (emailSender == null) {
                    call.respondEmailUnavailable()
                    return@post
                }
                val userId = call.currentUserId()
                val user = transaction { Users.selectAll().where { Users.id eq userId }.singleOrNull() }
                if (user == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "User not found"))
                    return@post
                }
                if (user[Users.emailVerifiedAt] != null) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(code = "EMAIL_ALREADY_VERIFIED", message = "This email is already verified")
                    )
                    return@post
                }

                val code = transaction { codes.issue(userId, EmailCodePurpose.VERIFY_EMAIL) }
                if (code == null) {
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ErrorResponse(code = "TOO_MANY_EMAILS", message = "Too many codes sent to this email; try again later")
                    )
                    return@post
                }

                // Sent while the app waits, so it can say whether the email went out. Resend
                // answers in well under a second.
                try {
                    emailSender.send(EmailTemplates.verifyEmail(user[Users.email], code))
                } catch (e: EmailSendException) {
                    transaction { codes.withdrawNewest(userId, EmailCodePurpose.VERIFY_EMAIL) }
                    call.respondEmailUnavailable()
                    return@post
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // Checking a code sends nothing, so the auth zone is enough; each code's own limit of
        // tries is what stops guessing.
        rateLimit(AUTH_RATE_LIMIT) {
            post("/auth/verify-email") {
                val request = call.receive<VerifyEmailRequest>()
                val userId = call.currentUserId()

                val verified = transaction {
                    val user = Users.selectAll().where { Users.id eq userId }.singleOrNull() ?: return@transaction false
                    // Already verified (a second tap, or another device got there first): done.
                    if (user[Users.emailVerifiedAt] != null) return@transaction true
                    if (!codes.check(userId, EmailCodePurpose.VERIFY_EMAIL, request.code)) return@transaction false
                    Users.update({ Users.id eq userId }) { it[emailVerifiedAt] = Instant.now() }
                    true
                }

                if (verified) call.respond(HttpStatusCode.NoContent) else call.respondInvalidCode()
            }
        }
    }

    rateLimit(EMAIL_RATE_LIMIT) {
        // Always the same answer, 204, whether or not the email has an account (decision 4):
        // the lookup and the email happen after answering.
        post("/auth/password-reset/send") {
            val request = call.receive<PasswordResetSendRequest>()
            // Says nothing about the account: it's the same for every email.
            if (emailSender == null) {
                call.respondEmailUnavailable()
                return@post
            }
            val email = normalizeEmail(request.email)
            call.application.launch { sendResetCode(emailSender, codes, email) }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    rateLimit(AUTH_RATE_LIMIT) {
        post("/auth/password-reset") {
            val request = call.receive<PasswordResetRequest>()
            // Checked before the code, so a too-short password doesn't spend one of its tries.
            if (request.newPassword.length < MIN_PASSWORD_LENGTH) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        code = "VALIDATION_ERROR",
                        message = "One or more fields are invalid",
                        fieldErrors = mapOf("newPassword" to "must be at least $MIN_PASSWORD_LENGTH characters")
                    )
                )
                return@post
            }
            val email = normalizeEmail(request.email)
            // Hashed before looking the account up, so an unknown email takes as long as a
            // real one (bcrypt is by far the slowest step).
            val newHash = PasswordHasher.hash(request.newPassword)

            val reset = transaction {
                // No such account: the same answer as a wrong code.
                val user = Users.selectAll().where { Users.email eq email }.singleOrNull() ?: return@transaction false
                val userId = user[Users.id]
                if (!codes.check(userId, EmailCodePurpose.RESET_PASSWORD, request.code)) return@transaction false

                val now = Instant.now()
                Users.update({ Users.id eq userId }) {
                    it[passwordHash] = newHash
                    // The code reached the inbox, so the email is the owner's too.
                    if (user[Users.emailVerifiedAt] == null) it[emailVerifiedAt] = now
                }
                // Signs out every device: if someone else knew the old password, their session
                // ends here. The app signs in again with the new password.
                RefreshTokens.update({ (RefreshTokens.userId eq userId) and RefreshTokens.revokedAt.isNull() }) {
                    it[revokedAt] = now
                }
                true
            }

            if (reset) call.respond(HttpStatusCode.NoContent) else call.respondInvalidCode()
        }
    }
}
