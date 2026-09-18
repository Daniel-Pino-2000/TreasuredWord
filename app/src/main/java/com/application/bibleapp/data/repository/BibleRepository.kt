package com.application.bibleapp.data.repository

import android.content.Context
import android.util.Log
import com.application.bibleapp.data.local.BibleDatabaseManager
import com.application.bibleapp.data.local.VersionDownloadSummary
import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.DEFAULT_VERSION
import com.application.bibleapp.data.model.DailyVerseRef
import com.application.bibleapp.data.model.DownloadedVersionInfo
import com.application.bibleapp.data.model.Footnote
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.Note
import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.data.model.toUI
import com.application.bibleapp.data.remote.BibleRemoteDataSource
import com.application.bibleapp.data.remote.DailyVerseDataSource
import com.application.bibleapp.data.remote.HttpClientProvider
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.VerseTextScale
import com.application.bibleapp.utils.NetworkUtils
import com.application.bibleapp.worker.DailyVerseScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.util.Calendar

/**
 * Year + day-of-year — descriptive metadata only (which day a cached row was fetched
 * for), not used to decide cache hit/miss: freshness is [DailyVerseFetchWorker]'s job
 * now, this is just what gets stamped on the row when it writes one.
 */
internal fun dailyVerseDateKey(now: Calendar = Calendar.getInstance()): String =
    "${now.get(Calendar.YEAR)}-${now.get(Calendar.DAY_OF_YEAR)}"

/** The last book/chapter/verse the user had open, restored on app launch. */
data class ReadingPosition(val bookId: Int, val chapter: Int, val verse: Int)

/** The user's chosen local wall-clock time for the daily verse reminder. */
data class NotificationTime(val hour: Int, val minute: Int)

/** On-disk cache of [BibleRepository.getAllVersions]'s result — a wrapper so the JSON file has a stable top-level shape. */
@Serializable
private data class CachedTranslations(val translations: List<BibleTranslation>)

/**
 * Sits between [BibleViewModel][com.application.bibleapp.viewmodel.BibleViewModel] and
 * the two data sources it coordinates: [remote] (the [BibleRemoteDataSource]
 * interface, currently backed by the helloao API client) for the translation
 * catalog and downloads, and [BibleDatabaseManager] for everything already on
 * disk. The ViewModel never talks to either directly — this is the one place
 * that decides *where* a piece of data comes from, so swapping the remote
 * source (or adding a cache layer) later only touches this class.
 */
class BibleRepository(
    private val context: Context,
    private val remote: BibleRemoteDataSource,
    private val daily: DailyVerseDataSource
) {
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences("bible_prefs", Context.MODE_PRIVATE)
    }

    suspend fun getChapter(bookId: Int, chapter: Int, versionId: String): List<VerseUI> =
        withContext(Dispatchers.IO) {
            BibleDatabaseManager
                .getVersesByChapter(context, bookId, chapter, versionId)
                .map { it.toUI() }
        }

    suspend fun getFootnotes(bookId: Int, chapter: Int, versionId: String): List<Footnote> =
        withContext(Dispatchers.IO) {
            BibleDatabaseManager.getFootnotesForChapter(context, bookId, chapter, versionId)
        }

    suspend fun searchVerses(query: String, versionId: String): List<VerseUI> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) emptyList()
            else BibleDatabaseManager.searchVerses(query, versionId)
        }

    // ---- Highlights & Notes (local-first; see data/local/BibleDatabaseManager.kt) ----

    suspend fun getHighlightsForChapter(bookId: Int, chapter: Int): List<Highlight> =
        withContext(Dispatchers.IO) { BibleDatabaseManager.getHighlightsForChapter(context, bookId, chapter) }

    suspend fun getAllActiveHighlights(): List<Highlight> =
        withContext(Dispatchers.IO) { BibleDatabaseManager.getAllActiveHighlights(context) }

    suspend fun createHighlight(versionId: String, verses: List<VerseLocationDto>, color: Int): Highlight =
        withContext(Dispatchers.IO) { BibleDatabaseManager.insertHighlight(context, versionId, verses, color) }

    suspend fun recolorHighlight(localId: Long, color: Int) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.recolorHighlight(context, localId, color) }

    suspend fun deleteHighlight(localId: Long) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.softDeleteHighlight(context, localId) }

    suspend fun getNotesForChapter(bookId: Int, chapter: Int): List<Note> =
        withContext(Dispatchers.IO) { BibleDatabaseManager.getNotesForChapter(context, bookId, chapter) }

    suspend fun getAllActiveNotes(): List<Note> =
        withContext(Dispatchers.IO) { BibleDatabaseManager.getAllActiveNotes(context) }

    suspend fun getNoteById(localId: Long): Note? =
        withContext(Dispatchers.IO) { BibleDatabaseManager.getNoteById(context, localId) }

    suspend fun createNote(versionId: String, verses: List<VerseLocationDto>, text: String): Note =
        withContext(Dispatchers.IO) { BibleDatabaseManager.insertNote(context, versionId, verses, text) }

    suspend fun updateNote(localId: Long, verses: List<VerseLocationDto>, text: String) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.updateNote(context, localId, verses, text) }

    suspend fun deleteNote(localId: Long) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.softDeleteNote(context, localId) }

    // ---- Sync worker support (Phase E) — see worker/SyncWorker.kt ----

    suspend fun getPendingHighlights(): List<Highlight> =
        withContext(Dispatchers.IO) { BibleDatabaseManager.getPendingHighlights(context) }

    suspend fun markHighlightPushed(localId: Long, remoteId: String, createdAt: String, updatedAt: String) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.markHighlightPushed(context, localId, remoteId, createdAt, updatedAt) }

    suspend fun purgeHighlight(localId: Long) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.purgeHighlight(context, localId) }

    suspend fun deleteHighlightByRemoteId(remoteId: String) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.deleteHighlightByRemoteId(context, remoteId) }

    suspend fun upsertHighlightFromServer(
        remoteId: String,
        versionId: String,
        verses: List<VerseLocationDto>,
        color: Int,
        createdAt: String,
        updatedAt: String
    ) = withContext(Dispatchers.IO) {
        BibleDatabaseManager.upsertHighlightFromServer(context, remoteId, versionId, verses, color, createdAt, updatedAt)
    }

    suspend fun getPendingNotes(): List<Note> =
        withContext(Dispatchers.IO) { BibleDatabaseManager.getPendingNotes(context) }

    suspend fun markNotePushed(localId: Long, remoteId: String, createdAt: String, updatedAt: String) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.markNotePushed(context, localId, remoteId, createdAt, updatedAt) }

    suspend fun purgeNote(localId: Long) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.purgeNote(context, localId) }

    suspend fun deleteNoteByRemoteId(remoteId: String) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.deleteNoteByRemoteId(context, remoteId) }

    suspend fun upsertNoteFromServer(
        remoteId: String,
        versionId: String,
        verses: List<VerseLocationDto>,
        text: String,
        createdAt: String,
        updatedAt: String
    ) = withContext(Dispatchers.IO) {
        BibleDatabaseManager.upsertNoteFromServer(context, remoteId, versionId, verses, text, createdAt, updatedAt)
    }

    /** Last time this device successfully pulled highlights/notes — passed as the server's
     *  `updatedSince` query param so a sync pass only asks for what changed since then (and gets
     *  tombstones for anything deleted elsewhere in the meantime). Null before the first sync. */
    fun loadHighlightsSyncWatermark(): String? = prefs.getString(KEY_HIGHLIGHTS_SYNC_WATERMARK, null)
    fun saveHighlightsSyncWatermark(iso: String) {
        prefs.edit().putString(KEY_HIGHLIGHTS_SYNC_WATERMARK, iso).apply()
    }

    fun loadNotesSyncWatermark(): String? = prefs.getString(KEY_NOTES_SYNC_WATERMARK, null)
    fun saveNotesSyncWatermark(iso: String) {
        prefs.edit().putString(KEY_NOTES_SYNC_WATERMARK, iso).apply()
    }

    /**
     * Reads the reference [DailyVerseFetchWorker][com.application.bibleapp.worker.DailyVerseFetchWorker]
     * cached in the local DB — a plain read, no network involved. Falls back to a
     * direct (uncached) [refreshDailyVerse] only if nothing has been cached yet, e.g.
     * right after install before the background worker's first run.
     */
    suspend fun getDailyVerse(): DailyVerseRef = withContext(Dispatchers.IO) {
        BibleDatabaseManager.getCachedDailyVerse(context) ?: refreshDailyVerse()
    }

    /**
     * Always fetches fresh from [daily] and overwrites the cached row — this is what
     * the daily background worker calls to replace the previous day's verse. Skips the
     * network call entirely when there's no connectivity rather than waiting on a
     * request that's bound to time out.
     */
    suspend fun refreshDailyVerse(): DailyVerseRef = withContext(Dispatchers.IO) {
        if (!NetworkUtils.isOnline(context)) {
            throw IOException("No internet connection — skipping daily verse fetch")
        }
        val fetched = daily.getDailyVerse()
        BibleDatabaseManager.saveCachedDailyVerse(context, dailyVerseDateKey(), fetched)
        fetched
    }

    fun isNotificationEnabled(): Boolean = prefs.getBoolean(KEY_NOTIFICATION_ENABLED, false)

    fun loadNotificationTime(): NotificationTime = NotificationTime(
        hour = prefs.getInt(KEY_NOTIFICATION_HOUR, DEFAULT_NOTIFICATION_HOUR),
        minute = prefs.getInt(KEY_NOTIFICATION_MINUTE, DEFAULT_NOTIFICATION_MINUTE)
    )

    /** Persists the toggle and (de)schedules the reminder worker in the same place — the
     *  ViewModel/UI never has to remember to do both. */
    fun setNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_ENABLED, enabled).apply()
        if (enabled) {
            val time = loadNotificationTime()
            DailyVerseScheduler.scheduleNotification(context, time.hour, time.minute)
        } else {
            DailyVerseScheduler.cancelNotification(context)
        }
    }

    /** No-ops on the schedule if the reminder is currently off — it'll pick up the new time
     *  whenever it's next turned on. */
    fun setNotificationTime(hour: Int, minute: Int) {
        prefs.edit()
            .putInt(KEY_NOTIFICATION_HOUR, hour)
            .putInt(KEY_NOTIFICATION_MINUTE, minute)
            .apply()
        if (isNotificationEnabled()) {
            DailyVerseScheduler.scheduleNotification(context, hour, minute)
        }
    }

    /**
     * The translation catalog, restricted to standard 66-book Bibles. The helloao API's
     * catalog also lists NT-only, OT-only, and single-book portions (roughly 1,000 of the
     * ~1,250 entries) — this app's reading/navigation model assumes the full 1-66 canon,
     * so anything short of it is filtered out here rather than surfaced as a pickable,
     * partially-broken version.
     *
     * The catalog response is ~850KB and barely changes day to day, so a fresh copy is
     * only fetched once per [TRANSLATIONS_CACHE_TTL_MS] — every other call (e.g. a cold
     * app start shortly after the last one) reads the filtered list straight back off
     * disk instead of re-downloading the whole thing. A corrupt/unreadable cache file is
     * treated the same as a missing one (re-fetched, then overwritten) rather than
     * failing the whole call.
     */
    suspend fun getAllVersions(): List<BibleTranslation> = withContext(Dispatchers.IO) {
        readCachedVersions()?.let { return@withContext it }

        val fresh = remote.getAvailableTranslations().filter { it.isCompleteCanon }
        writeCachedVersions(fresh)
        fresh
    }

    private fun readCachedVersions(): List<BibleTranslation>? {
        val file = translationsCacheFile
        if (!file.exists()) return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (age !in 0..TRANSLATIONS_CACHE_TTL_MS) return null // also covers a clock rollback (negative age)

        return try {
            HttpClientProvider.json.decodeFromString(CachedTranslations.serializer(), file.readText()).translations
        } catch (e: SerializationException) {
            Log.w("BibleRepository", "Translations cache unreadable, will re-fetch: ${e.message}")
            null
        } catch (e: IOException) {
            Log.w("BibleRepository", "Translations cache unreadable, will re-fetch: ${e.message}")
            null
        }
    }

    private fun writeCachedVersions(versions: List<BibleTranslation>) {
        try {
            val json = HttpClientProvider.json.encodeToString(CachedTranslations.serializer(), CachedTranslations(versions))
            translationsCacheFile.writeText(json)
        } catch (e: IOException) {
            Log.w("BibleRepository", "Failed to write translations cache: ${e.message}")
        }
    }

    private val translationsCacheFile: File
        get() = File(context.applicationContext.filesDir, "translations_cache.json")

    // BibleDatabaseManager.isVersionDownloaded runs a blocking SQLite query; keep it off
    // whatever dispatcher the caller happens to be on (viewModelScope defaults to Main).
    suspend fun isVersionDownloaded(versionId: String): Boolean = withContext(Dispatchers.IO) {
        BibleDatabaseManager.isVersionDownloaded(context, versionId)
    }

    /** All locally downloaded versions, keyed for the picker to badge without a query per row. */
    suspend fun getDownloadedVersions(): List<DownloadedVersionInfo> = withContext(Dispatchers.IO) {
        BibleDatabaseManager.getDownloadedVersions(context)
    }

    /**
     * Book names in [versionId]'s own language, keyed by book_id. Empty for "kjv" and for
     * any version downloaded before book names were captured — callers should fall back to
     * [com.application.bibleapp.data.model.BibleBooks]' English names in that case.
     */
    suspend fun getBookNames(versionId: String): Map<Int, String> = withContext(Dispatchers.IO) {
        BibleDatabaseManager.getBookNames(context, versionId)
    }

    /**
     * Chapter/verse structure for [versionId] — bookId -> (chapter -> verseCount).
     * Empty for "kjv" and for any version downloaded before this was captured, in
     * which case callers should fall back to
     * [com.application.bibleapp.data.model.BibleBooks]' hardcoded (KJV-based) structure.
     */
    suspend fun getChapterStructure(versionId: String): Map<Int, Map<Int, Int>> = withContext(Dispatchers.IO) {
        BibleDatabaseManager.getChapterStructure(context, versionId)
    }

    /** Downloads [translationId] (a single bulk request under the hood) and persists it to the local DB. */
    suspend fun downloadVersion(
        translationId: String,
        onProgress: (Float) -> Unit = {}
    ): Result<VersionDownloadSummary> = BibleDatabaseManager.downloadAndSaveVersion(
        context = context,
        translationId = translationId,
        remote = remote,
        onProgress = onProgress
    )

    /** Deletes then re-downloads [translationId] — the only way to pick up a newer schema_version. */
    suspend fun redownloadVersion(
        translationId: String,
        onProgress: (Float) -> Unit = {}
    ): Result<VersionDownloadSummary> {
        withContext(Dispatchers.IO) { BibleDatabaseManager.deleteDownloadedVersion(context, translationId) }
        return downloadVersion(translationId, onProgress)
    }

    /** Permanently removes [versionId]'s downloaded content. No-ops for the bundled "kjv". */
    suspend fun deleteVersion(versionId: String) = withContext(Dispatchers.IO) {
        BibleDatabaseManager.deleteDownloadedVersion(context, versionId)
    }

    /** Persisted across process restarts so the app reopens on the last version the user picked. */
    fun saveSelectedVersion(versionId: String) {
        prefs.edit().putString(KEY_SELECTED_VERSION, versionId).apply()
    }

    fun loadSelectedVersion(): String = prefs.getString(KEY_SELECTED_VERSION, DEFAULT_VERSION.id) ?: DEFAULT_VERSION.id

    fun saveThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun loadThemeMode(): ThemeMode {
        val stored = prefs.getString(KEY_THEME_MODE, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(stored) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun saveVerseTextScale(scale: VerseTextScale) {
        prefs.edit().putFloat(KEY_VERSE_TEXT_SCALE, scale.multiplier).apply()
    }

    fun loadVerseTextScale(): VerseTextScale =
        VerseTextScale.fromMultiplier(prefs.getFloat(KEY_VERSE_TEXT_SCALE, VerseTextScale.DEFAULT.multiplier))

    /** Persisted across process restarts so the app reopens on the last book/chapter/verse read. */
    fun saveReadingPosition(bookId: Int, chapter: Int, verse: Int) {
        prefs.edit()
            .putInt(KEY_READING_BOOK, bookId)
            .putInt(KEY_READING_CHAPTER, chapter)
            .putInt(KEY_READING_VERSE, verse)
            .apply()
    }

    fun loadReadingPosition(): ReadingPosition = ReadingPosition(
        bookId = prefs.getInt(KEY_READING_BOOK, 1),
        chapter = prefs.getInt(KEY_READING_CHAPTER, 1),
        verse = prefs.getInt(KEY_READING_VERSE, 1)
    )

    // ---- Sync preferences (Phase G) — read by SyncScheduler/SyncWorker, written by Settings ----

    /** Defaults to on — matches Phase E's existing behavior before this preference existed. */
    fun isAutoSyncEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, true)
    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC_ENABLED, enabled).apply()
    }

    /** Defaults to off — matches Phase E's existing behavior (any connected network) before this
     *  preference existed. */
    fun isWifiOnlySyncEnabled(): Boolean = prefs.getBoolean(KEY_WIFI_ONLY_SYNC, false)
    fun setWifiOnlySyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY_SYNC, enabled).apply()
    }

    /** Stamped by SyncWorker at the end of a fully successful pass — drives Settings' "Synced
     *  Xm ago" status line. Null before the first successful sync this install has ever done. */
    fun loadLastSyncCompletedAt(): String? = prefs.getString(KEY_LAST_SYNC_COMPLETED_AT, null)
    fun saveLastSyncCompletedAt(iso: String) {
        prefs.edit().putString(KEY_LAST_SYNC_COMPLETED_AT, iso).apply()
    }

    private companion object {
        const val KEY_SELECTED_VERSION = "selected_version_id"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_VERSE_TEXT_SCALE = "verse_text_scale"
        const val KEY_READING_BOOK = "reading_book_id"
        const val KEY_READING_CHAPTER = "reading_chapter"
        const val KEY_READING_VERSE = "reading_verse"
        const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        const val KEY_NOTIFICATION_HOUR = "notification_hour"
        const val KEY_NOTIFICATION_MINUTE = "notification_minute"
        const val KEY_HIGHLIGHTS_SYNC_WATERMARK = "highlights_sync_watermark"
        const val KEY_NOTES_SYNC_WATERMARK = "notes_sync_watermark"
        const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        const val KEY_WIFI_ONLY_SYNC = "wifi_only_sync"
        const val KEY_LAST_SYNC_COMPLETED_AT = "last_sync_completed_at"
        const val DEFAULT_NOTIFICATION_HOUR = 8
        const val DEFAULT_NOTIFICATION_MINUTE = 0

        // The catalog rarely changes; this just bounds how stale the picker's list can
        // get without forcing a fresh 850KB fetch on every cold start.
        const val TRANSLATIONS_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}