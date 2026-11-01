package com.application.bibleapp.server.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.util.UUID

/** What an emailed code is for. [dbValue] is what's stored in `email_codes.purpose`. */
enum class EmailCodePurpose(val dbValue: String) {
    VERIFY_EMAIL("verify_email"),
    RESET_PASSWORD("reset_password")
}

/**
 * One-time 6-digit codes sent by email (#155), mirroring V5__email_verification.sql. Like
 * refresh tokens, only a hash of the code is stored (see auth/EmailCodeIssuer.kt). A code works
 * once ([usedAt]), until [expiresAt], and for only a few wrong tries ([attempts]): with only a
 * million possible codes, the limit on tries is what really protects it.
 *
 * `userId` cascades on delete, so deleting an account deletes its codes too.
 */
object EmailCodes : Table("email_codes") {
    val id = uuid("id").clientDefault { UUID.randomUUID() }
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE).index("email_codes_user_id_idx")
    // An EmailCodePurpose.dbValue; the database's email_codes_purpose CHECK allows only those.
    val purpose = text("purpose")
    val codeHash = text("code_hash")
    val expiresAt = timestamp("expires_at")
    val usedAt = timestamp("used_at").nullable()
    val attempts = integer("attempts").default(0)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
