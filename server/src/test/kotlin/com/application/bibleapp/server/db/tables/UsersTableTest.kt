package com.application.bibleapp.server.db.tables

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// TODO: this points at the local dev Postgres container by default so it's easy to run right
// now. Worth switching to a dedicated test database (H2 in-memory, or Testcontainers) instead
// of sharing the dev DB — see the roadmap PDF, Section 2.
class UsersTableTest {

    // Same defaults and env overrides as application.conf's database block: the local Docker
    // Postgres on 5433, unless DATABASE_URL/USER/PASSWORD are set (CI's service runs on 5432).
    private val database = Database.connect(
        url = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5433/bibleapp",
        driver = "org.postgresql.Driver",
        user = System.getenv("DATABASE_USER") ?: "postgres",
        password = System.getenv("DATABASE_PASSWORD") ?: "postgres"
    )

    // A fresh email per test run so this test never collides with itself or with other tests
    // sharing this same database.
    private val testEmail = "userstabletest+${UUID.randomUUID()}@example.com"

    @BeforeTest
    fun createSchema() = transaction(database) {
        SchemaUtils.create(Users)
    }

    @AfterTest
    fun deleteTestRow() {
        // Deletes just the row this test inserted, rather than dropping the whole Users table:
        // RefreshTokens/Highlights/Notes/ReadingProgress all declare a foreign key to Users.id,
        // so dropping Users while any of those tables also exist in this shared dev database
        // would fail with a "depended on by other objects" error. Block body (not `= expr`) so
        // this stays Unit-returning - deleteWhere returns an Int, and @AfterTest requires void.
        transaction(database) {
            Users.deleteWhere { with(it) { email eq testEmail } }
        }
    }

    @Test
    fun insertedUserCanBeReadBackByEmail() = transaction(database) {
        val insertedId = Users.insert {
            it[email] = testEmail
            it[passwordHash] = "hashed-password"
            it[createdAt] = Instant.now()
        }[Users.id]

        val row = Users.selectAll().where { Users.email eq testEmail }.single()

        assertEquals(insertedId, row[Users.id])
        assertEquals(testEmail, row[Users.email])
        assertEquals("hashed-password", row[Users.passwordHash])
    }
}
