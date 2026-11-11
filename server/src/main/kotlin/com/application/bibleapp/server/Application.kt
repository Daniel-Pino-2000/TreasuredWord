package com.application.bibleapp.server

import com.application.bibleapp.server.ai.AiProvider
import com.application.bibleapp.server.ai.OpenAiCompatibleProvider
import com.application.bibleapp.server.db.startRetentionCleanup
import com.application.bibleapp.server.email.EmailSender
import com.application.bibleapp.server.email.GmailEmailSender
import com.application.bibleapp.server.plugins.configureDatabases
import com.application.bibleapp.server.plugins.configureMonitoring
import com.application.bibleapp.server.plugins.configureRateLimiting
import com.application.bibleapp.server.plugins.configureRouting
import com.application.bibleapp.server.plugins.configureSecurity
import com.application.bibleapp.server.plugins.configureSerialization
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import java.time.Clock

fun main(args: Array<String>) {
    EngineMain.main(args)
}

/** The module application.conf starts: the real server, sending email through Gmail when the
 *  GMAIL_* settings and EMAIL_FROM are set (#154), and asking Cloudflare Workers AI when the AI_*
 *  settings are (#124). */
fun Application.module() {
    serverModule(
        emailSender = GmailEmailSender.fromConfig(environment.config),
        aiProvider = OpenAiCompatibleProvider.fromConfig(environment.config)
    )
}

/**
 * Everything [module] sets up, with the email sender passed in (#154): tests start the server
 * through this with a fake that records emails instead of sending them. Null means the server
 * can't send email, and the endpoints that would answer EMAIL_UNAVAILABLE. Likewise a null
 * [aiProvider] makes the Bible Helper answer AI_UNAVAILABLE (#124), and tests pass a [clock] to
 * move its daily limit to the next day.
 */
fun Application.serverModule(emailSender: EmailSender?, aiProvider: AiProvider? = null, clock: Clock = Clock.systemUTC()) {
    configureMonitoring()
    configureSerialization()
    configureSecurity()
    configureRateLimiting(clock)
    configureDatabases()
    startRetentionCleanup(clock)
    configureRouting(emailSender, aiProvider, clock)
}
