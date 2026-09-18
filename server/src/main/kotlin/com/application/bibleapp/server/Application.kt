package com.application.bibleapp.server

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

fun Application.module() {
    configureMonitoring()
    configureSerialization()
    configureSecurity()
    configureRateLimiting()
    configureDatabases()
    configureRouting()
}
