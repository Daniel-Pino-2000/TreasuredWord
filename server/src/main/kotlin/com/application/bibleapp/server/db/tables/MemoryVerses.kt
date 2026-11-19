package com.application.bibleapp.server.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.util.UUID

/**
 * A passage on someone's Memorize list (#362): one book and chapter, verses [verseStart] to
 * [verseEnd], in the version it was read in, plus its practice progress. Unlike a highlight or
 * note it's always one run of consecutive verses, so it's stored as columns rather than
 * Highlights' JSON verse list. [progressAt] is when the progress last changed on a device, so an
 * older device can't move it backwards (docs/api_contract.md decision 21). `deletedAt` is a
 * tombstone, as for Highlights.
 */
object MemoryVerses : Table("memory_verses") {
    val id = uuid("id").clientDefault { UUID.randomUUID() }
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val versionId = text("version_id")
    val bookId = integer("book_id")
    val chapter = integer("chapter")
    val verseStart = integer("verse_start")
    val verseEnd = integer("verse_end")
    val status = text("status")
    val level = integer("level")
    val nextReviewAt = timestamp("next_review_at").nullable()
    val lastPracticedAt = timestamp("last_practiced_at").nullable()
    val correctStreak = integer("correct_streak")
    val memorizedAt = timestamp("memorized_at").nullable()
    val progressAt = timestamp("progress_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
