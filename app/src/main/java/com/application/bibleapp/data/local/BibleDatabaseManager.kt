package com.application.bibleapp.data.local

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.application.bibleapp.data.model.BibleVerse
import com.application.bibleapp.data.model.DailyVerseRef
import com.application.bibleapp.data.model.DownloadedVersionInfo
import com.application.bibleapp.data.model.Footnote
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.Note
import com.application.bibleapp.data.model.SyncStatus
import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.data.model.decodeVerseContentOrNull
import com.application.bibleapp.data.model.encodeToJson
import com.application.bibleapp.data.remote.BibleRemoteDataSource
import com.application.bibleapp.data.remote.DownloadedTranslation
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.data.remote.decodeVerseLocationsOrEmpty
import com.application.bibleapp.data.remote.encodeToJson as encodeVerseLocationsToJson
import com.application.bibleapp.utils.NetworkUtils
import com.application.bibleapp.utils.TextUtils.normalizeForSearch
import com.application.bibleapp.utils.isoTimestampNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What actually got written to disk after a translation download. */
data class VersionDownloadSummary(
    val downloadedVerseCount: Int,
    val downloadedChapterCount: Int,
    val skippedBookCount: Int
)

/**
 * Owns the single on-device SQLite connection and every query/write against it.
 *
 * Storage model: the bundled `KJV_verses` table (shipped as an asset, `text` only)
 * is never modified. Everything downloaded from the helloao API lands in three
 * separate tables instead — `downloaded_versions` (one row per downloaded
 * translation, plus [DownloadedVersionInfo.schemaVersion] recording which shape
 * of [downloadAndSaveVersion] wrote it), `downloaded_verses` (`text` for search,
 * `rich_content` for rendering — see [encodeToJson]/[decodeVerseContentOrNull]),
 * and `downloaded_footnotes` (chapter-wide footnote text, looked up separately
 * from verse rendering by version/book/chapter/noteId rather than duplicated
 * onto every verse that references one).
 *
 * New columns/tables are added idempotently via [addColumnIfMissing] rather than
 * a versioned migration framework — there's no ordered migration history to
 * replay, just "does this column exist yet."
 */
object BibleDatabaseManager {

    private const val DB_NAME = "bible_default.db"
    private const val DB_VERSION = 2 // bump this whenever you change the schema
    private var dbInstance: SQLiteDatabase? = null

    /**
     * Copies DB from assets if it doesn't exist, or if the stored version is outdated.
     * Version is tracked in a separate tiny file next to the DB so we don't touch the DB itself.
     */
    private fun copyDatabaseIfNeeded(context: Context): File {
        val dbFile = context.getDatabasePath(DB_NAME)
        val versionFile = File(dbFile.parent, "${DB_NAME}.ver")

        val installedVersion = if (versionFile.exists()) versionFile.readText().trim().toIntOrNull() ?: 0 else 0

        if (!dbFile.exists() || installedVersion < DB_VERSION) {
            dbFile.parentFile?.mkdirs()
            try {
                context.assets.open(DB_NAME).use { input ->
                    FileOutputStream(dbFile).use { output ->
                        input.copyTo(output)
                    }
                }
                versionFile.writeText(DB_VERSION.toString())
                Log.d("BibleDB", "Database copied (schema version $DB_VERSION)")
            } catch (e: Exception) {
                Log.e("BibleDB", "Error copying database: ${e.message}")
                throw e
            }
        } else {
            Log.d("BibleDB", "Database up to date (version $installedVersion)")
        }

        return dbFile
    }

    fun getDatabase(context: Context): SQLiteDatabase {
        if (dbInstance == null || dbInstance?.isOpen == false) {
            // Close stale instance before reopening
            dbInstance?.close()
            val dbFile = copyDatabaseIfNeeded(context)
            dbInstance = SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            ensureDownloadedVersionsTable(dbInstance!!)
            ensureUserContentTables(dbInstance!!)
        }
        return dbInstance!!
    }

    /**
     * Creates a separate table for downloaded (non-KJV) verses.
     * We never touch KJV_verses — it stays exactly as bundled.
     */
    private fun ensureDownloadedVersionsTable(db: SQLiteDatabase) {
        // Tracks which versions have been fully downloaded
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS downloaded_versions (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                downloaded_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // Existing rows predate this column and default to 1 ("plain text only") —
        // that's exactly what they are, since rich_content/footnotes didn't exist yet
        // when they were downloaded. New downloads always stamp the current version.
        addColumnIfMissing(db, "downloaded_versions", "schema_version", "INTEGER NOT NULL DEFAULT 1")

        // Separate table for downloaded verses — never mixed with bundled KJV.
        // rich_content is nullable JSON (StoredVerseContent) alongside plain `text` —
        // `text` stays plain on purpose so search's GROUP_CONCAT/LIKE keeps working
        // unchanged; only the reading screen looks at rich_content.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS downloaded_verses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                version TEXT NOT NULL,
                book_id INTEGER NOT NULL,
                chapter INTEGER NOT NULL,
                verse INTEGER NOT NULL,
                text TEXT NOT NULL,
                rich_content TEXT,
                UNIQUE(version, book_id, chapter, verse)
            )
            """.trimIndent()
        )
        addColumnIfMissing(db, "downloaded_verses", "rich_content", "TEXT")

        // Footnote text is chapter-wide, referenced by noteId from a verse's rich_content
        // runs — kept in its own table rather than duplicated per-verse.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS downloaded_footnotes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                version TEXT NOT NULL,
                book_id INTEGER NOT NULL,
                chapter INTEGER NOT NULL,
                note_id INTEGER NOT NULL,
                verse INTEGER NOT NULL,
                caller TEXT,
                text TEXT NOT NULL,
                UNIQUE(version, book_id, chapter, note_id)
            )
            """.trimIndent()
        )

        // Each book's name as given by the translation itself (e.g. "Génesis" for a
        // Spanish translation) — the bundled KJV has no row here and never needs one,
        // since BibleBooks' hardcoded English names are already correct for it.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS downloaded_book_names (
                version TEXT NOT NULL,
                book_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                PRIMARY KEY (version, book_id)
            )
            """.trimIndent()
        )

        // Per-chapter verse count as this translation actually has it — versification
        // (verse numbering/splitting) can differ slightly between translations, so
        // navigation shouldn't just assume the bundled KJV's structure applies to every
        // downloaded version. Chapter count for a book is derived from COUNT(*) of its
        // rows here rather than stored separately.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS downloaded_chapter_info (
                version TEXT NOT NULL,
                book_id INTEGER NOT NULL,
                chapter INTEGER NOT NULL,
                verse_count INTEGER NOT NULL,
                PRIMARY KEY (version, book_id, chapter)
            )
            """.trimIndent()
        )

        // Single-row cache of the daily-verse reference, refreshed once a day by
        // DailyVerseFetchWorker and read by the Home screen instead of hitting the
        // remote API on every open. `CHECK (id = 0)` enforces at the DB level that
        // this table can only ever hold one row — the "replace, don't accumulate"
        // upsert the daily verse feature needs. end_verse is a real nullable column
        // (SQLite supports NULL natively), so no SharedPreferences-style sentinel
        // value is needed here the way one was for the old int-based cache.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_verse_cache (
                id INTEGER PRIMARY KEY CHECK (id = 0),
                date_key TEXT NOT NULL,
                book_id INTEGER NOT NULL,
                chapter INTEGER NOT NULL,
                start_verse INTEGER NOT NULL,
                end_verse INTEGER
            )
            """.trimIndent()
        )

        Log.d("BibleDB", "Downloaded versions/verses/footnotes tables ready")
    }

    /**
     * Local-first storage for highlights and notes — see server/docs/api_contract.md and
     * data/model/Highlight.kt, Note.kt. This device's Room-free SQLite is the source of truth;
     * `sync_status` marks rows the sync worker (Phase E) still needs to push. `remote_id` stays
     * NULL until the first successful push. Soft deletes (`deleted_at`) mirror the server's
     * tombstone model so a pending deletion can still be pushed after the row is "gone" locally.
     */
    private fun ensureUserContentTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS highlights (
                local_id INTEGER PRIMARY KEY AUTOINCREMENT,
                remote_id TEXT,
                version_id TEXT NOT NULL,
                verses_json TEXT NOT NULL,
                color INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                deleted_at TEXT,
                sync_status TEXT NOT NULL DEFAULT 'PENDING'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notes (
                local_id INTEGER PRIMARY KEY AUTOINCREMENT,
                remote_id TEXT,
                version_id TEXT NOT NULL,
                verses_json TEXT NOT NULL,
                text TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                deleted_at TEXT,
                sync_status TEXT NOT NULL DEFAULT 'PENDING'
            )
            """.trimIndent()
        )
        Log.d("BibleDB", "Highlight/note tables ready")
    }

    /** Overwrites the cached daily-verse reference — always a single row, replaced wholesale. */
    fun saveCachedDailyVerse(context: Context, dateKey: String, ref: DailyVerseRef) {
        val db = getDatabase(context)
        db.execSQL(
            """
            INSERT OR REPLACE INTO daily_verse_cache (id, date_key, book_id, chapter, start_verse, end_verse)
            VALUES (0, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(dateKey, ref.bookId, ref.chapter, ref.startVerse, ref.endVerse)
        )
    }

    /** The cached daily-verse reference, or null if the background worker hasn't populated it yet. */
    fun getCachedDailyVerse(context: Context): DailyVerseRef? {
        val db = getDatabase(context)
        val cursor = db.rawQuery(
            "SELECT book_id, chapter, start_verse, end_verse FROM daily_verse_cache WHERE id = 0",
            null
        )
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            DailyVerseRef(
                bookId = it.getInt(0),
                chapter = it.getInt(1),
                startVerse = it.getInt(2),
                endVerse = if (it.isNull(3)) null else it.getInt(3)
            )
        }
    }

    /**
     * SQLite has no `ADD COLUMN IF NOT EXISTS`; this makes adding a column to an
     * existing installation idempotent without a full migration framework. Safe to
     * call every time the DB opens — a no-op once the column exists.
     */
    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, definition: String) {
        val columnExists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.any { it == column }
        }
        if (!columnExists) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
            Log.d("BibleDB", "Added missing column $table.$column")
        }
    }

    fun isVersionDownloaded(context: Context, versionId: String): Boolean {
        if (versionId == "kjv") return true // always available
        val db = getDatabase(context)
        val cursor = db.rawQuery(
            "SELECT 1 FROM downloaded_versions WHERE id = ? LIMIT 1",
            arrayOf(versionId)
        )
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }

    /** All locally downloaded (non-bundled) versions, for badging the picker without one query per row. */
    fun getDownloadedVersions(context: Context): List<DownloadedVersionInfo> {
        val db = getDatabase(context)
        val results = mutableListOf<DownloadedVersionInfo>()
        val cursor = db.rawQuery("SELECT id, schema_version FROM downloaded_versions", null)
        cursor.use {
            while (it.moveToNext()) {
                results.add(DownloadedVersionInfo(id = it.getString(0), schemaVersion = it.getInt(1)))
            }
        }
        return results
    }

    /** Wipes a downloaded translation so it can be cleanly re-fetched (e.g. to pick up a newer schema). */
    fun deleteDownloadedVersion(context: Context, versionId: String) {
        if (versionId == "kjv") return // bundled, never deletable
        val db = getDatabase(context)
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM downloaded_verses WHERE version = ?", arrayOf(versionId))
            db.execSQL("DELETE FROM downloaded_footnotes WHERE version = ?", arrayOf(versionId))
            db.execSQL("DELETE FROM downloaded_book_names WHERE version = ?", arrayOf(versionId))
            db.execSQL("DELETE FROM downloaded_chapter_info WHERE version = ?", arrayOf(versionId))
            db.execSQL("DELETE FROM downloaded_versions WHERE id = ?", arrayOf(versionId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Downloads a whole translation via [remote] — a single bulk request under the
     * hood — and writes it in one batched transaction.
     *
     * IMPORTANT: all network I/O + JSON parsing happens inside
     * [BibleRemoteDataSource.downloadTranslation], fully before any transaction is
     * opened here. `SQLiteDatabase` ties an open transaction to the calling thread
     * via a thread-local session; `Dispatchers.IO` is free to resume a coroutine on
     * a *different* pool thread after it suspends on I/O, so awaiting network calls
     * inside `beginTransaction()/endTransaction()` intermittently makes a later
     * `execSQL`/`endTransaction()` run on a thread that has no record of the
     * transaction, throwing "Cannot perform this operation because there is no
     * current transaction" (and, since the connection stays checked out the whole
     * time, starves any other caller trying to touch the DB — including ones on the
     * main thread). The write loop below is purely synchronous `execSQL` calls with
     * no suspension points in between, so it can't hop threads mid-transaction.
     *
     * If the download itself fails (network error, malformed JSON, translation not
     * found), nothing is written and [translationId] is left un-downloaded for a
     * clean retry.
     */
    suspend fun downloadAndSaveVersion(
        context: Context,
        translationId: String,
        remote: BibleRemoteDataSource,
        onProgress: (Float) -> Unit = {}
    ): Result<VersionDownloadSummary> = withContext(Dispatchers.IO) {
        if (translationId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Translation id must not be blank"))
        }

        if (isVersionDownloaded(context, translationId)) {
            Log.d("BibleDB", "Version $translationId already downloaded, skipping")
            return@withContext Result.success(VersionDownloadSummary(0, 0, 0))
        }

        if (!NetworkUtils.isOnline(context)) {
            return@withContext Result.failure(IOException("No internet connection. Connect to the internet and try again."))
        }

        val downloaded: DownloadedTranslation = try {
            remote.downloadTranslation(translationId, onProgress)
        } catch (e: Exception) {
            Log.e("BibleDB", "Failed to download $translationId: ${e.message}")
            return@withContext Result.failure(e)
        }

        if (downloaded.verses.isEmpty()) {
            Log.e("BibleDB", "Translation $translationId has no usable content")
            return@withContext Result.failure(IOException("No content is available for this translation."))
        }

        if (downloaded.skippedBookCount > 0) {
            Log.d(
                "BibleDB",
                "Translation $translationId is a partial canon: ${downloaded.verses.size} verses downloaded, " +
                    "${downloaded.skippedBookCount} book(s) outside the supported 1-66 canon skipped"
            )
        }

        // --- Write phase: synchronous DB calls only, no suspension points. ---
        val db = getDatabase(context)
        try {
            db.beginTransaction()
            try {
                downloaded.verses.forEach { verse ->
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO downloaded_verses (version, book_id, chapter, verse, text, rich_content)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            translationId,
                            verse.bookId,
                            verse.chapter,
                            verse.verse,
                            verse.text,
                            verse.richContent?.encodeToJson()
                        )
                    )
                }

                downloaded.bookNames.forEach { bookName ->
                    db.execSQL(
                        "INSERT OR REPLACE INTO downloaded_book_names (version, book_id, name) VALUES (?, ?, ?)",
                        arrayOf(translationId, bookName.bookId, bookName.name)
                    )
                }

                downloaded.chapterInfo.forEach { chapterInfo ->
                    db.execSQL(
                        "INSERT OR REPLACE INTO downloaded_chapter_info (version, book_id, chapter, verse_count) VALUES (?, ?, ?, ?)",
                        arrayOf(translationId, chapterInfo.bookId, chapterInfo.chapter, chapterInfo.verseCount)
                    )
                }

                downloaded.footnotes.forEach { footnote ->
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO downloaded_footnotes (version, book_id, chapter, note_id, verse, caller, text)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            translationId,
                            footnote.bookId,
                            footnote.chapter,
                            footnote.noteId,
                            footnote.verse,
                            footnote.caller,
                            footnote.text
                        )
                    )
                }

                db.execSQL(
                    "INSERT OR REPLACE INTO downloaded_versions (id, name, downloaded_at, schema_version) VALUES (?, ?, ?, ?)",
                    arrayOf(
                        translationId,
                        downloaded.translationName,
                        System.currentTimeMillis(),
                        DownloadedVersionInfo.CURRENT_DOWNLOAD_SCHEMA_VERSION
                    )
                )
                db.setTransactionSuccessful()
                Log.d(
                    "BibleDB",
                    "Version $translationId saved successfully (${downloaded.verses.size} verses, " +
                        "${downloaded.footnotes.size} footnotes, ${downloaded.skippedBookCount} book(s) skipped)"
                )
                onProgress(1f)
                Result.success(
                    VersionDownloadSummary(
                        downloadedVerseCount = downloaded.verses.size,
                        downloadedChapterCount = downloaded.downloadedChapterCount,
                        skippedBookCount = downloaded.skippedBookCount
                    )
                )
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.e("BibleDB", "Failed to save version $translationId: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Routes to KJV_verses for "kjv", downloaded_verses for everything else.
     * KJV_verses has no rich_content column (it's a separate bundled schema, never
     * touched by the helloao download path), so it's selected as a literal NULL to
     * keep the cursor shape identical either way.
     */
    fun getVersesByChapter(context: Context, bookId: Int, chNum: Int, version: String): List<BibleVerse> {
        val db = getDatabase(context)

        val (table, richContentColumn, versionClause) = if (version == "kjv") {
            Triple("KJV_verses", "NULL", "")
        } else {
            Triple("downloaded_verses", "rich_content", "AND version = ?")
        }

        val args = if (version == "kjv") {
            arrayOf(bookId.toString(), chNum.toString())
        } else {
            arrayOf(bookId.toString(), chNum.toString(), version)
        }

        val cursor = db.rawQuery(
            "SELECT id, text, $richContentColumn, book_id, chapter, verse FROM $table " +
                "WHERE book_id = ? AND chapter = ? $versionClause ORDER BY verse",
            args
        )

        val verses = mutableListOf<BibleVerse>()
        while (cursor.moveToNext()) {
            verses.add(
                BibleVerse(
                    id = cursor.getInt(0),
                    text = cursor.getString(1),
                    richContent = decodeVerseContentOrNull(if (cursor.isNull(2)) null else cursor.getString(2)),
                    bookId = cursor.getInt(3),
                    chapter = cursor.getInt(4),
                    verse = cursor.getInt(5)
                )
            )
        }
        cursor.close()
        return verses
    }

    /**
     * Footnotes for one chapter of a downloaded (non-KJV) version. KJV has no
     * footnote table at all — it's the bundled asset DB, never touched by the
     * helloao path — so this simply returns empty for "kjv" rather than querying.
     */
    fun getFootnotesForChapter(context: Context, bookId: Int, chNum: Int, version: String): List<Footnote> {
        if (version == "kjv") return emptyList()
        val db = getDatabase(context)

        val cursor = db.rawQuery(
            "SELECT note_id, verse, caller, text FROM downloaded_footnotes " +
                "WHERE version = ? AND book_id = ? AND chapter = ? ORDER BY note_id",
            arrayOf(version, bookId.toString(), chNum.toString())
        )

        val footnotes = mutableListOf<Footnote>()
        cursor.use {
            while (it.moveToNext()) {
                footnotes.add(
                    Footnote(
                        noteId = it.getInt(0),
                        verse = it.getInt(1),
                        caller = if (it.isNull(2)) null else it.getString(2),
                        text = it.getString(3)
                    )
                )
            }
        }
        return footnotes
    }

    /**
     * Book names in [versionId]'s own language, keyed by book_id (1-66). Empty for "kjv"
     * (bundled, English-only — BibleBooks' hardcoded names already cover it) and for any
     * version downloaded before this table existed, in which case the caller falls back
     * to BibleBooks' English names.
     */
    fun getBookNames(context: Context, versionId: String): Map<Int, String> {
        if (versionId == "kjv") return emptyMap()
        val db = getDatabase(context)
        val cursor = db.rawQuery(
            "SELECT book_id, name FROM downloaded_book_names WHERE version = ?",
            arrayOf(versionId)
        )
        val names = mutableMapOf<Int, String>()
        cursor.use {
            while (it.moveToNext()) {
                names[it.getInt(0)] = it.getString(1)
            }
        }
        return names
    }

    /**
     * Chapter/verse structure for [versionId], as bookId -> (chapter -> verseCount).
     * Empty for "kjv" and for any version downloaded before this table existed, in
     * which case the caller falls back to BibleBooks' hardcoded (KJV-based) structure.
     * A book's chapter count is just the size of its inner map — there's no separate
     * "total chapters" row to keep in sync.
     */
    fun getChapterStructure(context: Context, versionId: String): Map<Int, Map<Int, Int>> {
        if (versionId == "kjv") return emptyMap()
        val db = getDatabase(context)
        val cursor = db.rawQuery(
            "SELECT book_id, chapter, verse_count FROM downloaded_chapter_info WHERE version = ?",
            arrayOf(versionId)
        )
        val structure = mutableMapOf<Int, MutableMap<Int, Int>>()
        cursor.use {
            while (it.moveToNext()) {
                val bookId = it.getInt(0)
                structure.getOrPut(bookId) { mutableMapOf() }[it.getInt(1)] = it.getInt(2)
            }
        }
        return structure
    }

    private fun buildNormalizedSql(textExpr: String): String {
        val replacements = listOf(
            "á" to "a", "é" to "e", "í" to "i",
            "ó" to "o", "ú" to "u", "ü" to "u", "ñ" to "n"
        )
        var sql = textExpr
        replacements.forEach { (from, to) ->
            sql = "REPLACE($sql,'$from','$to')"
        }
        return "LOWER($sql)"
    }

    suspend fun searchVerses(query: String, version: String): List<VerseUI> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val db = dbInstance ?: throw IllegalStateException("DB not initialized")
        val words = query.normalizeForSearch().split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return@withContext emptyList()

        try {
            val isKjv = version == "kjv"
            val table = if (isKjv) "KJV_verses" else "downloaded_verses"
            val normalizedCol = buildNormalizedSql("GROUP_CONCAT(text, ' ')")
            val havingConditions = words.joinToString(" AND ") { "$normalizedCol LIKE ?" }

            val whereClause = if (isKjv) "" else "WHERE version = ?"
            val args = buildList {
                if (!isKjv) add(version)
                words.forEach { add("%$it%") }
            }.toTypedArray()

            val cursor = db.rawQuery(
                """
                SELECT book_id, chapter, verse, GROUP_CONCAT(text, ' ') as fullText
                FROM $table
                $whereClause
                GROUP BY book_id, chapter, verse
                HAVING $havingConditions
                ORDER BY book_id, chapter, verse
                LIMIT 500
                """.trimIndent(),
                args
            )

            val results = mutableListOf<VerseUI>()
            cursor.use {
                while (it.moveToNext()) {
                    results.add(
                        VerseUI(
                            id = "${it.getInt(0)}_${it.getInt(1)}_${it.getInt(2)}".hashCode(),
                            text = it.getString(3),
                            bookId = it.getInt(0),
                            chapter = it.getInt(1),
                            verse = it.getInt(2),
                            isUserVerse = false,
                            isHighlighted = false,
                            highlightColor = 0x00000000
                        )
                    )
                }
            }
            results
        } catch (e: Exception) {
            Log.e("BibleDB", "Search error: ${e.message}")
            emptyList()
        }
    }

    // ---- Highlights & Notes (local-first; see ensureUserContentTables) ----

    private fun lastInsertRowId(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT last_insert_rowid()", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private const val HIGHLIGHT_COLUMNS =
        "local_id, remote_id, version_id, verses_json, color, created_at, updated_at, deleted_at, sync_status"

    private fun cursorToHighlight(cursor: Cursor): Highlight = Highlight(
        localId = cursor.getLong(0),
        remoteId = if (cursor.isNull(1)) null else cursor.getString(1),
        versionId = cursor.getString(2),
        verses = decodeVerseLocationsOrEmpty(cursor.getString(3)),
        color = cursor.getInt(4),
        createdAt = cursor.getString(5),
        updatedAt = cursor.getString(6),
        deletedAt = if (cursor.isNull(7)) null else cursor.getString(7),
        syncStatus = SyncStatus.fromStored(cursor.getString(8))
    )

    /** Saves locally right away (PENDING) — no account or network required; Phase E's sync worker pushes it later. */
    fun insertHighlight(context: Context, versionId: String, verses: List<VerseLocationDto>, color: Int): Highlight {
        val db = getDatabase(context)
        val now = isoTimestampNow()
        db.execSQL(
            """
            INSERT INTO highlights (version_id, verses_json, color, created_at, updated_at, sync_status)
            VALUES (?, ?, ?, ?, ?, 'PENDING')
            """.trimIndent(),
            arrayOf(versionId, verses.encodeVerseLocationsToJson(), color, now, now)
        )
        return Highlight(lastInsertRowId(db), null, versionId, verses, color, now, now, null, SyncStatus.PENDING)
    }

    /** Recoloring is the only post-creation edit for a highlight (contract decision 14). */
    fun recolorHighlight(context: Context, localId: Long, color: Int) {
        val db = getDatabase(context)
        db.execSQL(
            "UPDATE highlights SET color = ?, updated_at = ?, sync_status = 'PENDING' WHERE local_id = ?",
            arrayOf(color, isoTimestampNow(), localId)
        )
    }

    /** Soft delete, mirroring the server's tombstone model — the row stays until it's pushed. */
    fun softDeleteHighlight(context: Context, localId: Long) {
        val db = getDatabase(context)
        val now = isoTimestampNow()
        db.execSQL(
            "UPDATE highlights SET deleted_at = ?, updated_at = ?, sync_status = 'PENDING' WHERE local_id = ?",
            arrayOf(now, now, localId)
        )
    }

    /** Active highlights covering any verse in [bookId]/[chapter] — same "any entry matches" filter as the
     *  server's query params (contract decision 15), applied locally for rendering the reading view. */
    fun getHighlightsForChapter(context: Context, bookId: Int, chapter: Int): List<Highlight> =
        getDatabase(context).rawQuery("SELECT $HIGHLIGHT_COLUMNS FROM highlights WHERE deleted_at IS NULL", null)
            .use { cursor -> generateSequence { if (cursor.moveToNext()) cursorToHighlight(cursor) else null }.toList() }
            .filter { highlight -> highlight.verses.any { it.bookId == bookId && it.chapter == chapter } }

    /** All active highlights, most recently created first — for the Library screen. */
    fun getAllActiveHighlights(context: Context): List<Highlight> =
        getDatabase(context).rawQuery(
            "SELECT $HIGHLIGHT_COLUMNS FROM highlights WHERE deleted_at IS NULL ORDER BY created_at DESC",
            null
        ).use { cursor -> generateSequence { if (cursor.moveToNext()) cursorToHighlight(cursor) else null }.toList() }

    /** Every row (active or soft-deleted) still needing a push — the sync worker's queue (Phase E). */
    fun getPendingHighlights(context: Context): List<Highlight> =
        getDatabase(context).rawQuery(
            "SELECT $HIGHLIGHT_COLUMNS FROM highlights WHERE sync_status = 'PENDING'",
            null
        ).use { cursor -> generateSequence { if (cursor.moveToNext()) cursorToHighlight(cursor) else null }.toList() }

    private const val NOTE_COLUMNS =
        "local_id, remote_id, version_id, verses_json, text, created_at, updated_at, deleted_at, sync_status"

    private fun cursorToNote(cursor: Cursor): Note = Note(
        localId = cursor.getLong(0),
        remoteId = if (cursor.isNull(1)) null else cursor.getString(1),
        versionId = cursor.getString(2),
        verses = decodeVerseLocationsOrEmpty(cursor.getString(3)),
        text = cursor.getString(4),
        createdAt = cursor.getString(5),
        updatedAt = cursor.getString(6),
        deletedAt = if (cursor.isNull(7)) null else cursor.getString(7),
        syncStatus = SyncStatus.fromStored(cursor.getString(8))
    )

    fun insertNote(context: Context, versionId: String, verses: List<VerseLocationDto>, text: String): Note {
        val db = getDatabase(context)
        val now = isoTimestampNow()
        db.execSQL(
            """
            INSERT INTO notes (version_id, verses_json, text, created_at, updated_at, sync_status)
            VALUES (?, ?, ?, ?, ?, 'PENDING')
            """.trimIndent(),
            arrayOf(versionId, verses.encodeVerseLocationsToJson(), text, now, now)
        )
        return Note(lastInsertRowId(db), null, versionId, verses, text, now, now, null, SyncStatus.PENDING)
    }

    /** Both the verse list and text are editable in place for notes (contract decision 14). */
    fun updateNote(context: Context, localId: Long, verses: List<VerseLocationDto>, text: String) {
        val db = getDatabase(context)
        db.execSQL(
            "UPDATE notes SET verses_json = ?, text = ?, updated_at = ?, sync_status = 'PENDING' WHERE local_id = ?",
            arrayOf(verses.encodeVerseLocationsToJson(), text, isoTimestampNow(), localId)
        )
    }

    fun softDeleteNote(context: Context, localId: Long) {
        val db = getDatabase(context)
        val now = isoTimestampNow()
        db.execSQL(
            "UPDATE notes SET deleted_at = ?, updated_at = ?, sync_status = 'PENDING' WHERE local_id = ?",
            arrayOf(now, now, localId)
        )
    }

    /** Opened directly by a note's own editor screen, not just from an already-loaded list (contract's note-detail rationale). */
    fun getNoteById(context: Context, localId: Long): Note? =
        getDatabase(context).rawQuery(
            "SELECT $NOTE_COLUMNS FROM notes WHERE local_id = ? AND deleted_at IS NULL",
            arrayOf(localId.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursorToNote(cursor) else null }

    fun getNotesForChapter(context: Context, bookId: Int, chapter: Int): List<Note> =
        getDatabase(context).rawQuery("SELECT $NOTE_COLUMNS FROM notes WHERE deleted_at IS NULL", null)
            .use { cursor -> generateSequence { if (cursor.moveToNext()) cursorToNote(cursor) else null }.toList() }
            .filter { note -> note.verses.any { it.bookId == bookId && it.chapter == chapter } }

    /** All active notes, most recently created first — for the Library screen. */
    fun getAllActiveNotes(context: Context): List<Note> =
        getDatabase(context).rawQuery(
            "SELECT $NOTE_COLUMNS FROM notes WHERE deleted_at IS NULL ORDER BY created_at DESC",
            null
        ).use { cursor -> generateSequence { if (cursor.moveToNext()) cursorToNote(cursor) else null }.toList() }

    /** Every row (active or soft-deleted) still needing a push — the sync worker's queue (Phase E). */
    fun getPendingNotes(context: Context): List<Note> =
        getDatabase(context).rawQuery(
            "SELECT $NOTE_COLUMNS FROM notes WHERE sync_status = 'PENDING'",
            null
        ).use { cursor -> generateSequence { if (cursor.moveToNext()) cursorToNote(cursor) else null }.toList() }

    // ---- Sync worker support (Phase E) — see worker/SyncWorker.kt ----

    /** After a successful create/recolor push — stamps the server's id (a no-op change if this
     *  row already had one, e.g. a recolor) and timestamps, and clears PENDING. */
    fun markHighlightPushed(context: Context, localId: Long, remoteId: String, createdAt: String, updatedAt: String) {
        getDatabase(context).execSQL(
            "UPDATE highlights SET remote_id = ?, created_at = ?, updated_at = ?, sync_status = 'SYNCED' WHERE local_id = ?",
            arrayOf(remoteId, createdAt, updatedAt, localId)
        )
    }

    /** Hard-removes a local row once its deletion has been confirmed pushed (or, for a row that
     *  was created and soft-deleted again before ever reaching the server, immediately — there's
     *  nothing to push for a row the server never saw). Unlike [softDeleteHighlight], this isn't
     *  a tombstone; nothing references a purged row afterward. */
    fun purgeHighlight(context: Context, localId: Long) {
        getDatabase(context).execSQL("DELETE FROM highlights WHERE local_id = ?", arrayOf(localId))
    }

    /** Removes this device's copy of a highlight the server reports as deleted (a pulled
     *  tombstone) — a no-op if this device never had a synced copy of it. */
    fun deleteHighlightByRemoteId(context: Context, remoteId: String) {
        getDatabase(context).execSQL("DELETE FROM highlights WHERE remote_id = ?", arrayOf(remoteId))
    }

    /** Inserts or refreshes the local copy of a highlight from the server's authoritative state —
     *  either a highlight seen for the first time (created on another device) or this device's
     *  own row, freshly confirmed. Matched by [remoteId], not local_id, since the two devices
     *  don't share local row ids. */
    fun upsertHighlightFromServer(
        context: Context,
        remoteId: String,
        versionId: String,
        verses: List<VerseLocationDto>,
        color: Int,
        createdAt: String,
        updatedAt: String
    ) {
        val db = getDatabase(context)
        val versesJson = verses.encodeVerseLocationsToJson()
        val existingLocalId = db.rawQuery("SELECT local_id FROM highlights WHERE remote_id = ?", arrayOf(remoteId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        if (existingLocalId != null) {
            db.execSQL(
                """
                UPDATE highlights SET version_id = ?, verses_json = ?, color = ?, created_at = ?,
                    updated_at = ?, deleted_at = NULL, sync_status = 'SYNCED' WHERE local_id = ?
                """.trimIndent(),
                arrayOf(versionId, versesJson, color, createdAt, updatedAt, existingLocalId)
            )
        } else {
            db.execSQL(
                """
                INSERT INTO highlights (remote_id, version_id, verses_json, color, created_at, updated_at, sync_status)
                VALUES (?, ?, ?, ?, ?, ?, 'SYNCED')
                """.trimIndent(),
                arrayOf(remoteId, versionId, versesJson, color, createdAt, updatedAt)
            )
        }
    }

    /** See [markHighlightPushed] — the note equivalent. */
    fun markNotePushed(context: Context, localId: Long, remoteId: String, createdAt: String, updatedAt: String) {
        getDatabase(context).execSQL(
            "UPDATE notes SET remote_id = ?, created_at = ?, updated_at = ?, sync_status = 'SYNCED' WHERE local_id = ?",
            arrayOf(remoteId, createdAt, updatedAt, localId)
        )
    }

    /** See [purgeHighlight] — the note equivalent. */
    fun purgeNote(context: Context, localId: Long) {
        getDatabase(context).execSQL("DELETE FROM notes WHERE local_id = ?", arrayOf(localId))
    }

    /** See [deleteHighlightByRemoteId] — the note equivalent. */
    fun deleteNoteByRemoteId(context: Context, remoteId: String) {
        getDatabase(context).execSQL("DELETE FROM notes WHERE remote_id = ?", arrayOf(remoteId))
    }

    /** See [upsertHighlightFromServer] — the note equivalent (text instead of color). */
    fun upsertNoteFromServer(
        context: Context,
        remoteId: String,
        versionId: String,
        verses: List<VerseLocationDto>,
        text: String,
        createdAt: String,
        updatedAt: String
    ) {
        val db = getDatabase(context)
        val versesJson = verses.encodeVerseLocationsToJson()
        val existingLocalId = db.rawQuery("SELECT local_id FROM notes WHERE remote_id = ?", arrayOf(remoteId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        if (existingLocalId != null) {
            db.execSQL(
                """
                UPDATE notes SET version_id = ?, verses_json = ?, text = ?, created_at = ?,
                    updated_at = ?, deleted_at = NULL, sync_status = 'SYNCED' WHERE local_id = ?
                """.trimIndent(),
                arrayOf(versionId, versesJson, text, createdAt, updatedAt, existingLocalId)
            )
        } else {
            db.execSQL(
                """
                INSERT INTO notes (remote_id, version_id, verses_json, text, created_at, updated_at, sync_status)
                VALUES (?, ?, ?, ?, ?, ?, 'SYNCED')
                """.trimIndent(),
                arrayOf(remoteId, versionId, versesJson, text, createdAt, updatedAt)
            )
        }
    }
}