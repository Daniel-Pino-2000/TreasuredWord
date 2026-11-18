package com.application.bibleapp.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Runs [block] in a transaction on the IO threads (#346). On Render's free instance Ktor answers
 * every request on one thread, and Exposed's blocking `transaction { }` holds it until Neon
 * answers (hundreds of ms, seconds while Neon's compute wakes), so every other request waited.
 */
suspend fun <T> dbQuery(block: Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }
