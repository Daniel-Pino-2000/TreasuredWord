package com.application.bibleapp.server.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.util.UUID

/**
 * Searches people chose to report because the results didn't help (#402), mirroring
 * V12__search_reports.sql. No user ID or IP, on purpose, like [AiReports]. Kept 90 days.
 */
object SearchReports : Table("search_reports") {
    val id = uuid("id").clientDefault { UUID.randomUUID() }
    val createdAt = timestamp("created_at").index("search_reports_created_at_idx")
    val query = text("query")
    val language = text("language")
    val bibleVersion = text("bible_version")
    val appVersion = text("app_version").nullable()
    // USFM references joined by ", ", in the order the app showed them; "" when there were none.
    val results = text("results")
    val keyPassages = text("key_passages")

    override val primaryKey = PrimaryKey(id)
}
