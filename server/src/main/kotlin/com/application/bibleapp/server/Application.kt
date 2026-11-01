package com.application.bibleapp.server

import com.application.bibleapp.server.email.EmailSender
import com.application.bibleapp.server.email.ResendEmailSender
import com.application.bibleapp.server.plugins.configureDatabases
import com.application.bibleapp.server.plugins.configureMonitoring
import com.application.bibleapp.server.plugins.configureRateLimiting
import com.application.bibleapp.server.plugins.configureRouting
import com.application.bibleapp.server.plugins.configureSecurity
import com.application.bibleapp.server.plugins.configureSerialization
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain

fun main(args: Array<String>) {
    EngineMain.main(args)
}

/** The module application.conf starts: the real server, sending email through Resend when
 *  RESEND_API_KEY and EMAIL_FROM are set. */
fun Application.module() {
    serverModule(emailSender = ResendEmailSender.fromConfig(environment.config))
}

/**
 * Everything [module] sets up, with the email sender passed in (#154): tests start the server
 * through this with a fake that records emails instead of sending them. Null means the server
 * can't send email, and the endpoints that would answer EMAIL_UNAVAILABLE.
 */
fun Application.serverModule(emailSender: EmailSender?) {
    configureMonitoring()
    configureSerialization()
    configureSecurity()
    configureRateLimiting()
    configureDatabases()
    configureRouting(emailSender)
}
