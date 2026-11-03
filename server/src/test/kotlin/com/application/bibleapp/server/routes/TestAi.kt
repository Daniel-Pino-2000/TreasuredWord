package com.application.bibleapp.server.routes

import com.application.bibleapp.server.ai.AiCompletion
import com.application.bibleapp.server.ai.AiMessage
import com.application.bibleapp.server.ai.AiProvider
import com.application.bibleapp.server.ai.AiProviderException
import com.application.bibleapp.server.serverModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.ApplicationTestBuilder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for Cloudflare in the tests (#124): returns [answer], or throws [failure], and
 * records what it was sent, so `:server:test` never touches the network or spends quota.
 */
class FakeAiProvider : AiProvider {
    class Call(val messages: List<AiMessage>, val maxTokens: Int)

    val calls = CopyOnWriteArrayList<Call>()
    @Volatile var answer = "Grace is God's favor we don't deserve. [[EPH 2:8-9]]"
    @Volatile var failure: AiProviderException? = null

    override suspend fun complete(messages: List<AiMessage>, maxTokens: Int): AiCompletion {
        calls += Call(messages, maxTokens)
        failure?.let { throw it }
        return AiCompletion(text = answer, cutOff = false, neurons = 31.0)
    }
}

/** A clock the test moves by hand, to reach the next UTC day. */
class MutableClock(@Volatile var now: Instant = Instant.now()) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}

/**
 * Starts the server with [ai] in place of Cloudflare (null: no AI configured) and [clock], and
 * returns a JSON client. Like [startWithFakeEmail], application.conf's module list is emptied so
 * its module(), which would read the real AI_* settings, doesn't start too.
 */
fun ApplicationTestBuilder.startWithFakeAi(
    ai: AiProvider? = FakeAiProvider(),
    clock: Clock = MutableClock()
): HttpClient {
    environment {
        config = ApplicationConfig("application.conf")
            .mergeWith(MapApplicationConfig().apply { put("ktor.application.modules", emptyList()) })
    }
    application { serverModule(emailSender = FakeEmailSender(), aiProvider = ai, clock = clock) }
    return createClient { install(ContentNegotiation) { json() } }
}
