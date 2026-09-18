package com.application.bibleapp.server.routes

import com.application.bibleapp.server.auth.JwtConfig
import com.application.bibleapp.server.auth.JwtIssuer
import com.application.bibleapp.server.auth.PasswordHasher
import com.application.bibleapp.server.auth.RefreshTokenIssuer
import com.application.bibleapp.server.auth.getJwtConfig
import com.application.bibleapp.server.db.tables.RefreshTokens
import com.application.bibleapp.server.db.tables.Users
import com.application.bibleapp.server.models.AuthResponse
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.LoginRequest
import com.application.bibleapp.server.models.LogoutRequest
import com.application.bibleapp.server.models.RefreshRequest
import com.application.bibleapp.server.models.RegisterRequest
import com.application.bibleapp.server.plugins.AUTH_RATE_LIMIT
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
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

fun Route.authRoutes() {

    rateLimit(AUTH_RATE_LIMIT) {
        post("/auth/register") {
            val request = call.receive<RegisterRequest>()

            val fieldErrors = mutableMapOf<String, String>()
            if (!EMAIL_REGEX.matches(request.email)) {
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
                Users.selectAll().where { Users.email eq request.email }.singleOrNull()
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
                    it[Users.email] = request.email
                    it[Users.passwordHash] = passwordHash
                    it[Users.createdAt] = Instant.now()
                } get Users.id
            }

            val jwtConfig = call.application.getJwtConfig()
            call.respond(HttpStatusCode.Created, issueTokensFor(userId, jwtConfig))
        }

        post("/auth/login") {
            val request = call.receive<LoginRequest>()

            val user = transaction {
                Users.selectAll().where { Users.email eq request.email }.singleOrNull()
            }

            // Same response whether the email doesn't exist or the password is wrong (checking
            // user != null with the && short-circuits before ever calling PasswordHasher.verify
            // when there's no row to compare against) - see docs/api_contract.md decision 4. The
            // API should never let a caller distinguish "wrong password" from "no such account".
            val credentialsAreValid = user != null && PasswordHasher.verify(request.password, user[Users.passwordHash])

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
