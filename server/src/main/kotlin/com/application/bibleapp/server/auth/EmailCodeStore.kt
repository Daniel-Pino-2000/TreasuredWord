package com.application.bibleapp.server.auth

import com.application.bibleapp.server.db.tables.EmailCodePurpose
import com.application.bibleapp.server.db.tables.EmailCodes
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Makes and checks emailed codes (#156), shared by verify-email and password reset (#157).
 * Both functions expect to run inside a `transaction { }`, so a successful check and what it
 * unlocks (marking the email verified, changing the password) happen together or not at all.
 *
 * [secret] keys the codes' HMAC (see EmailCodeIssuer).
 */
class EmailCodeStore(private val secret: String) {

    /**
     * Stores a new code for [userId] and returns it, or null if the account has already been
     * sent [MAX_CODES_PER_HOUR] codes in the last hour. That limit is per account, on top of
     * the per-IP rate limit, so nobody can flood one person's inbox by changing IP addresses.
     *
     * Older codes for the same purpose stop working without being touched, because [check]
     * only ever looks at the newest one. They stay a day so the hourly count can see them.
     */
    fun issue(userId: UUID, purpose: EmailCodePurpose): String? {
        val now = Instant.now()
        EmailCodes.deleteWhere {
            with(it) { (EmailCodes.userId eq userId) and (EmailCodes.createdAt less now.minus(KEEP_FOR)) }
        }
        val sentLastHour = EmailCodes.selectAll()
            .where { (EmailCodes.userId eq userId) and (EmailCodes.createdAt greater now.minus(Duration.ofHours(1))) }
            .count()
        if (sentLastHour >= MAX_CODES_PER_HOUR) return null

        val code = EmailCodeIssuer.generateCode()
        EmailCodes.insert {
            it[EmailCodes.userId] = userId
            it[EmailCodes.purpose] = purpose.dbValue
            it[codeHash] = EmailCodeIssuer.hash(code, secret)
            it[expiresAt] = now.plus(CODE_TTL)
            it[createdAt] = now
        }
        return code
    }

    /** Takes back the code [issue] just made, when the email with it couldn't be sent, so the
     *  failed send doesn't count against the hourly limit. */
    fun withdrawNewest(userId: UUID, purpose: EmailCodePurpose) {
        newest(userId, purpose)?.let { row -> EmailCodes.deleteWhere { with(it) { EmailCodes.id eq row[EmailCodes.id] } } }
    }

    /**
     * Whether [code] is [userId]'s newest code for [purpose], not yet used, not expired and
     * within its [MAX_ATTEMPTS] tries. Every check counts as a try, right or wrong, and a match
     * uses the code up. The caller answers the same for every kind of failure, so a guesser
     * can't tell a wrong code from an expired one.
     */
    fun check(userId: UUID, purpose: EmailCodePurpose, code: String): Boolean {
        val row = newest(userId, purpose, lock = true) ?: return false
        val id = row[EmailCodes.id]
        if (row[EmailCodes.usedAt] != null) return false
        if (!row[EmailCodes.expiresAt].isAfter(Instant.now())) return false
        if (row[EmailCodes.attempts] >= MAX_ATTEMPTS) return false

        EmailCodes.update({ EmailCodes.id eq id }) { it[attempts] = attempts + 1 }
        // People paste codes with spaces around them, or as "123 456".
        if (!EmailCodeIssuer.matches(code.filterNot { it.isWhitespace() }, row[EmailCodes.codeHash], secret)) return false

        EmailCodes.update({ EmailCodes.id eq id }) { it[usedAt] = Instant.now() }
        return true
    }

    /** [lock] holds the row until the transaction ends (SELECT ... FOR UPDATE), so two checks
     *  at the same moment can't both spend the same try or both use the same code. */
    private fun newest(userId: UUID, purpose: EmailCodePurpose, lock: Boolean = false) = EmailCodes.selectAll()
        .where { (EmailCodes.userId eq userId) and (EmailCodes.purpose eq purpose.dbValue) }
        .orderBy(EmailCodes.createdAt, SortOrder.DESC)
        .limit(1)
        .let { if (lock) it.forUpdate() else it }
        .singleOrNull()

    companion object {
        /** How long a code works. Long enough to switch to the email app and back. */
        val CODE_TTL: Duration = Duration.ofMinutes(15)
        /** Wrong tries a code allows: 5 guesses out of a million possible codes. */
        const val MAX_ATTEMPTS = 5
        /** Codes one account can be sent in an hour, verification and reset together. */
        const val MAX_CODES_PER_HOUR = 5
        private val KEEP_FOR: Duration = Duration.ofDays(1)
    }
}
