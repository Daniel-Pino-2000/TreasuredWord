package com.application.bibleapp.server.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * One ready answer per Ask tab starter, language and instructions version (#284), mirroring
 * V8__ai_starter_answers.sql. Shared by everyone, so there's no user.
 */
object AiStarterAnswers : Table("ai_starter_answers") {
    val starter = text("starter")
    val language = text("language")
    val promptVersion = text("prompt_version")
    val answer = text("answer")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(starter, language, promptVersion)
}
