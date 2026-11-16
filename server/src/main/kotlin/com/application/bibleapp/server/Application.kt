package com.application.bibleapp.server

import com.application.bibleapp.server.ai.AiProvider
import com.application.bibleapp.server.ai.HelperVerses
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
import com.application.bibleapp.server.search.EmbeddingProvider
import com.application.bibleapp.server.search.VerseSearch
import com.application.bibleapp.server.search.WorkersAiEmbeddingProvider
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import java.time.Clock

fun main(args: Array<String>) {
    EngineMain.main(args)
}

/** The module application.conf starts: the real server, sending email through Gmail when the
 *  GMAIL_* settings and EMAIL_FROM are set (#154), and asking Cloudflare Workers AI when the AI_*
 *  settings are (#124), for answers and for search by meaning's query vectors (#287). */
fun Application.module() {
    serverModule(
        emailSender = GmailEmailSender.fromConfig(environment.config),
        aiProvider = OpenAiCompatibleProvider.fromConfig(environment.config),
        embeddingProvider = WorkersAiEmbeddingProvider.fromConfig(environment.config, VerseSearch.MODEL),
        helperVerses = environment.config.propertyOrNull("ai.helperVerses")?.getString()?.toBoolean() == true
    )
}

/**
 * Everything [module] sets up, with the email sender passed in (#154): tests start the server
 * through this with a fake that records emails instead of sending them. Null means the server
 * can't send email, and the endpoints that would answer EMAIL_UNAVAILABLE. Likewise a null
 * [aiProvider] makes the Bible Helper answer AI_UNAVAILABLE (#124), and a null [embeddingProvider]
 * makes search by meaning answer SEARCH_UNAVAILABLE (#287). [helperVerses] (AI_HELPER_VERSES)
 * gives the Bible Helper the verses search finds for each question (#287, phase 5); it needs an
 * [embeddingProvider], and is off unless set. Tests pass a [clock] to move the daily limits to
 * the next day, and a [searchTranslation] of their own so a dev database's BSB rows are never touched.
 */
fun Application.serverModule(
    emailSender: EmailSender?,
    aiProvider: AiProvider? = null,
    clock: Clock = Clock.systemUTC(),
    embeddingProvider: EmbeddingProvider? = null,
    helperVerses: Boolean = false,
    searchTranslation: String = VerseSearch.TRANSLATION
) {
    configureMonitoring()
    configureSerialization()
    configureSecurity()
    configureRateLimiting(clock)
    configureDatabases()
    startRetentionCleanup(clock)
    // One search for the endpoint and the Bible Helper, so both count against the same daily cap.
    val search = embeddingProvider?.let { VerseSearch(it, searchTranslation, clock) }
    configureRouting(emailSender, aiProvider, search, search?.takeIf { helperVerses }?.let(::HelperVerses), clock)
}
