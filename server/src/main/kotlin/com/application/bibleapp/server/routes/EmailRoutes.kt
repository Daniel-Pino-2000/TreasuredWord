package com.application.bibleapp.server.routes

import com.application.bibleapp.server.auth.EmailCodeStore
import com.application.bibleapp.server.auth.currentUserId
import com.application.bibleapp.server.db.tables.EmailCodePurpose
import com.application.bibleapp.server.db.tables.Users
import com.application.bibleapp.server.email.EmailSendException
import com.application.bibleapp.server.email.EmailSender
import com.application.bibleapp.server.email.EmailTemplates
import com.application.bibleapp.server.models.ErrorResponse
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

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
 * Verifying an account's email with an emailed code (#156). Both endpoints are for a signed-in
 * user: the app calls `send` right after Create account, and again from Settings for accounts
 * that never verified.
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
}
