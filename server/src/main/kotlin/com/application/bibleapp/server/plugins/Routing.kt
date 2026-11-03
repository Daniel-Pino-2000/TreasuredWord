package com.application.bibleapp.server.plugins

import com.application.bibleapp.server.ai.AiProvider
import com.application.bibleapp.server.auth.EmailCodeStore
import com.application.bibleapp.server.auth.GoogleIdTokenVerifier
import com.application.bibleapp.server.auth.getJwtConfig
import com.application.bibleapp.server.email.EmailSender
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.routes.aiRoutes
import com.application.bibleapp.server.routes.authRoutes
import com.application.bibleapp.server.routes.emailRoutes
import com.application.bibleapp.server.routes.syncRoutes
import com.application.bibleapp.server.routes.userRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Clock

fun Application.configureRouting(emailSender: EmailSender?, aiProvider: AiProvider?, clock: Clock) {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(
                code = "INTERNAL_ERROR",
                message = "An unexpected error occurred"
            ))
        }
    }

    val googleVerifier = GoogleIdTokenVerifier.fromConfig(environment.config)
    // Codes are HMAC'd with the JWT secret, which lives outside the database (#156).
    val emailCodes = EmailCodeStore(getJwtConfig().secret)

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        route("/api/v1") {
            authRoutes(googleVerifier)
            emailRoutes(emailSender, emailCodes)
            userRoutes(googleVerifier)
            syncRoutes()
            aiRoutes(aiProvider, clock)
        }
    }
}
