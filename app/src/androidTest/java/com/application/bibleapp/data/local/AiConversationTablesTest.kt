package com.application.bibleapp.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sign-out, account deletion and switching accounts remove every Bible Helper conversation (#127),
 * on a real SQLite. Uses a throwaway in-memory database, never the app's own.
 */
@RunWith(AndroidJUnit4::class)
class AiConversationTablesTest {

    private lateinit var db: SQLiteDatabase

    @Before
    fun createTables() {
        db = SQLiteDatabase.create(null)
        BibleDatabaseManager.ensureUserContentTables(db)
        BibleDatabaseManager.ensureAiHelperTables(db)
        // Running it again, as every app start does, is harmless.
        BibleDatabaseManager.ensureAiHelperTables(db)

        db.execSQL("INSERT INTO ai_conversations (id, title, created_at, updated_at) VALUES (1, 'Q', 't', 't')")
        db.execSQL("INSERT INTO ai_messages (conversation_id, role, content, created_at) VALUES (1, 'user', 'Q', 't')")
        db.execSQL("INSERT INTO ai_messages (conversation_id, role, content, crisis, prompt_version, created_at) VALUES (1, 'assistant', 'A', 0, '1', 't')")
        db.execSQL(
            "INSERT INTO notes (remote_id, version_id, verses_json, text, created_at, updated_at) VALUES (NULL, 'kjv', '[]', 'never uploaded', 't', 't')"
        )
    }

    @After
    fun close() = db.close()

    @Test
    fun signingOutOrDeletingTheAccountRemovesEveryConversation() {
        BibleDatabaseManager.deleteAllUserContent(db)

        assertEquals(0, count("ai_conversations"))
        assertEquals(0, count("ai_messages"))
        assertEquals(0, count("notes"))
    }

    @Test
    fun signingIntoAnotherAccountRemovesTheOldAccountsConversations() {
        BibleDatabaseManager.deleteContentSyncedToAnAccount(db)

        assertEquals(0, count("ai_conversations"))
        assertEquals(0, count("ai_messages"))
        // A note made here and never uploaded still goes to the new account (#81).
        assertEquals(1, count("notes"))
    }

    private fun count(table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
}
