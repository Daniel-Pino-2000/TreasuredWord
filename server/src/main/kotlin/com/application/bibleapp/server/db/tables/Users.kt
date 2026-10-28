package com.application.bibleapp.server.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.lowerCase
import java.util.UUID

object Users : Table("users") {
    val id = uuid("id").clientDefault { UUID.randomUUID() }
    // Always lower case (#114) - mirrors users_email_lowercase from V3__lowercase_emails.sql.
    val email = text("email").uniqueIndex().check("users_email_lowercase") { it eq it.lowerCase() }
    // Null for an account made with Google sign-in that never set a password (#112).
    val passwordHash = text("password_hash").nullable()
    // Google's permanent ID for the account ("sub" in its ID token), once Google sign-in has been
    // used; null otherwise. Mirrors V4__google_sign_in.sql.
    val googleSubject = text("google_subject").nullable().uniqueIndex("users_google_subject_unique")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        // Every account keeps at least one way to sign in (#112).
        check("users_has_sign_in_method") { passwordHash.isNotNull() or googleSubject.isNotNull() }
    }
}