package com.application.bibleapp.server.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date

/**
 * How many Bible Helper answers each user got on each UTC [day] (#124), mirroring
 * V6__ai_daily_usage.sql. `userId` cascades on delete, so deleting an account deletes its counts.
 */
object AiDailyUsage : Table("ai_daily_usage") {
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val day = date("day")
    val count = integer("count")

    override val primaryKey = PrimaryKey(userId, day)
}
