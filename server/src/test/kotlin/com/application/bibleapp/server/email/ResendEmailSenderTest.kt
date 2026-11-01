package com.application.bibleapp.server.email

import com.sun.net.httpserver.HttpServer
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [ResendEmailSender] against a local stand-in for Resend's API (#154), so the test checks the
 * real request it makes without sending any email.
 */
class ResendEmailSenderTest {

    private class Received(val path: String, val authorization: String?, val body: String)

    private val received = mutableListOf<Received>()
    private var status = 200

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            received += Received(
                exchange.requestURI.path,
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestBody.readBytes().decodeToString()
            )
            val reply = (if (status == 200) """{"id":"test-email-id"}""" else """{"message":"test"}""").toByteArray()
            exchange.sendResponseHeaders(status, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        start()
    }
    private val baseUrl = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stopServer() = server.stop(0)

    @Test
    fun sendsOneJsonPostToTheEmailsEndpointWithTheKey() = runBlocking {
        val sender = ResendEmailSender(apiKey = "re_test_key", from = "TreasuredWord <codes@example.com>", baseUrl = baseUrl)

        sender.send(EmailMessage(to = "ana@example.com", subject = "Your code", text = "123456"))

        val request = received.single()
        assertEquals("/emails", request.path)
        assertEquals("Bearer re_test_key", request.authorization)
        val json = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("TreasuredWord <codes@example.com>", json["from"]!!.jsonPrimitive.content)
        assertEquals("ana@example.com", json["to"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("Your code", json["subject"]!!.jsonPrimitive.content)
        assertEquals("123456", json["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRefusalFromResendThrowsEmailSendException() {
        status = 422
        val sender = ResendEmailSender(apiKey = "re_test_key", from = "codes@example.com", baseUrl = baseUrl)

        assertFailsWith<EmailSendException> {
            runBlocking { sender.send(EmailMessage("ana@example.com", "Your code", "123456")) }
        }
    }

    @Test
    fun anUnreachableResendThrowsEmailSendException() {
        // A port that was free a moment ago, so nothing answers there.
        val closedPort = ServerSocket(0).use { it.localPort }
        val sender = ResendEmailSender(apiKey = "re_test_key", from = "codes@example.com", baseUrl = "http://127.0.0.1:$closedPort")

        assertFailsWith<EmailSendException> {
            runBlocking { sender.send(EmailMessage("ana@example.com", "Your code", "123456")) }
        }
    }

    @Test
    fun withoutAKeyOrASenderThereIsNoEmailSender() {
        assertNull(ResendEmailSender.fromConfig(MapApplicationConfig("email.resendApiKey" to "", "email.from" to "codes@example.com")))
        assertNull(ResendEmailSender.fromConfig(MapApplicationConfig("email.resendApiKey" to "re_test_key", "email.from" to "")))
        assertNotNull(ResendEmailSender.fromConfig(MapApplicationConfig("email.resendApiKey" to "re_test_key", "email.from" to "codes@example.com")))
    }
}
