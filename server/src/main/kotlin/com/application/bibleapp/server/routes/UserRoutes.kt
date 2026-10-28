package com.application.bibleapp.server.routes

import com.application.bibleapp.server.auth.GoogleIdTokenVerifier
import com.application.bibleapp.server.auth.PasswordHasher
import com.application.bibleapp.server.auth.currentUserId
import com.application.bibleapp.server.db.tables.Users
import com.application.bibleapp.server.models.DeleteAccountRequest
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.UserResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Account endpoints. Both require a valid access token, and the current user's id always
 * comes from the verified token (see auth/Principal.kt), never from a request field.
 */
fun Route.userRoutes(googleVerifier: GoogleIdTokenVerifier?) {
    authenticate("auth-jwt") {
        get("/users/me") {
            val userId = call.currentUserId()

            val user = transaction {
                Users.selectAll().where { Users.id eq userId }.singleOrNull()
            }

            if (user == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "User not found"))
                return@get
            }

            call.respond(
                HttpStatusCode.OK,
                UserResponse(
                    id = user[Users.id].toString(),
                    email = user[Users.email],
                    createdAt = user[Users.createdAt].toString(),
                    hasPassword = user[Users.passwordHash] != null,
                    hasGoogle = user[Users.googleSubject] != null
                )
            )
        }

        delete("/users/me") {
            val userId = call.currentUserId()
            val request = call.receive<DeleteAccountRequest>()

            val user = transaction {
                Users.selectAll().where { Users.id eq userId }.singleOrNull()
            }

            if (user == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "User not found"))
                return@delete
            }

            // Re-confirms who's asking rather than trusting the bearer token alone - a
            // destructive, irreversible action deserves a stronger check, in case the token
            // came from a device the user no longer trusts (docs/api_contract.md decision 5).
            // With the password, or for a Google account (which may have none, #112) by signing
            // in with that same Google account again.
            when {
                request.password != null -> {
                    val passwordHash = user[Users.passwordHash]
                    if (passwordHash == null || !PasswordHasher.verify(request.password, passwordHash)) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse(code = "INVALID_CREDENTIALS", message = "Incorrect password")
                        )
                        return@delete
                    }
                }
                request.googleIdToken != null -> {
                    val identity = call.verifyGoogleToken(googleVerifier, request.googleIdToken) ?: return@delete
                    val linkedSubject = user[Users.googleSubject]
                    if (linkedSubject == null || identity.subject != linkedSubject) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse(code = "INVALID_CREDENTIALS", message = "That Google account isn't this account's")
                        )
                        return@delete
                    }
                }
                else -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            code = "VALIDATION_ERROR",
                            message = "Confirm with the password or with Google",
                            fieldErrors = mapOf("password" to "required unless googleIdToken is given")
                        )
                    )
                    return@delete
                }
            }

            // Deleting the user row cascades to their highlights, notes, reading progress, and
            // refresh tokens automatically - every one of those tables declares its userId
            // column with onDelete = ReferenceOption.CASCADE, so the database enforces this,
            // not application code. This isn't optional polish: Google Play's User Data policy
            // requires an in-app path to delete an account and its data for any app that lets
            // users create one (decision 6).
            transaction {
                Users.deleteWhere { with(it) { Users.id eq userId } }
            }

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
