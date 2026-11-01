package com.application.bibleapp.server.email

import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Sends email through Resend's HTTPS API (#154): one JSON POST to `/emails` with the API key as
 * a bearer token. HTTPS rather than SMTP because Render's free tier blocks outgoing SMTP ports.
 *
 * Uses the JDK's own HTTP client, so the server needs no new dependency for one POST.
 *
 * [from] must be an address on a domain verified in Resend's dashboard, written as
 * `TreasuredWord <codes@example.com>`. [baseUrl] is only changed by tests.
 */
class ResendEmailSender(
    private val apiKey: String,
    private val from: String,
    private val baseUrl: String = DEFAULT_BASE_URL
) : EmailSender {

    private val client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()

    @Serializable
    private data class ResendEmail(val from: String, val to: List<String>, val subject: String, val text: String)

    override suspend fun send(message: EmailMessage) {
        val body = Json.encodeToString(ResendEmail.serializer(), ResendEmail(from, listOf(message.to), message.subject, message.text))
        val request = HttpRequest.newBuilder(URI("$baseUrl/emails"))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        // sendAsync + await suspends instead of blocking one of Ktor's threads while Resend answers.
        val response = try {
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: IOException) {
            throw EmailSendException("Couldn't reach Resend", e)
        }
        if (response.statusCode() !in 200..299) {
            // Resend's error body says why (bad key, unverified domain, daily limit reached). It
            // never contains the key, so it's safe to log; the recipient's address isn't logged.
            log.warn("Resend refused an email: HTTP {} {}", response.statusCode(), response.body())
            throw EmailSendException("Resend answered HTTP ${response.statusCode()}")
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.resend.com"
        private val TIMEOUT: Duration = Duration.ofSeconds(10)
        private val log = LoggerFactory.getLogger(ResendEmailSender::class.java)

        /**
         * Built from the `email` block of application.conf, or null when RESEND_API_KEY or
         * EMAIL_FROM isn't set (a local server, or before the Resend account exists): the
         * endpoints that send email then answer EMAIL_UNAVAILABLE instead of the server failing
         * to start, like Google sign-in without GOOGLE_CLIENT_ID.
         */
        fun fromConfig(config: ApplicationConfig): ResendEmailSender? {
            val apiKey = config.propertyOrNull("email.resendApiKey")?.getString()?.takeIf { it.isNotBlank() } ?: return null
            val from = config.propertyOrNull("email.from")?.getString()?.takeIf { it.isNotBlank() } ?: return null
            val baseUrl = config.propertyOrNull("email.resendBaseUrl")?.getString() ?: DEFAULT_BASE_URL
            return ResendEmailSender(apiKey, from, baseUrl)
        }
    }
}
