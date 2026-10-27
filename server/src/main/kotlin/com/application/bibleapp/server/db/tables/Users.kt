package com.application.bibleapp.server.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.lowerCase
import java.util.UUID

object Users : Table("users") {
    val id = uuid("id").clientDefault { UUID.randomUUID() }
    // Always lower case (#114) - mirrors users_email_lowercase from V3__lowercase_emails.sql.
    val email = text("email").uniqueIndex().check("users_email_lowercase") { it eq it.lowerCase() }
    val passwordHash = text("password_hash")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}