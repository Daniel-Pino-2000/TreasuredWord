package com.application.bibleapp.server.email

import com.sun.net.httpserver.HttpServer
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GmailEmailSender] against a local stand-in for Google's token endpoint and the Gmail API
 * (#154), so the test checks the real requests it makes without sending any email.
 */
class GmailEmailSenderTest {

    private class Received(val path: String, val authorization: String?, val body: String)

    private val received = mutableListOf<Received>()
    private var tokenStatus = 200
    private var tokensIssued = 0

    /** What the stand-in Gmail answers, in order; once used up, 200. */
    private val sendStatuses = ArrayDeque<Int>()

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            received += Received(path, exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.readBytes().decodeToString())
            val (status, reply) = when (path) {
                "/token" -> if (tokenStatus == 200) {
                    tokensIssued++
                    200 to """{"access_token":"access-$tokensIssued","expires_in":3599,"scope":"https://www.googleapis.com/auth/gmail.send","token_type":"Bearer"}"""
                } else {
                    tokenStatus to """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}"""
                }
                else -> (sendStatuses.removeFirstOrNull() ?: 200).let { it to if (it == 200) """{"id":"test-id"}""" else """{"error":{"code":$it}}""" }
            }
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val baseUrl = "http://127.0.0.1:${server.address.port}"

    private fun sender(apiBaseUrl: String = baseUrl) = GmailEmailSender(
        clientId = "test-client.apps.googleusercontent.com",
        clientSecret = "test-secret",
        refreshToken = "test-refresh-token",
        from = "TreasuredWord <treasuredword.test@gmail.com>",
        tokenUrl = "$baseUrl/token",
        apiBaseUrl = apiBaseUrl
    )

    private val message = EmailMessage(to = "ana@example.com", subject = "123456 is your TreasuredWord code", text = "Your code is 123456\n\nIt works for 15 minutes.")

    private val sends get() = received.filter { it.path != "/token" }

    @AfterTest
    fun stopServer() = server.stop(0)

    @Test
    fun getsAnAccessTokenWithTheRefreshTokenThenSendsTheEmail() = runBlocking {
        sender().send(message)

        val tokenRequest = received.first()
        assertEquals("/token", tokenRequest.path)
        val form = tokenRequest.body.split("&").associate { pair ->
            pair.substringBefore("=") to URLDecoder.decode(pair.substringAfter("="), Charsets.UTF_8)
        }
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("test-refresh-token", form["refresh_token"])
        assertEquals("test-client.apps.googleusercontent.com", form["client_id"])
        assertEquals("test-secret", form["client_secret"])

        val send = sends.single()
        assertEquals("/gmail/v1/users/me/messages/send", send.path)
        assertEquals("Bearer access-1", send.authorization)
    }

    @Test
    fun theEmailIsPlainTextMimeWithTheRightHeaders() = runBlocking {
        sender().send(message)

        val raw = Json.parseToJsonElement(sends.single().body).jsonObject["raw"]!!.jsonPrimitive.content
        val mime = Base64.getUrlDecoder().decode(raw).decodeToString()
        val (headers, body) = mime.split("\r\n\r\n", limit = 2)
        val lines = headers.split("\r\n")
        assertTrue("From: TreasuredWord <treasuredword.test@gmail.com>" in lines)
        assertTrue("To: ana@example.com" in lines)
        assertTrue("Content-Type: text/plain; charset=UTF-8" in lines)
        val subject = lines.single { it.startsWith("Subject: ") }.removePrefix("Subject: ")
        val encodedSubject = subject.removePrefix("=?UTF-8?B?").removeSuffix("?=")
        assertEquals(message.subject, Base64.getDecoder().decode(encodedSubject).decodeToString())
        assertEquals(message.text, Base64.getMimeDecoder().decode(body).decodeToString())
    }

    @Test
    fun theAccessTokenIsReusedForTheNextEmail() = runBlocking {
        val sender = sender()
        sender.send(message)
        sender.send(message)

        assertEquals(1, received.count { it.path == "/token" })
        assertEquals(listOf("Bearer access-1", "Bearer access-1"), sends.map { it.authorization })
    }

    @Test
    fun aRejectedAccessTokenIsRefreshedAndTheEmailSentOnce() = runBlocking {
        sendStatuses += 401

        sender().send(message)

        assertEquals(2, received.count { it.path == "/token" })
        assertEquals(listOf("Bearer access-1", "Bearer access-2"), sends.map { it.authorization })
    }

    @Test
    fun aRevokedRefreshTokenThrowsEmailSendExceptionAndSendsNothing() {
        tokenStatus = 400

        assertFailsWith<EmailSendException> { runBlocking { sender().send(message) } }
        assertTrue(sends.isEmpty())
    }

    @Test
    fun aRefusalFromGmailThrowsEmailSendException() {
        sendStatuses += 429

        assertFailsWith<EmailSendException> { runBlocking { sender().send(message) } }
    }

    @Test
    fun anUnreachableGmailThrowsEmailSendException() {
        // A port that was free a moment ago, so nothing answers there.
        val closedPort = ServerSocket(0).use { it.localPort }

        assertFailsWith<EmailSendException> { runBlocking { sender(apiBaseUrl = "http://127.0.0.1:$closedPort").send(message) } }
    }

    @Test
    fun anAddressWithALineBreakIsRefusedBeforeAnythingIsSent() {
        val sneaky = message.copy(to = "ana@example.com\r\nBcc: everyone@example.com")

        assertFailsWith<EmailSendException> { runBlocking { sender().send(sneaky) } }
        assertTrue(received.isEmpty())
    }

    @Test
    fun withoutEverySettingThereIsNoEmailSender() {
        val complete = mapOf(
            "email.gmailClientId" to "id",
            "email.gmailClientSecret" to "secret",
            "email.gmailRefreshToken" to "refresh",
            "email.from" to "TreasuredWord <treasuredword.test@gmail.com>"
        )
        assertNotNull(GmailEmailSender.fromConfig(MapApplicationConfig(*complete.toList().toTypedArray())))
        for (missing in complete.keys) {
            val config = complete + (missing to "")
            assertNull(GmailEmailSender.fromConfig(MapApplicationConfig(*config.toList().toTypedArray())), "without $missing")
        }
    }
}
