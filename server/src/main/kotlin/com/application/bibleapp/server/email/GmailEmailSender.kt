package com.application.bibleapp.server.email

import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Sends email through the Gmail API (#154), from TreasuredWord's own Gmail account. Free, unlike
 * Resend, which only delivers to other people from a domain of our own (and a domain costs money).
 * Mail that really comes from Gmail also passes Gmail's own sender checks, so it isn't marked as
 * spam the way "from a Gmail address" mail sent through another company would be.
 *
 * Two HTTPS calls, both with the JDK's own HTTP client (Render's free tier blocks SMTP ports):
 * 1. [refreshToken] → a short-lived access token from Google's token endpoint. The refresh token
 *    comes from authorizing the Gmail account once with the `gmail.send` scope (server/DEPLOY.md);
 *    the access token lasts about an hour, so it's kept and reused until shortly before then.
 * 2. `POST /gmail/v1/users/me/messages/send` with the whole email, MIME-encoded, as `raw`.
 *
 * [from] is the Gmail account itself with a name, `TreasuredWord <name@gmail.com>`; Gmail sends
 * from the authorized account whatever it says. The URLs are only changed by tests.
 */
class GmailEmailSender(
    private val clientId: String,
    private val clientSecret: String,
    private val refreshToken: String,
    private val from: String,
    private val tokenUrl: String = DEFAULT_TOKEN_URL,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL
) : EmailSender {

    private val client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long = 3600
    )

    @Serializable
    private data class RawMessage(val raw: String)

    private class AccessToken(val value: String, val expiresAt: Instant)

    // One refresh at a time: two codes requested together would otherwise both refresh.
    private val tokenLock = Mutex()
    private var keptToken: AccessToken? = null

    override suspend fun send(message: EmailMessage) {
        // The address goes into a header line; a line break in it could add headers of its own.
        if (message.to.any { it == '\r' || it == '\n' }) throw EmailSendException("Invalid recipient address")
        val body = json.encodeToString(RawMessage.serializer(), RawMessage(encodeRaw(mimeMessage(from, message))))

        var response = postEmail(body, accessToken())
        if (response.statusCode() == 401) {
            // The kept access token was revoked or expired early: get a new one and try once more.
            response = postEmail(body, accessToken(forceRefresh = true))
        }
        if (response.statusCode() !in 200..299) {
            // Google's error body says why (daily sending limit, account suspended). It never
            // contains a token, so it's safe to log; the recipient's address isn't logged.
            log.warn("Gmail refused an email: HTTP {} {}", response.statusCode(), response.body())
            throw EmailSendException("Gmail answered HTTP ${response.statusCode()}")
        }
    }

    private suspend fun postEmail(body: String, token: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("$apiBaseUrl/gmail/v1/users/me/messages/send"))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return request(request, "Gmail")
    }

    private suspend fun accessToken(forceRefresh: Boolean = false): String = tokenLock.withLock {
        val kept = keptToken
        if (!forceRefresh && kept != null && Instant.now().isBefore(kept.expiresAt)) return kept.value

        val form = listOf(
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token"
        ).joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        val request = HttpRequest.newBuilder(URI(tokenUrl))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = request(request, "Google's token endpoint")
        if (response.statusCode() != 200) {
            // Usually "invalid_grant": the refresh token was revoked (the Gmail password changed,
            // access was removed) or went unused for six months. DEPLOY.md says how to get a new one.
            log.warn("Couldn't get a Gmail access token: HTTP {} {}", response.statusCode(), response.body())
            throw EmailSendException("Google's token endpoint answered HTTP ${response.statusCode()}")
        }
        val token = json.decodeFromString(TokenResponse.serializer(), response.body())
        // A minute early, so a token never runs out between being checked and being used.
        keptToken = AccessToken(token.accessToken, Instant.now().plusSeconds(token.expiresIn - 60))
        token.accessToken
    }

    // sendAsync + await suspends instead of blocking one of Ktor's threads while Google answers.
    private suspend fun request(request: HttpRequest, what: String): HttpResponse<String> = try {
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
    } catch (e: IOException) {
        throw EmailSendException("Couldn't reach $what", e)
    }

    companion object {
        const val DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val DEFAULT_API_BASE_URL = "https://gmail.googleapis.com"
        private val TIMEOUT: Duration = Duration.ofSeconds(10)
        private val log = LoggerFactory.getLogger(GmailEmailSender::class.java)

        /**
         * The email as Gmail wants it: plain-text MIME. The subject and body are base64 in UTF-8,
         * so any character survives and no line is too long.
         */
        internal fun mimeMessage(from: String, message: EmailMessage): String {
            val mime = Base64.getMimeEncoder()
            val subject = "=?UTF-8?B?${Base64.getEncoder().encodeToString(message.subject.toByteArray())}?="
            return listOf(
                "From: $from",
                "To: ${message.to}",
                "Subject: $subject",
                "MIME-Version: 1.0",
                "Content-Type: text/plain; charset=UTF-8",
                "Content-Transfer-Encoding: base64",
                "",
                mime.encodeToString(message.text.toByteArray())
            ).joinToString("\r\n")
        }

        /** Gmail's `raw`: the whole MIME message in URL-safe base64. */
        internal fun encodeRaw(mime: String): String = Base64.getUrlEncoder().encodeToString(mime.toByteArray())

        /**
         * Built from the `email` block of application.conf, or null when any of GMAIL_CLIENT_ID,
         * GMAIL_CLIENT_SECRET, GMAIL_REFRESH_TOKEN or EMAIL_FROM isn't set (a local server, or before
         * the Gmail account is set up): the endpoints that send email then answer EMAIL_UNAVAILABLE
         * instead of the server failing to start, like Google sign-in without GOOGLE_CLIENT_ID.
         */
        fun fromConfig(config: ApplicationConfig): GmailEmailSender? {
            fun setting(name: String) = config.propertyOrNull("email.$name")?.getString()?.takeIf { it.isNotBlank() }
            return GmailEmailSender(
                clientId = setting("gmailClientId") ?: return null,
                clientSecret = setting("gmailClientSecret") ?: return null,
                refreshToken = setting("gmailRefreshToken") ?: return null,
                from = setting("from") ?: return null,
                tokenUrl = setting("gmailTokenUrl") ?: DEFAULT_TOKEN_URL,
                apiBaseUrl = setting("gmailApiBaseUrl") ?: DEFAULT_API_BASE_URL
            )
        }
    }
}
