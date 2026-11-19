package com.application.bibleapp.server.routes

import io.ktor.server.routing.Route

/**
 * Groups the cross-device sync resources (highlights, notes, reading progress, the Memorize list). Each
 * lives in its own file since every resource has several endpoints of its own; this function
 * just decides that they're all mounted together, matching the grouping already decided in
 * docs/api_contract.md.
 */
fun Route.syncRoutes() {
    highlightRoutes()
    noteRoutes()
    readingProgressRoutes()
    memoryVerseRoutes()
}
