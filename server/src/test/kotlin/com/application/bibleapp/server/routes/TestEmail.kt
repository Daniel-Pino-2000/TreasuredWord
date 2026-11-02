package com.application.bibleapp.server.routes

import com.application.bibleapp.server.email.EmailMessage
import com.application.bibleapp.server.email.EmailSendException
import com.application.bibleapp.server.email.EmailSender
import com.application.bibleapp.server.serverModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for Gmail in the tests (#154): records each email instead of sending it, so
 * `:server:test` never sends real mail. Set [failing] to act like Gmail refusing the email.
 */
class FakeEmailSender : EmailSender {
    val sent = CopyOnWriteArrayList<EmailMessage>()
    @Volatile var failing = false

    override suspend fun send(message: EmailMessage) {
        if (failing) throw EmailSendException("FakeEmailSender is set to fail")
        sent += message
    }

    fun sentTo(email: String): List<EmailMessage> = sent.filter { it.to == email }

    /** The code in the newest email to [email], waiting up to a few seconds for emails the
     *  server sends in the background (password reset). */
    suspend fun awaitCodeTo(email: String): String = withTimeout(5_000) {
        while (sentTo(email).isEmpty()) delay(20)
        CODE.find(sentTo(email).last().text)!!.value
    }

    private companion object {
        val CODE = Regex("\\b\\d{6}\\b")
    }
}

/**
 * Starts the server with a [FakeEmailSender] in place of Gmail and returns it with a JSON
 * client. application.conf's module list is emptied so its module(), which would use the real
 * sender, doesn't start too; serverModule() is the same server with the sender passed in.
 */
fun ApplicationTestBuilder.startWithFakeEmail(
    baseConfig: ApplicationConfig = ApplicationConfig("application.conf")
): Pair<HttpClient, FakeEmailSender> {
    val fake = FakeEmailSender()
    environment {
        config = baseConfig.mergeWith(MapApplicationConfig().apply { put("ktor.application.modules", emptyList()) })
    }
    application { serverModule(fake) }
    return createClient { install(ContentNegotiation) { json() } } to fake
}
