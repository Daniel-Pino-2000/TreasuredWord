package com.application.bibleapp.data.local

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.BibleVerse
import com.application.bibleapp.data.model.DailyVerseRef
import com.application.bibleapp.data.model.DownloadedVersionInfo
import com.application.bibleapp.data.model.Footnote
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.Note
import com.application.bibleapp.data.model.SyncStatus
import com.application.bibleapp.data.model.TranslationLicense
import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.data.model.decodeVerseContentOrNull
import com.application.bibleapp.data.model.encodeToJson
import com.application.bibleapp.data.remote.BibleRemoteDataSource
import com.application.bibleapp.data.remote.DownloadedTranslation
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.data.remote.decodeVerseLocationsOrEmpty
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.data.remote.encodeToJson as encodeVerseLocationsToJson
import com.application.bibleapp.utils.NetworkUtils
import com.application.bibleapp.utils.TextUtils.normalizeForSearch
import com.application.bibleapp.utils.isoTimestampNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
 * keeps the asset's verses unchanged; the only thing added to it is the derived
 * `search_text` column (see [ensureSearchTextColumns]). Everything downloaded from
 * the helloao API lands in three separate tables instead — `downloaded_versions`
 * (one row per downloaded translation, plus [DownloadedVersionInfo.schemaVersion]
 * recording which shape of [downloadAndSaveVersion] wrote it), `downloaded_verses`
 * (plain `text`, normalized `search_text` for search, `rich_content` for
 * rendering — see [encodeToJson]/[decodeVerseContentOrNull]),
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
    // Bump this whenever the bundled asset changes (e.g. a corrected KJV_verses). Existing
    // installs then refresh just the asset's own tables — see [refreshBundledTables].
    private const val DB_VERSION = 2
    @Volatile private var dbInstance: SQLiteDatabase? = null

    /**
     * Makes sure the on-device DB exists and matches [DB_VERSION]. The version is tracked in a
     * tiny file next to the DB, written only once the DB is fully in place, so a crash halfway
     * through just means the same step runs again on the next launch.
     *
     * - No DB yet (fresh install): copy the asset into place.
     * - Older version: the file also holds highlights, notes and downloaded translations, so it's
     *   never replaced. Only the tables the asset ships are refreshed in place.
     */
    private fun copyDatabaseIfNeeded(context: Context): File {
        val dbFile = context.getDatabasePath(DB_NAME)
        val versionFile = File(dbFile.parent, "${DB_NAME}.ver")

        val installedVersion = if (versionFile.exists()) versionFile.readText().trim().toIntOrNull() ?: 0 else 0

        try {
            if (!dbFile.exists()) {
                copyAsset(context, dbFile)
                versionFile.writeText(DB_VERSION.toString())
                // The fresh copy has no highlights or notes, so any sync watermark left in prefs
                // (e.g. restored from a cloud backup, which doesn't carry this DB) is stale.
                BibleRepository.clearSyncState(context)
                Log.d("BibleDB", "Database copied (schema version $DB_VERSION)")
            } else if (installedVersion < DB_VERSION) {
                refreshBundledTables(context, dbFile)
                versionFile.writeText(DB_VERSION.toString())
                Log.d("BibleDB", "Bundled tables refreshed (schema version $installedVersion -> $DB_VERSION)")
            } else {
                Log.d("BibleDB", "Database up to date (version $installedVersion)")
            }
        } catch (e: Exception) {
            Log.e("BibleDB", "Error installing database: ${e.message}")
            throw e
        }

        return dbFile
    }

    /** Copies the asset to [target] via a temp file and a rename, so [target] never exists half-written. */
    private fun copyAsset(context: Context, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parent, "${target.name}.tmp")
        context.assets.open(DB_NAME).use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Couldn't move ${temp.name} to ${target.name}")
        }
    }

    /**
     * Replaces every table the asset ships (today `KJV_verses`, `KJV_books`, `translations`)
     * with the new asset's copy, in one transaction, leaving every other table untouched.
     * Which tables count as "bundled" is read from the asset itself, so the asset must never
     * contain the app's own tables (highlights, notes, downloaded_*): they'd be overwritten.
     *
     * Runs on its own short-lived connection, since ATTACH would otherwise stick to the shared one.
     */
    private fun refreshBundledTables(context: Context, dbFile: File) {
        val bundledFile = File(context.cacheDir, "$DB_NAME.bundled")
        copyAsset(context, bundledFile)
        try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                db.execSQL("ATTACH DATABASE ? AS bundled", arrayOf(bundledFile.path))
                // sqlite_sequence is maintained by SQLite itself (refilled by the inserts below);
                // autoindexes have no SQL and come back with their table.
                val schema = db.rawQuery(
                    "SELECT type, name, sql FROM bundled.sqlite_master " +
                        "WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' " +
                        "ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END",
                    null
                ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) } }

                db.beginTransaction()
                try {
                    for ((type, name, sql) in schema) {
                        if (type == "table") {
                            db.execSQL("DROP TABLE IF EXISTS main.\"$name\"")
                            db.execSQL(sql)
                            db.execSQL("INSERT INTO main.\"$name\" SELECT * FROM bundled.\"$name\"")
                        } else {
                            db.execSQL("DROP ${type.uppercase()} IF EXISTS main.\"$name\"")
                            db.execSQL(sql)
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                db.execSQL("DETACH DATABASE bundled")
            } finally {
                db.close()
            }
        } finally {
            bundledFile.delete()
        }
    }

    /**
     * Synchronized because the UI and background workers all reach for the DB at startup;
     * without it, each one copied the asset over the same file and opened its own connection.
     */
    @Synchronized
    fun getDatabase(context: Context): SQLiteDatabase {
        dbInstance?.takeIf { it.isOpen }?.let { return it }
        // Close stale instance before reopening
        dbInstance?.close()
        val dbFile = copyDatabaseIfNeeded(context)
        val db = SQLiteDatabase.openDatabase(
            dbFile.path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        ensureDownloadedVersionsTable(db)
        ensureUserContentTables(db)
        ensureSearchTextColumns(db)
        dbInstance = db
        return db
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
        // The translation's full title and its TranslationLicense as JSON, for the reader's credit
        // offline (#98). Null on older rows until saveMissingCredits fills them in from the catalog.
        addColumnIfMissing(db, "downloaded_versions", "full_name", "TEXT")
        addColumnIfMissing(db, "downloaded_versions", "license", "TEXT")

        // Separate table for downloaded verses — never mixed with bundled KJV.
        // rich_content is nullable JSON (StoredVerseContent) alongside plain `text` —
        // `text` stays plain on purpose (search's `search_text` is made from it); only
        // the reading screen looks at rich_content.
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

    /**
     * Each verse's text as search matches it, normalized in Kotlin by the same
     * [normalizeForSearch] as the query (#90). SQL alone can't do that: `LOWER` only knows A-Z,
     * and folding accents with nested `REPLACE`s covered only Spanish and slowed every search.
     * New downloads fill it as they're written; the bundled KJV (whose table is replaced
     * without it whenever the asset changes) and versions downloaded before it existed are
     * filled by [ensureSearchText] once, when they're first opened or searched.
     */
    private fun ensureSearchTextColumns(db: SQLiteDatabase) {
        addColumnIfMissing(db, "KJV_verses", "search_text", "TEXT")
        addColumnIfMissing(db, "downloaded_verses", "search_text", "TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS search_text_state (id INTEGER PRIMARY KEY CHECK (id = 0), normalizer_version INTEGER NOT NULL)"
        )
        val storedVersion = db.rawQuery("SELECT normalizer_version FROM search_text_state WHERE id = 0", null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        if (storedVersion < SEARCH_TEXT_VERSION) {
            // Text normalized by an older [normalizeForSearch] wouldn't match today's queries.
            db.beginTransaction()
            try {
                db.execSQL("UPDATE KJV_verses SET search_text = NULL")
                db.execSQL("UPDATE downloaded_verses SET search_text = NULL")
                db.execSQL("INSERT OR REPLACE INTO search_text_state (id, normalizer_version) VALUES (0, ?)", arrayOf(SEARCH_TEXT_VERSION))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        synchronized(searchTextReady) { searchTextReady.clear() }
    }

    /** Bump whenever [normalizeForSearch] changes, so every stored `search_text` is rebuilt. */
    private const val SEARCH_TEXT_VERSION = 1

    /** Versions whose `search_text` is known to be filled in, so the check runs once per launch. */
    private val searchTextReady = mutableSetOf<String>()

    /**
     * Fills in `search_text` for any of [version]'s verses that don't have it yet: a one-time
     * cost of a second or two for a whole Bible, normally paid in the background by
     * [prepareSearch] when the version is opened. Synchronized so a search arriving meanwhile
     * waits for it instead of doing it again.
     */
    private fun ensureSearchText(db: SQLiteDatabase, version: String) = synchronized(searchTextReady) {
        if (version in searchTextReady) return@synchronized
        val isKjv = version == "kjv"
        val table = verseTable(version)
        // Normalized as they're read, so the write transaction below only writes.
        val missing = db.rawQuery(
            "SELECT id, text FROM $table WHERE search_text IS NULL" + if (isKjv) "" else " AND version = ?",
            if (isKjv) null else arrayOf(version)
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1).orEmpty().normalizeForSearch()) }
        }

        if (missing.isNotEmpty()) {
            db.beginTransaction()
            try {
                db.compileStatement("UPDATE $table SET search_text = ? WHERE id = ?").use { statement ->
                    missing.forEach { (id, searchText) ->
                        statement.bindString(1, searchText)
                        statement.bindLong(2, id)
                        statement.executeUpdateDelete()
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Log.d("BibleDB", "Filled search_text for ${missing.size} verses of $version")
        }
        searchTextReady += version
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
        val cursor = db.rawQuery("SELECT id, schema_version, name, full_name, license FROM downloaded_versions", null)
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    DownloadedVersionInfo(
                        id = it.getString(0),
                        schemaVersion = it.getInt(1),
                        name = it.getString(2),
                        fullName = if (it.isNull(3)) "" else it.getString(3),
                        license = if (it.isNull(4)) null else decodeLicenseOrNull(it.getString(4))
                    )
                )
            }
        }
        return results
    }

    /**
     * Gives downloads made before credits were saved (#98) their title and license from the
     * catalog entry [catalog], so their credit keeps working offline afterwards. Only fills empty
     * columns: what was saved with the download describes the text actually on the device.
     */
    fun saveMissingCredits(context: Context, catalog: List<BibleTranslation>) {
        if (catalog.isEmpty()) return
        val db = getDatabase(context)
        db.beginTransaction()
        try {
            catalog.forEach { version ->
                db.execSQL(
                    """
                    UPDATE downloaded_versions
                    SET full_name = COALESCE(full_name, ?), license = COALESCE(license, ?)
                    WHERE id = ? AND (full_name IS NULL OR license IS NULL)
                    """.trimIndent(),
                    arrayOf(
                        version.nativeName,
                        version.license?.let { licenseJson.encodeToString(TranslationLicense.serializer(), it) },
                        version.id
                    )
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private val licenseJson = Json { ignoreUnknownKeys = true }

    private fun decodeLicenseOrNull(json: String): TranslationLicense? = try {
        licenseJson.decodeFromString(TranslationLicense.serializer(), json)
    } catch (e: SerializationException) {
        Log.w("BibleDB", "Unreadable saved license, ignoring: ${e.message}")
        null
    } catch (e: IllegalArgumentException) {
        Log.w("BibleDB", "Unreadable saved license, ignoring: ${e.message}")
        null
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
            return@withContext Result.failure(NoInternetException())
        }

        val downloaded: DownloadedTranslation = try {
            remote.downloadTranslation(translationId, onProgress)
        } catch (e: Exception) {
            Log.e("BibleDB", "Failed to download $translationId: ${e.message}")
            return@withContext Result.failure(e)
        }

        if (downloaded.verses.isEmpty()) {
            Log.e("BibleDB", "Translation $translationId has no usable content")
            return@withContext Result.failure(NoContentException())
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
                        INSERT OR REPLACE INTO downloaded_verses (version, book_id, chapter, verse, text, rich_content, search_text)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            translationId,
                            verse.bookId,
                            verse.chapter,
                            verse.verse,
                            verse.text,
                            verse.richContent?.encodeToJson(),
                            verse.text.normalizeForSearch()
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
                    "INSERT OR REPLACE INTO downloaded_versions (id, name, downloaded_at, schema_version, full_name, license) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        translationId,
                        downloaded.translationName,
                        System.currentTimeMillis(),
                        DownloadedVersionInfo.CURRENT_DOWNLOAD_SCHEMA_VERSION,
                        downloaded.fullName.ifBlank { null },
                        downloaded.license?.let { licenseJson.encodeToString(TranslationLicense.serializer(), it) }
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

    private fun verseTable(version: String) = if (version == "kjv") "KJV_verses" else "downloaded_verses"

    /**
     * The WHERE clause and arguments shared by [hasSearchMatch] and [searchVerses]: every word of
     * the normalized [query] must appear somewhere in the verse's `search_text` (parts of words
     * count). Null if the query has nothing searchable in it. Each table holds one row per verse
     * (downloaded_verses is UNIQUE on it, and the KJV asset has 31,102 rows for 31,102 verses),
     * so rows are matched directly instead of grouped into verses first.
     */
    private fun searchCondition(query: String, version: String): Pair<String, Array<String>>? {
        val words = query.normalizeForSearch().split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val isKjv = version == "kjv"
        val conditions = buildList {
            if (!isKjv) add("version = ?")
            words.forEach { _ -> add("search_text LIKE ?") }
        }.joinToString(" AND ")
        val args = buildList {
            if (!isKjv) add(version)
            words.forEach { add("%$it%") }
        }.toTypedArray()
        return conditions to args
    }

    /**
     * Fills in [version]'s missing `search_text` ahead of its first search, from the background
     * when a version is opened, so neither Search nor its suggestion checks wait for it.
     */
    suspend fun prepareSearch(context: Context, version: String) = withContext(Dispatchers.IO) {
        ensureSearchText(getDatabase(context), version)
    }

    /**
     * Whether a word search for [query] would find anything in [version], matched exactly as
     * [searchVerses] matches, with LIMIT 1 so it's cheap enough to run for each Search
     * suggestion (#88).
     */
    suspend fun hasSearchMatch(context: Context, query: String, version: String): Boolean = withContext(Dispatchers.IO) {
        val db = getDatabase(context)
        val (conditions, args) = searchCondition(query, version) ?: return@withContext false
        ensureSearchText(db, version)
        db.rawQuery("SELECT 1 FROM ${verseTable(version)} WHERE $conditions LIMIT 1", args).use { it.moveToFirst() }
    }

    suspend fun searchVerses(query: String, version: String): List<VerseUI> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val db = dbInstance ?: throw IllegalStateException("DB not initialized")
        val (conditions, args) = searchCondition(query, version) ?: return@withContext emptyList()

        try {
            ensureSearchText(db, version)
            val cursor = db.rawQuery(
                """
                SELECT book_id, chapter, verse, text
                FROM ${verseTable(version)}
                WHERE $conditions
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
            // Rethrown so the Search screen can show an error instead of a misleading "no results".
            Log.e("BibleDB", "Search error: ${e.message}")
            throw e
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

    /** Undo for [softDeleteHighlight]: clears the tombstone and re-queues the row for sync. If a
     *  sync already purged the row (pushed delete → [purgeHighlight]) there's nothing to clear, so
     *  it's re-created from [highlight] instead — same verses/color, new local id. */
    fun restoreHighlight(context: Context, highlight: Highlight) {
        val db = getDatabase(context)
        val restored = db.compileStatement(
            "UPDATE highlights SET deleted_at = NULL, updated_at = ?, sync_status = 'PENDING' WHERE local_id = ? AND deleted_at IS NOT NULL"
        ).use { statement ->
            statement.bindString(1, isoTimestampNow())
            statement.bindLong(2, highlight.localId)
            statement.executeUpdateDelete() > 0
        }
        if (!restored) insertHighlight(context, highlight.versionId, highlight.verses, highlight.color)
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

    /** Undo for [softDeleteNote] — see [restoreHighlight]. */
    fun restoreNote(context: Context, note: Note) {
        val db = getDatabase(context)
        val restored = db.compileStatement(
            "UPDATE notes SET deleted_at = NULL, updated_at = ?, sync_status = 'PENDING' WHERE local_id = ? AND deleted_at IS NOT NULL"
        ).use { statement ->
            statement.bindString(1, isoTimestampNow())
            statement.bindLong(2, note.localId)
            statement.executeUpdateDelete() > 0
        }
        if (!restored) insertNote(context, note.versionId, note.verses, note.text)
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

    // ---- Sync support (Phase E) — see worker/SyncEngine.kt ----

    /** Runs an UPDATE/DELETE and returns how many rows it touched — [SQLiteDatabase.execSQL] can't. */
    private fun SQLiteDatabase.executeCounting(sql: String, vararg args: Any?): Int =
        compileStatement(sql).use { statement ->
            args.forEachIndexed { index, arg ->
                when (arg) {
                    null -> statement.bindNull(index + 1)
                    is String -> statement.bindString(index + 1, arg)
                    is Int -> statement.bindLong(index + 1, arg.toLong())
                    is Long -> statement.bindLong(index + 1, arg)
                    else -> error("Unsupported SQL argument: $arg")
                }
            }
            statement.executeUpdateDelete()
        }

    /**
     * After a successful create/recolor push — stamps the server's id and timestamps and clears
     * PENDING, but only if the row is unchanged since [pushedUpdatedAt] was read for the push.
     * A recolor or delete made while the request was out (#83) would otherwise be marked synced
     * without ever being sent; instead the row keeps PENDING, gets the server's id (so its next
     * push is an update or delete, not a second create), and goes out again next pass.
     */
    fun markHighlightPushed(
        context: Context,
        localId: Long,
        pushedUpdatedAt: String,
        remoteId: String,
        createdAt: String,
        updatedAt: String
    ): Boolean {
        val db = getDatabase(context)
        val marked = db.executeCounting(
            "UPDATE highlights SET remote_id = ?, created_at = ?, updated_at = ?, sync_status = 'SYNCED' WHERE local_id = ? AND updated_at = ?",
            remoteId, createdAt, updatedAt, localId, pushedUpdatedAt
        ) > 0
        if (!marked) db.executeCounting("UPDATE highlights SET remote_id = ? WHERE local_id = ?", remoteId, localId)
        return marked
    }

    /** Hard-removes a local row once its deletion has been confirmed pushed (or, for a row that
     *  was created and soft-deleted again before ever reaching the server, immediately — there's
     *  nothing to push for a row the server never saw). Unlike [softDeleteHighlight], this isn't
     *  a tombstone; nothing references a purged row afterward. If it was restored (Undo) while
     *  the delete was out, it's kept and loses its remote id instead: the server's copy is now a
     *  tombstone, so the next pass creates it again (#83). */
    fun purgeHighlight(context: Context, localId: Long, pushedUpdatedAt: String) {
        val db = getDatabase(context)
        val purged = db.executeCounting("DELETE FROM highlights WHERE local_id = ? AND updated_at = ?", localId, pushedUpdatedAt) > 0
        if (!purged) db.executeCounting("UPDATE highlights SET remote_id = NULL WHERE local_id = ?", localId)
    }

    /** The server answered 404 for this row's remote id (it isn't in this account): forget the id
     *  and keep the row PENDING, so the next pass creates it instead of retrying forever. */
    fun detachHighlight(context: Context, localId: Long) {
        getDatabase(context).executeCounting(
            "UPDATE highlights SET remote_id = NULL, sync_status = 'PENDING' WHERE local_id = ?", localId
        )
    }

    /** Removes this device's copy of a highlight the server reports as deleted (a pulled
     *  tombstone) — false if this device had no copy of it. A deletion from another device wins
     *  over an unpushed local edit to the same highlight. */
    fun deleteHighlightByRemoteId(context: Context, remoteId: String): Boolean =
        getDatabase(context).executeCounting("DELETE FROM highlights WHERE remote_id = ?", remoteId) > 0

    /** Inserts or refreshes the local copy of a highlight from the server's authoritative state —
     *  either a highlight seen for the first time (created on another device) or this device's
     *  own row, freshly confirmed. Matched by [remoteId], not local_id, since the two devices
     *  don't share local row ids. A row that's PENDING here (its push failed, or it changed
     *  mid-push) is left alone so the local change isn't lost; it's pushed next pass (#83).
     *  Returns whether anything changed, so screens only reload when they need to (#79). */
    fun upsertHighlightFromServer(
        context: Context,
        remoteId: String,
        versionId: String,
        verses: List<VerseLocationDto>,
        color: Int,
        createdAt: String,
        updatedAt: String
    ): Boolean {
        val db = getDatabase(context)
        val versesJson = verses.encodeVerseLocationsToJson()
        val existing = db.rawQuery("SELECT local_id, sync_status FROM highlights WHERE remote_id = ?", arrayOf(remoteId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) to SyncStatus.fromStored(cursor.getString(1)) else null }
        if (existing != null) {
            val (localId, status) = existing
            if (status == SyncStatus.PENDING) return false
            return db.executeCounting(
                """
                UPDATE highlights SET version_id = ?, verses_json = ?, color = ?, created_at = ?,
                    updated_at = ?, deleted_at = NULL, sync_status = 'SYNCED'
                WHERE local_id = ? AND sync_status = 'SYNCED' AND (version_id IS NOT ? OR verses_json IS NOT ?
                    OR color IS NOT ? OR created_at IS NOT ? OR updated_at IS NOT ? OR deleted_at IS NOT NULL)
                """.trimIndent(),
                versionId, versesJson, color, createdAt, updatedAt,
                localId, versionId, versesJson, color, createdAt, updatedAt
            ) > 0
        }
        db.execSQL(
            """
            INSERT INTO highlights (remote_id, version_id, verses_json, color, created_at, updated_at, sync_status)
            VALUES (?, ?, ?, ?, ?, ?, 'SYNCED')
            """.trimIndent(),
            arrayOf(remoteId, versionId, versesJson, color, createdAt, updatedAt)
        )
        return true
    }

    /** See [markHighlightPushed] — the note equivalent. */
    fun markNotePushed(
        context: Context,
        localId: Long,
        pushedUpdatedAt: String,
        remoteId: String,
        createdAt: String,
        updatedAt: String
    ): Boolean {
        val db = getDatabase(context)
        val marked = db.executeCounting(
            "UPDATE notes SET remote_id = ?, created_at = ?, updated_at = ?, sync_status = 'SYNCED' WHERE local_id = ? AND updated_at = ?",
            remoteId, createdAt, updatedAt, localId, pushedUpdatedAt
        ) > 0
        if (!marked) db.executeCounting("UPDATE notes SET remote_id = ? WHERE local_id = ?", remoteId, localId)
        return marked
    }

    /** See [purgeHighlight] — the note equivalent. */
    fun purgeNote(context: Context, localId: Long, pushedUpdatedAt: String) {
        val db = getDatabase(context)
        val purged = db.executeCounting("DELETE FROM notes WHERE local_id = ? AND updated_at = ?", localId, pushedUpdatedAt) > 0
        if (!purged) db.executeCounting("UPDATE notes SET remote_id = NULL WHERE local_id = ?", localId)
    }

    /** See [detachHighlight] — the note equivalent. */
    fun detachNote(context: Context, localId: Long) {
        getDatabase(context).executeCounting(
            "UPDATE notes SET remote_id = NULL, sync_status = 'PENDING' WHERE local_id = ?", localId
        )
    }

    /** See [deleteHighlightByRemoteId] — the note equivalent. */
    fun deleteNoteByRemoteId(context: Context, remoteId: String): Boolean =
        getDatabase(context).executeCounting("DELETE FROM notes WHERE remote_id = ?", remoteId) > 0

    /** See [upsertHighlightFromServer] — the note equivalent (text instead of color). */
    fun upsertNoteFromServer(
        context: Context,
        remoteId: String,
        versionId: String,
        verses: List<VerseLocationDto>,
        text: String,
        createdAt: String,
        updatedAt: String
    ): Boolean {
        val db = getDatabase(context)
        val versesJson = verses.encodeVerseLocationsToJson()
        val existing = db.rawQuery("SELECT local_id, sync_status FROM notes WHERE remote_id = ?", arrayOf(remoteId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) to SyncStatus.fromStored(cursor.getString(1)) else null }
        if (existing != null) {
            val (localId, status) = existing
            if (status == SyncStatus.PENDING) return false
            return db.executeCounting(
                """
                UPDATE notes SET version_id = ?, verses_json = ?, text = ?, created_at = ?,
                    updated_at = ?, deleted_at = NULL, sync_status = 'SYNCED'
                WHERE local_id = ? AND sync_status = 'SYNCED' AND (version_id IS NOT ? OR verses_json IS NOT ?
                    OR text IS NOT ? OR created_at IS NOT ? OR updated_at IS NOT ? OR deleted_at IS NOT NULL)
                """.trimIndent(),
                versionId, versesJson, text, createdAt, updatedAt,
                localId, versionId, versesJson, text, createdAt, updatedAt
            ) > 0
        }
        db.execSQL(
            """
            INSERT INTO notes (remote_id, version_id, verses_json, text, created_at, updated_at, sync_status)
            VALUES (?, ?, ?, ?, ?, ?, 'SYNCED')
            """.trimIndent(),
            arrayOf(remoteId, versionId, versesJson, text, createdAt, updatedAt)
        )
        return true
    }

    /** Highlights and notes not yet on the server — what signing out would lose (#81). A row
     *  created and deleted again before it was ever pushed doesn't count: there's nothing to lose. */
    fun countPendingChanges(context: Context): Int {
        val db = getDatabase(context)
        val pendingClause = "sync_status = 'PENDING' AND NOT (deleted_at IS NOT NULL AND remote_id IS NULL)"
        return listOf("highlights", "notes").sumOf { table ->
            db.rawQuery("SELECT COUNT(*) FROM $table WHERE $pendingClause", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }
    }

    // ---- Account changes (#81) — see BibleRepository.removeAccountContent/adoptContentOwner ----

    /** Signing out removes the account's highlights and notes from this device; they're safe on
     *  the server and come back on the next sign-in. */
    fun deleteAllUserContent(context: Context) {
        val db = getDatabase(context)
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM highlights")
            db.execSQL("DELETE FROM notes")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Signing into a different account than the one this device's content was synced with:
     *  removes everything that came from (or went to) the old account, keeping only items made
     *  here that were never uploaded anywhere. */
    fun deleteContentSyncedToAnAccount(context: Context) {
        val db = getDatabase(context)
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM highlights WHERE remote_id IS NOT NULL")
            db.execSQL("DELETE FROM notes WHERE remote_id IS NOT NULL")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- Account deletion cascade (Phase H) — see BibleRepository.detachLocalContentFromDeletedAccount ----

    /** A still-pending tombstone has nothing left to push once the account is gone, so it's
     *  purged outright; every other row is detached back to local-only PENDING content instead
     *  of left pointing at a `remote_id` that will 404 forever (the Phase E orphaned-row bug,
     *  guaranteed to recur here otherwise since account deletion invalidates every remote_id at
     *  once). */
    fun detachHighlightsFromAccount(context: Context) {
        val db = getDatabase(context)
        db.execSQL("DELETE FROM highlights WHERE deleted_at IS NOT NULL")
        db.execSQL("UPDATE highlights SET remote_id = NULL, sync_status = 'PENDING' WHERE remote_id IS NOT NULL")
    }

    /** See [detachHighlightsFromAccount] — the note equivalent. */
    fun detachNotesFromAccount(context: Context) {
        val db = getDatabase(context)
        db.execSQL("DELETE FROM notes WHERE deleted_at IS NOT NULL")
        db.execSQL("UPDATE notes SET remote_id = NULL, sync_status = 'PENDING' WHERE remote_id IS NOT NULL")
    }
}

/** A version download was refused because the device is offline. The UI shows its own,
 *  translated message for it; [message] is for logs. */
class NoInternetException : IOException("No internet connection")

/** A version downloaded fine but held no usable verses. */
class NoContentException : IOException("No content is available for this translation")
