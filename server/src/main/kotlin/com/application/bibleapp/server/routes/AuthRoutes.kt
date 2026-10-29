package com.application.bibleapp.server.routes

import com.application.bibleapp.server.auth.GoogleIdTokenVerifier
import com.application.bibleapp.server.auth.GoogleIdentity
import com.application.bibleapp.server.auth.GoogleKeysUnavailableException
import com.application.bibleapp.server.auth.JwtConfig
import com.application.bibleapp.server.auth.JwtIssuer
import com.application.bibleapp.server.auth.PasswordHasher
import com.application.bibleapp.server.auth.RefreshTokenIssuer
import com.application.bibleapp.server.auth.getJwtConfig
import com.application.bibleapp.server.db.tables.RefreshTokens
import com.application.bibleapp.server.db.tables.Users
import com.application.bibleapp.server.models.AuthResponse
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.GoogleSignInRequest
import com.application.bibleapp.server.models.LoginRequest
import com.application.bibleapp.server.models.LogoutRequest
import com.application.bibleapp.server.models.RefreshRequest
import com.application.bibleapp.server.models.RegisterRequest
import com.application.bibleapp.server.plugins.AUTH_RATE_LIMIT
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
private const val MIN_PASSWORD_LENGTH = 8
private val REFRESH_TOKEN_TTL: Duration = Duration.ofDays(30)

/**
 * Emails are stored and compared in lower case, so "Ana@example.com" and "ana@example.com" are
 * the same account (#114) - phone keyboards often capitalise the first letter. lowercase() with
 * no arguments uses Locale.ROOT, so the result doesn't depend on the server's language setting.
 * V3__lowercase_emails.sql lower-cased the existing rows and makes the database reject any email
 * that isn't lower case.
 */
private fun normalizeEmail(email: String): String = email.trim().lowercase()

/**
 * Issues a fresh access token + a fresh refresh token for [userId], storing only the refresh
 * token's hash. Shared by register, login, and refresh so the "what does a successful auth
 * response look like" logic lives in exactly one place instead of being copied three times.
 */
private fun issueTokensFor(userId: UUID, jwtConfig: JwtConfig): AuthResponse {
    val accessToken = JwtIssuer.issueAccessToken(
        secret = jwtConfig.secret,
        issuer = jwtConfig.issuer,
        audience = jwtConfig.audience,
        userId = userId
    )

    val rawRefreshToken = RefreshTokenIssuer.generateToken()

    transaction {
        RefreshTokens.insert {
            it[RefreshTokens.userId] = userId
            it[tokenHash] = RefreshTokenIssuer.hash(rawRefreshToken)
            it[expiresAt] = Instant.now().plus(REFRESH_TOKEN_TTL)
            it[createdAt] = Instant.now()
        }
    }

    return AuthResponse(
        userId = userId.toString(),
        accessToken = accessToken,
        accessTokenExpiresInSeconds = JwtIssuer.ACCESS_TOKEN_EXPIRES_IN_SECONDS,
        refreshToken = rawRefreshToken
    )
}

/**
 * Checks [idToken] with [verifier], or answers the error itself and returns null: the server
 * isn't set up for Google (503), Google's keys can't be fetched (503), or the token fails a
 * check (401). Shared by Google sign-in and deleting a Google account (#112).
 */
internal suspend fun ApplicationCall.verifyGoogleToken(
    verifier: GoogleIdTokenVerifier?,
    idToken: String
): GoogleIdentity? {
    if (verifier == null) {
        respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorResponse(code = "GOOGLE_SIGN_IN_UNAVAILABLE", message = "Google sign-in isn't set up on this server")
        )
        return null
    }
    val identity = try {
        verifier.verify(idToken)
    } catch (e: GoogleKeysUnavailableException) {
        respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorResponse(code = "GOOGLE_SIGN_IN_UNAVAILABLE", message = "Couldn't reach Google to check the sign-in")
        )
        return null
    }
    if (identity == null) {
        respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(code = "INVALID_GOOGLE_TOKEN", message = "Google sign-in couldn't be verified")
        )
    }
    return identity
}

fun Route.authRoutes(googleVerifier: GoogleIdTokenVerifier?) {

    rateLimit(AUTH_RATE_LIMIT) {
        post("/auth/register") {
            val request = call.receive<RegisterRequest>()
            val email = normalizeEmail(request.email)

            val fieldErrors = mutableMapOf<String, String>()
            if (!EMAIL_REGEX.matches(email)) {
                fieldErrors["email"] = "must be a valid email address"
            }
            if (request.password.length < MIN_PASSWORD_LENGTH) {
                fieldErrors["password"] = "must be at least $MIN_PASSWORD_LENGTH characters"
            }
            if (fieldErrors.isNotEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        code = "VALIDATION_ERROR",
                        message = "One or more fields are invalid",
                        fieldErrors = fieldErrors
                    )
                )
                return@post
            }

            val existingUser = transaction {
                Users.selectAll().where { Users.email eq email }.singleOrNull()
            }

            if (existingUser != null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse(code = "EMAIL_ALREADY_REGISTERED", message = "Email is already registered")
                )
                return@post
            }

            val passwordHash = PasswordHasher.hash(request.password)

            val userId = transaction {
                Users.insert {
                    it[Users.email] = email
                    it[Users.passwordHash] = passwordHash
                    it[Users.createdAt] = Instant.now()
                } get Users.id
            }

            val jwtConfig = call.application.getJwtConfig()
            call.respond(HttpStatusCode.Created, issueTokensFor(userId, jwtConfig))
        }

        post("/auth/login") {
            val request = call.receive<LoginRequest>()
            val email = normalizeEmail(request.email)

            val user = transaction {
                Users.selectAll().where { Users.email eq email }.singleOrNull()
            }

            // Same response whether the email doesn't exist or the password is wrong (checking
            // user != null with the && short-circuits before ever calling PasswordHasher.verify
            // when there's no row to compare against) - see docs/api_contract.md decision 4. The
            // API should never let a caller distinguish "wrong password" from "no such account".
            // An account made with Google has no password, so no password can match it (#112).
            val passwordHash = user?.get(Users.passwordHash)
            val credentialsAreValid = passwordHash != null && PasswordHasher.verify(request.password, passwordHash)

            if (!credentialsAreValid) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(code = "INVALID_CREDENTIALS", message = "Invalid email or password")
                )
                return@post
            }

            val jwtConfig = call.application.getJwtConfig()
            call.respond(HttpStatusCode.OK, issueTokensFor(user!![Users.id], jwtConfig))
        }

        // Google sign-in (#112): signs in to the account this Google account is linked to, links
        // it to an existing account with the same email, or creates a new account without a
        // password. Linking by email is safe because the verifier only accepts emails Google has
        // verified, so whoever holds the token controls that inbox (and linking takes the
        // account away from whoever set its password, #142).
        post("/auth/google") {
            val request = call.receive<GoogleSignInRequest>()
            val identity = call.verifyGoogleToken(googleVerifier, request.idToken) ?: return@post
            val email = normalizeEmail(identity.email)

            var created = false
            val userId = transaction {
                Users.selectAll().where { Users.googleSubject eq identity.subject }.singleOrNull()
                    ?.let { return@transaction it[Users.id] }

                val sameEmail = Users.selectAll().where { Users.email eq email }.singleOrNull()
                if (sameEmail != null) {
                    // Already linked to a different Google account with this email (Google
                    // accounts can be deleted and the address reused): don't take it over.
                    if (sameEmail[Users.googleSubject] != null) return@transaction null
                    // Registering never checks the email, so whoever set this account's password
                    // may not own the inbox; Google has checked it. The account goes to the Google
                    // user: the password is removed and every existing session revoked, so nobody
                    // who registered someone else's email keeps access (#142). Same transaction,
                    // so it all happens or none of it does; the token issued below comes after.
                    Users.update({ Users.id eq sameEmail[Users.id] }) {
                        it[googleSubject] = identity.subject
                        it[passwordHash] = null
                    }
                    RefreshTokens.update({
                        (RefreshTokens.userId eq sameEmail[Users.id]) and RefreshTokens.revokedAt.isNull()
                    }) {
                        it[revokedAt] = Instant.now()
                    }
                    return@transaction sameEmail[Users.id]
                }

                created = true
                Users.insert {
                    it[Users.email] = email
                    it[Users.passwordHash] = null
                    it[Users.googleSubject] = identity.subject
                    it[Users.createdAt] = Instant.now()
                } get Users.id
            }

            if (userId == null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse(
                        code = "EMAIL_LINKED_TO_OTHER_GOOGLE_ACCOUNT",
                        message = "This email's account is linked to a different Google account"
                    )
                )
                return@post
            }

            val jwtConfig = call.application.getJwtConfig()
            call.respond(
                if (created) HttpStatusCode.Created else HttpStatusCode.OK,
                issueTokensFor(userId, jwtConfig)
            )
        }

        post("/auth/refresh") {
            val request = call.receive<RefreshRequest>()
            val submittedHash = RefreshTokenIssuer.hash(request.refreshToken)

            val tokenRow = transaction {
                RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq submittedHash }.singleOrNull()
            }

            val isUsable = tokenRow != null &&
                tokenRow[RefreshTokens.revokedAt] == null &&
                tokenRow[RefreshTokens.expiresAt].isAfter(Instant.now())

            if (tokenRow == null || !isUsable) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(code = "UNAUTHORIZED", message = "Invalid, expired, or already-used refresh token")
                )
                return@post
            }

            // Rotation: this token is spent the moment it's used, regardless of whether the
            // caller ever uses the newly-issued one. If this exact token is submitted again later,
            // that's the "reuse of an already-rotated token" signal from the contract's design
            // decisions - a sign the token may have been copied/stolen, not normal client behavior.
            transaction {
                RefreshTokens.update({ RefreshTokens.id eq tokenRow[RefreshTokens.id] }) {
                    it[revokedAt] = Instant.now()
                }
            }

            val jwtConfig = call.application.getJwtConfig()
            call.respond(HttpStatusCode.OK, issueTokensFor(tokenRow[RefreshTokens.userId], jwtConfig))
        }
    }

    authenticate("auth-jwt") {
        post("/auth/logout") {
            val request = call.receive<LogoutRequest>()
            val tokenHash = RefreshTokenIssuer.hash(request.refreshToken)

            // Revokes only the one session this specific refresh token belongs to - other
            // devices stay logged in, per the contract. No need to check that it belongs to
            // the caller: only whoever already holds this exact raw token could have computed
            // this hash in the first place.
            transaction {
                RefreshTokens.update({ RefreshTokens.tokenHash eq tokenHash }) {
                    it[revokedAt] = Instant.now()
                }
            }

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
