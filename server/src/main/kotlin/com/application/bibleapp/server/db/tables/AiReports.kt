package com.application.bibleapp.server.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.util.UUID

/**
 * Reported Bible Helper answers (#125), mirroring V7__ai_reports.sql. No user ID, on purpose:
 * we need the bad answer, not who reported it. Kept 90 days.
 */
object AiReports : Table("ai_reports") {
    val id = uuid("id").clientDefault { UUID.randomUUID() }
    val createdAt = timestamp("created_at").index("ai_reports_created_at_idx")
    // One of WRONG_TEACHING, WRONG_VERSE, HARMFUL, OTHER; the database's CHECK allows only those.
    val reason = text("reason")
    val question = text("question")
    val answer = text("answer")
    val comment = text("comment").nullable()
    val language = text("language")
    val promptVersion = text("prompt_version").nullable()

    override val primaryKey = PrimaryKey(id)
}
