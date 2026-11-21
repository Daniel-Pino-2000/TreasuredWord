package com.application.bibleapp.data.repository

import android.content.Context
import android.util.Log
import com.application.bibleapp.data.local.BibleDatabaseManager
import com.application.bibleapp.data.local.VersionDownloadSummary
import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.CachedDailyVerse
import com.application.bibleapp.data.model.DEFAULT_VERSION
import com.application.bibleapp.data.model.DailyVerseRef
import com.application.bibleapp.data.model.DownloadedVersionInfo
import com.application.bibleapp.data.model.Footnote
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.MemoryPassage
import com.application.bibleapp.data.model.MemoryVerse
import com.application.bibleapp.data.remote.MemoryProgressDto
import com.application.bibleapp.data.remote.MemoryVerseResponseDto
import com.application.bibleapp.data.model.Note
import com.application.bibleapp.data.model.VerseOfTheDay
import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.data.model.toUI
import com.application.bibleapp.data.remote.BibleRemoteDataSource
import com.application.bibleapp.data.remote.DailyVerseDataSource
import com.application.bibleapp.data.remote.HighlightResponseDto
import com.application.bibleapp.data.remote.HttpClientProvider
import com.application.bibleapp.data.remote.NoteResponseDto
import com.application.bibleapp.data.remote.ReadingProgressResponseDto
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.data.model.YouVersionBibles
import com.application.bibleapp.data.local.NoContentException
import com.application.bibleapp.data.local.NoInternetException
import com.application.bibleapp.data.remote.YouVersionBibleSource
import com.application.bibleapp.data.remote.YouVersionBusyException
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.ThemePalette
import com.application.bibleapp.ui.theme.VerseImageStyle
import com.application.bibleapp.verseimage.DailyWallpaper
import com.application.bibleapp.verseimage.DailyWallpaperSettings
import com.application.bibleapp.verseimage.VerseImageFormat
import com.application.bibleapp.verseimage.VerseImageOptions
import com.application.bibleapp.ui.theme.VerseTextScale
import com.application.bibleapp.utils.NetworkUtils
import com.application.bibleapp.utils.isoTimestampNow
import com.application.bibleapp.utils.parseVerseReference
import com.application.bibleapp.utils.passage
import com.application.bibleapp.worker.DailyVerseScheduler
import com.application.bibleapp.worker.LocalReadingProgress
import com.application.bibleapp.worker.SyncLocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.util.Calendar
import com.application.bibleapp.widget.DailyVerseWidgetUpdates
import com.application.bibleapp.widget.MemorizeWidgetUpdates
import com.application.bibleapp.widget.MemorizePick
import com.application.bibleapp.widget.WidgetColors

/**
 * Year + day-of-year, in local time — stamped on the cached daily-verse row and compared with
 * today's when it's read, so a row from a previous day is never shown as today's verse (#183).
 * The background job can't be relied on to replace it: battery restrictions can delay or skip it.
 */
internal fun dailyVerseDateKey(now: Calendar = Calendar.getInstance()): String =
    "${now.get(Calendar.YEAR)}-${now.get(Calendar.DAY_OF_YEAR)}"

/** Yesterday's [dailyVerseDateKey], to compare a newly fetched verse with yesterday's (#298). */
internal fun previousDailyVerseDateKey(now: Calendar = Calendar.getInstance()): String =
    dailyVerseDateKey((now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })

/** [cached]'s reference if it was fetched for [todayKey], else null. */
internal fun CachedDailyVerse?.refFor(todayKey: String): DailyVerseRef? =
    this?.takeIf { it.dateKey == todayKey }?.ref

/**
 * The verse to keep as today's once OurManna returned [fetched] (#298). OurManna changes its verse
 * on its own clock, not the phone's, so a fetch made before its switchover returns the verse it
 * was already serving yesterday. When yesterday's fetch came after the switchover (the job was
 * delayed, or the app was opened late in the day) and today's comes before it, that is the same
 * verse two days running. Then today gets the [curated] verse (never yesterday's), for the whole
 * day: waiting for OurManna's switchover instead would change the verse in the middle of the day,
 * and every following day too for phones ahead of OurManna's clock.
 */
internal fun verseToKeepForToday(
    fetched: DailyVerseRef,
    yesterdays: DailyVerseRef?,
    curated: (avoid: DailyVerseRef?) -> DailyVerseRef
): DailyVerseRef = if (fetched == yesterdays) curated(yesterdays) else fetched

/**
 * Today's daily verse: [cached] if it's from [todayKey], else a fresh [refresh]; if that fails
 * (offline, OurManna down), the [curated] verse — which changes daily too — rather than an old
 * cached one (#183). Pure apart from the two lambdas, so the date check is unit tested.
 */
internal suspend fun pickDailyVerse(
    cached: CachedDailyVerse?,
    todayKey: String,
    refresh: suspend () -> DailyVerseRef,
    curated: () -> DailyVerseRef
): DailyVerseRef {
    cached.refFor(todayKey)?.let { return it }
    return try {
        refresh()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        curated()
    }
}

/** The last book/chapter/verse the user had open, restored on app launch. */
/** Why a YouVersion chapter (#366) isn't on screen: no connection, too many requests, or anything else. */
enum class ChapterProblem { OFFLINE, BUSY, UNAVAILABLE }

data class ChapterLoad(val verses: List<VerseUI>, val problem: ChapterProblem? = null)

data class ReadingPosition(val bookId: Int, val chapter: Int, val verse: Int)

/** The user's chosen local wall-clock time for the daily verse reminder. */
data class NotificationTime(val hour: Int, val minute: Int)

/**
 * On-disk cache of [BibleRepository.getAllVersions]'s result — a wrapper so the JSON file has a
 * stable top-level shape. [format] marks what the entries carry: files written before it existed
 * read as 1 and lack licenses (#98), so they're re-fetched instead of trusted for a day.
 */
@Serializable
private data class CachedTranslations(val translations: List<BibleTranslation>, val format: Int = 1)

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
    private val daily: DailyVerseDataSource,
    private val youVersion: YouVersionBibleSource = YouVersionBibleSource()
) : SyncLocalStore {
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** [versionId]'s [bookId] [chapter], fetched first if it's a YouVersion chapter not read yet
     *  (#366); empty when that isn't possible right now, as for a version that isn't on the device. */
    suspend fun getChapter(bookId: Int, chapter: Int, versionId: String): List<VerseUI> =
        loadChapter(bookId, chapter, versionId).verses

    /** The bundled KJV's [bookId] [chapter], read at once: a small query on the bundled table, for
     *  the one place that can't wait (a licensed Bible's selection asked about in Bible Helper, #366). */
    fun kjvChapterNow(bookId: Int, chapter: Int): List<VerseUI> =
        BibleDatabaseManager.getVersesByChapter(context, bookId, chapter, DEFAULT_VERSION.id).map { it.toUI() }

    /** Like [getChapter], plus why a YouVersion chapter couldn't be fetched, for the reader to say. */
    suspend fun loadChapter(bookId: Int, chapter: Int, versionId: String): ChapterLoad =
        withContext(Dispatchers.IO) {
            val problem = if (YouVersionBibles.isYouVersion(versionId)) fetchYouVersionChapterIfMissing(bookId, chapter, versionId) else null
            ChapterLoad(
                verses = BibleDatabaseManager.getVersesByChapter(context, bookId, chapter, versionId).map { it.toUI() },
                problem = problem
            )
        }

    /** Fetches and saves one chapter of a YouVersion version unless it's already saved (#366). */
    private suspend fun fetchYouVersionChapterIfMissing(bookId: Int, chapter: Int, versionId: String): ChapterProblem? {
        if (!BibleDatabaseManager.isVersionDownloaded(context, versionId)) return null // not added on this device
        if (BibleDatabaseManager.hasChapter(context, versionId, bookId, chapter)) return null
        val bible = YouVersionBibles.find(versionId) ?: return ChapterProblem.UNAVAILABLE
        if (!NetworkUtils.isOnline(context)) return ChapterProblem.OFFLINE
        return try {
            val parsed = youVersion.fetchChapter(bible.yvId, bookId, chapter)
            BibleDatabaseManager.saveChapter(context, versionId, bookId, chapter, parsed.verses, parsed.footnotes)
            if (parsed.verses.isEmpty()) ChapterProblem.UNAVAILABLE else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: YouVersionBusyException) {
            ChapterProblem.BUSY
        } catch (e: Exception) {
            Log.w("BibleRepository", "YouVersion chapter $versionId $bookId:$chapter failed: ${e.message}")
            if (NetworkUtils.isOnline(context)) ChapterProblem.UNAVAILABLE else ChapterProblem.OFFLINE
        }
    }

    suspend fun getFootnotes(bookId: Int, chapter: Int, versionId: String): List<Footnote> =
        withContext(Dispatchers.IO) {
            BibleDatabaseManager.getFootnotesForChapter(context, bookId, chapter, versionId)
        }

    /** Fills in anything [versionId] still needs for word search (#90); see BibleDatabaseManager.prepareSearch. */
    suspend fun prepareSearch(versionId: String) = BibleDatabaseManager.prepareSearch(context, versionId)

    /** Whether a word search for [query] finds anything in [versionId] (#88). */
    suspend fun hasSearchMatch(query: String, versionId: String): Boolean =
        BibleDatabaseManager.hasSearchMatch(context, query, versionId)

    /** A reference ("John 3:16", "Jn 3", the version's own book name) finds that passage; anything
     *  else, or a reference to verses this version doesn't have, is a word search (#59). */
    suspend fun searchVerses(query: String, versionId: String): List<VerseUI> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val reference = parseVerseReference(query, BibleDatabaseManager.getBookNames(context, versionId))
            val passage = reference?.let { ref ->
                BibleDatabaseManager.getVersesByChapter(context, ref.bookId, ref.chapter, versionId)
                    .map { it.toUI() }
                    .passage(ref)
            }
            passage?.takeIf { it.isNotEmpty() } ?: BibleDatabaseManager.searchVerses(query, versionId)
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

    suspend fun restoreHighlight(highlight: Highlight) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.restoreHighlight(context, highlight) }

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

    suspend fun restoreNote(note: Note) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.restoreNote(context, note) }

    // ---- Sync support (Phase E) — see worker/SyncEngine.kt ----

    override suspend fun getPendingHighlights(): List<Highlight> =
        withContext(Dispatchers.IO) { BibleDatabaseManager.getPendingHighlights(context) }

    override suspend fun markHighlightPushed(pushed: Highlight, remoteId: String, createdAt: String, updatedAt: String): Boolean =
        withContext(Dispatchers.IO) {
            BibleDatabaseManager.markHighlightPushed(context, pushed.localId, pushed.updatedAt, remoteId, createdAt, updatedAt)
        }

    override suspend fun purgeHighlight(pushed: Highlight) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.purgeHighlight(context, pushed.localId, pushed.updatedAt) }

    override suspend fun detachHighlight(pushed: Highlight) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.detachHighlight(context, pushed.localId) }

    override suspend fun deleteHighlightByRemoteId(remoteId: String): Boolean =
        withContext(Dispatchers.IO) { BibleDatabaseManager.deleteHighlightByRemoteId(context, remoteId) }

    override suspend fun upsertHighlightFromServer(dto: HighlightResponseDto): Boolean = withContext(Dispatchers.IO) {
        BibleDatabaseManager.upsertHighlightFromServer(context, dto.id, dto.versionId, dto.verses, dto.color, dto.createdAt, dto.updatedAt)
    }

    override suspend fun getPendingNotes(): List<Note> =
        withContext(Dispatchers.IO) { BibleDatabaseManager.getPendingNotes(context) }

    override suspend fun markNotePushed(pushed: Note, remoteId: String, createdAt: String, updatedAt: String): Boolean =
        withContext(Dispatchers.IO) {
            BibleDatabaseManager.markNotePushed(context, pushed.localId, pushed.updatedAt, remoteId, createdAt, updatedAt)
        }

    override suspend fun purgeNote(pushed: Note) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.purgeNote(context, pushed.localId, pushed.updatedAt) }

    override suspend fun detachNote(pushed: Note) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.detachNote(context, pushed.localId) }

    override suspend fun deleteNoteByRemoteId(remoteId: String): Boolean =
        withContext(Dispatchers.IO) { BibleDatabaseManager.deleteNoteByRemoteId(context, remoteId) }

    override suspend fun upsertNoteFromServer(dto: NoteResponseDto): Boolean = withContext(Dispatchers.IO) {
        BibleDatabaseManager.upsertNoteFromServer(context, dto.id, dto.versionId, dto.verses, dto.text, dto.createdAt, dto.updatedAt)
    }

    // ---- The Memorize list (#362) ----

    suspend fun getAllActiveMemoryVerses(): List<MemoryVerse> =
        withContext(Dispatchers.IO) { BibleDatabaseManager.getAllActiveMemoryVerses(context) }

    suspend fun findActiveMemoryVerse(passage: MemoryPassage): MemoryVerse? =
        withContext(Dispatchers.IO) { BibleDatabaseManager.findActiveMemoryVerse(context, passage) }

    // Each change to the list redraws the memorize widget (#364), so a removed passage is
    // replaced at once and the first one added fills an empty widget.
    suspend fun addMemoryVerse(versionId: String, passage: MemoryPassage): MemoryVerse =
        withContext(Dispatchers.IO) { BibleDatabaseManager.insertMemoryVerse(context, versionId, passage) }
            .also { MemorizeWidgetUpdates.refreshNow(context) }

    suspend fun updateMemoryProgress(localId: Long, progress: MemoryProgressDto) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.updateMemoryProgress(context, localId, progress) }

    suspend fun deleteMemoryVerse(localId: Long) {
        withContext(Dispatchers.IO) { BibleDatabaseManager.softDeleteMemoryVerse(context, localId) }
        MemorizeWidgetUpdates.refreshNow(context)
    }

    suspend fun restoreMemoryVerse(verse: MemoryVerse) {
        withContext(Dispatchers.IO) { BibleDatabaseManager.restoreMemoryVerse(context, verse) }
        MemorizeWidgetUpdates.refreshNow(context)
    }

    override suspend fun getPendingMemoryVerses(): List<MemoryVerse> =
        withContext(Dispatchers.IO) { BibleDatabaseManager.getPendingMemoryVerses(context) }

    override suspend fun markMemoryVersePushed(pushed: MemoryVerse, dto: MemoryVerseResponseDto): Boolean =
        withContext(Dispatchers.IO) { BibleDatabaseManager.markMemoryVersePushed(context, pushed.localId, pushed.updatedAt, dto) }

    override suspend fun purgeMemoryVerse(pushed: MemoryVerse) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.purgeMemoryVerse(context, pushed.localId, pushed.updatedAt) }

    override suspend fun detachMemoryVerse(pushed: MemoryVerse) =
        withContext(Dispatchers.IO) { BibleDatabaseManager.detachMemoryVerse(context, pushed.localId) }

    override suspend fun deleteMemoryVerseByRemoteId(remoteId: String): Boolean =
        withContext(Dispatchers.IO) { BibleDatabaseManager.deleteMemoryVerseByRemoteId(context, remoteId) }
            .also { if (it) MemorizeWidgetUpdates.refreshNow(context) }

    override suspend fun upsertMemoryVerseFromServer(dto: MemoryVerseResponseDto): Boolean =
        withContext(Dispatchers.IO) { BibleDatabaseManager.upsertMemoryVerseFromServer(context, dto) }
            .also { if (it) MemorizeWidgetUpdates.refreshNow(context) }

    /** Highlights, notes and Memorize passages that haven't reached the server yet (#81). */
    suspend fun countPendingChanges(): Int =
        withContext(Dispatchers.IO) { BibleDatabaseManager.countPendingChanges(context) }

    /** Where the next pull starts — passed as the server's `updatedSince` query param so a sync
     *  pass only asks for what changed since then (and gets tombstones for anything deleted
     *  elsewhere in the meantime). From the server's clock (#84). Null before the first sync. */
    override fun loadHighlightsSyncWatermark(): String? = prefs.getString(KEY_HIGHLIGHTS_SYNC_WATERMARK, null)
    override fun saveHighlightsSyncWatermark(iso: String) {
        prefs.edit().putString(KEY_HIGHLIGHTS_SYNC_WATERMARK, iso).apply()
    }

    override fun loadNotesSyncWatermark(): String? = prefs.getString(KEY_NOTES_SYNC_WATERMARK, null)
    override fun saveNotesSyncWatermark(iso: String) {
        prefs.edit().putString(KEY_NOTES_SYNC_WATERMARK, iso).apply()
    }

    override fun loadMemoryVersesSyncWatermark(): String? = prefs.getString(KEY_MEMORY_VERSES_SYNC_WATERMARK, null)
    override fun saveMemoryVersesSyncWatermark(iso: String) {
        prefs.edit().putString(KEY_MEMORY_VERSES_SYNC_WATERMARK, iso).apply()
    }

    /**
     * Today's daily verse. Usually a plain read of the reference
     * [DailyVerseFetchWorker][com.application.bibleapp.worker.DailyVerseFetchWorker] cached; when
     * that's missing or from a previous day (the job didn't run — battery restrictions, #183), it
     * fetches today's with [refreshDailyVerse], and offline falls back to [VerseOfTheDay.forToday].
     * Never throws, except on cancellation.
     */
    suspend fun getDailyVerse(): DailyVerseRef = withContext(Dispatchers.IO) {
        pickDailyVerse(
            cached = BibleDatabaseManager.getCachedDailyVerse(context),
            todayKey = dailyVerseDateKey(),
            refresh = { refreshDailyVerse() },
            curated = { curatedDailyVerse() }
        )
    }

    /** Today's curated verse, never the one cached for yesterday (#298) — the offline fallback. */
    fun curatedDailyVerse(): DailyVerseRef = VerseOfTheDay.forToday(
        avoid = BibleDatabaseManager.getCachedDailyVerse(context).refFor(previousDailyVerseDateKey())
    )

    /** The cached daily-verse reference if it's today's, else null — a plain read, no network. */
    suspend fun getTodaysCachedDailyVerse(): DailyVerseRef? = withContext(Dispatchers.IO) {
        BibleDatabaseManager.getCachedDailyVerse(context).refFor(dailyVerseDateKey())
    }

    /**
     * Fetches today's verse from [daily] and caches it — what the daily background worker calls to
     * replace the previous day's verse. Once today has a cached verse it's kept, with no network
     * call (#298): a second fetch later in the day could land after OurManna's switchover and
     * change the verse mid-day. A fetched verse that is yesterday's again is replaced by the
     * curated one ([verseToKeepForToday]). Skips the network call entirely when there's no
     * connectivity rather than waiting on a request that's bound to time out.
     */
    suspend fun refreshDailyVerse(): DailyVerseRef = withContext(Dispatchers.IO) {
        val todayKey = dailyVerseDateKey()
        val cached = BibleDatabaseManager.getCachedDailyVerse(context)
        cached.refFor(todayKey)?.let { return@withContext it }
        if (!NetworkUtils.isOnline(context)) {
            throw IOException("No internet connection — skipping daily verse fetch")
        }
        val kept = verseToKeepForToday(
            fetched = daily.getDailyVerse(),
            yesterdays = cached.refFor(previousDailyVerseDateKey())
        ) { avoid -> VerseOfTheDay.forToday(avoid) }
        BibleDatabaseManager.saveCachedDailyVerse(context, todayKey, kept)
        DailyVerseWidgetUpdates.refreshNow(context) // the widget shows the cached verse (#245)
        kept
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
        val versions = readCachedVersions() ?: remote.getAvailableTranslations()
            .filter { it.isCompleteCanon }
            .also { writeCachedVersions(it) }
        saveMissingCredits(versions)
        versions + youVersionTranslations()
    }

    /** The licensed YouVersion Bibles (#366), offered only when the build has the App Key. */
    private fun youVersionTranslations(): List<BibleTranslation> =
        if (youVersion.isConfigured) YouVersionBibles.all.map { it.toTranslation() } else emptyList()

    /** Copies catalog titles and licenses onto downloads that lack them, so their credit works offline (#98). */
    private fun saveMissingCredits(versions: List<BibleTranslation>) {
        val incomplete = BibleDatabaseManager.getDownloadedVersions(context)
            .filter { it.fullName.isBlank() || it.license == null }
            .mapTo(HashSet()) { it.id }
        if (incomplete.isEmpty()) return
        BibleDatabaseManager.saveMissingCredits(context, versions.filter { it.id in incomplete })
    }

    private fun readCachedVersions(): List<BibleTranslation>? {
        val file = translationsCacheFile
        if (!file.exists()) return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (age !in 0..TRANSLATIONS_CACHE_TTL_MS) return null // also covers a clock rollback (negative age)

        return try {
            HttpClientProvider.json.decodeFromString(CachedTranslations.serializer(), file.readText())
                .takeIf { it.format >= TRANSLATIONS_CACHE_FORMAT }
                ?.translations
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
            val json = HttpClientProvider.json.encodeToString(CachedTranslations.serializer(), CachedTranslations(versions, TRANSLATIONS_CACHE_FORMAT))
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

    /** Deletes saved YouVersion versions the app no longer offers (#381): see [YouVersionBibles.isWithdrawn]. */
    suspend fun removeWithdrawnVersions() = withContext(Dispatchers.IO) {
        BibleDatabaseManager.getDownloadedVersions(context)
            .filter { YouVersionBibles.isWithdrawn(it.id) }
            .forEach { BibleDatabaseManager.deleteDownloadedVersion(context, it.id) }
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
    ): Result<VersionDownloadSummary> =
        if (YouVersionBibles.isYouVersion(translationId)) {
            addYouVersion(translationId, onProgress)
        } else {
            BibleDatabaseManager.downloadAndSaveVersion(
                context = context,
                translationId = translationId,
                remote = remote,
                onProgress = onProgress
            )
        }

    /**
     * "Downloading" a YouVersion version (#366) only saves its book names, verse counts and credit:
     * its chapters are fetched as they're read (see [loadChapter]), never all at once.
     */
    private suspend fun addYouVersion(versionId: String, onProgress: (Float) -> Unit): Result<VersionDownloadSummary> =
        withContext(Dispatchers.IO) {
            val bible = YouVersionBibles.find(versionId)
                ?: return@withContext Result.failure(NoContentException())
            if (!NetworkUtils.isOnline(context)) return@withContext Result.failure(NoInternetException())
            try {
                onProgress(0.1f)
                val structure = youVersion.fetchStructure(bible.yvId)
                if (structure.chapterInfo.isEmpty()) return@withContext Result.failure(NoContentException())
                onProgress(0.8f)
                val translation = bible.toTranslation()
                val notice = bible.notice ?: youVersion.fetchCopyright(bible.yvId)
                BibleDatabaseManager.registerOnlineVersion(
                    context = context,
                    versionId = versionId,
                    name = bible.abbreviation,
                    fullName = bible.title,
                    license = translation.license?.copy(notice = notice),
                    bookNames = structure.bookNames,
                    chapterInfo = structure.chapterInfo
                )
                onProgress(1f)
                Result.success(VersionDownloadSummary(0, 0, 0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BibleRepository", "Adding $versionId failed: ${e.message}")
                Result.failure(e)
            }
        }

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
        DailyVerseWidgetUpdates.refreshNow(context) // the widget's verse follows the translation (#245)
    }

    fun loadSelectedVersion(): String = prefs.getString(KEY_SELECTED_VERSION, DEFAULT_VERSION.id) ?: DEFAULT_VERSION.id

    fun saveThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        DailyVerseWidgetUpdates.refreshNow(context) // the widget follows the app's light/dark (#333)
    }

    fun loadThemeMode(): ThemeMode {
        val stored = prefs.getString(KEY_THEME_MODE, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(stored) }.getOrDefault(ThemeMode.SYSTEM)
    }

    /** The color theme (#244). Kept on this device only, like [ThemeMode]; not synced. */
    fun saveThemePalette(palette: ThemePalette) {
        prefs.edit().putString(KEY_THEME_PALETTE, palette.name).apply()
        DailyVerseWidgetUpdates.refreshNow(context) // and its color theme (#333)
    }

    fun loadThemePalette(): ThemePalette = ThemePalette.fromStored(prefs.getString(KEY_THEME_PALETTE, null))

    /** Whether the daily verse widget uses the app's theme or the wallpaper's colors (#333). */
    fun saveWidgetColors(colors: WidgetColors) {
        prefs.edit().putString(KEY_WIDGET_COLORS, colors.name).apply()
        DailyVerseWidgetUpdates.refreshNow(context)
    }

    fun loadWidgetColors(): WidgetColors = WidgetColors.fromStored(prefs.getString(KEY_WIDGET_COLORS, null))

    /** Home's Verse to memorize tile (#365) keeps its own daily pick, by the widget's rules (#364). */
    internal fun loadHomeMemorizePick(): MemorizePick? {
        val day = prefs.getString(KEY_HOME_MEMORIZE_DAY, null) ?: return null
        val id = prefs.getLong(KEY_HOME_MEMORIZE_ID, -1L).takeIf { it >= 0 } ?: return null
        val previous = prefs.getLong(KEY_HOME_MEMORIZE_PREVIOUS_ID, -1L).takeIf { it >= 0 }
        return MemorizePick(day, id, previous)
    }

    internal fun saveHomeMemorizePick(pick: MemorizePick) {
        prefs.edit()
            .putString(KEY_HOME_MEMORIZE_DAY, pick.day)
            .putLong(KEY_HOME_MEMORIZE_ID, pick.localId)
            .putLong(KEY_HOME_MEMORIZE_PREVIOUS_ID, pick.previousLocalId ?: -1L)
            .apply()
    }

    /** The verse image editor's last choices (#254), so the next image starts from them. Before
     *  the first image, the style is the one named after the app's color theme. */
    fun loadVerseImageOptions(): VerseImageOptions = VerseImageOptions(
        style = VerseImageStyle.fromStored(prefs.getString(KEY_VERSE_IMAGE_STYLE, null), loadThemePalette()),
        format = VerseImageFormat.fromStored(prefs.getString(KEY_VERSE_IMAGE_FORMAT, null)),
        showVerseNumbers = prefs.getBoolean(KEY_VERSE_IMAGE_NUMBERS, true),
        centered = prefs.getBoolean(KEY_VERSE_IMAGE_CENTERED, true),
        showMark = prefs.getBoolean(KEY_VERSE_IMAGE_MARK, true),
        showLabel = prefs.getBoolean(KEY_VERSE_IMAGE_LABEL, true)
    )

    /** The daily verse wallpaper (#257). Kept by [DailyWallpaper] in its own file, which backups
     *  don't copy; its style defaults to the one named after the app's color theme. */
    fun loadDailyWallpaper(): DailyWallpaperSettings =
        DailyWallpaper.load(context, VerseImageStyle.forPalette(loadThemePalette()))

    /** Saves it and starts or stops the daily updates; turning it on sets today's wallpaper. */
    fun saveDailyWallpaper(settings: DailyWallpaperSettings) = DailyWallpaper.save(context, settings)

    fun saveVerseImageOptions(options: VerseImageOptions) {
        prefs.edit()
            .putString(KEY_VERSE_IMAGE_STYLE, options.style.name)
            .putString(KEY_VERSE_IMAGE_FORMAT, options.format.name)
            .putBoolean(KEY_VERSE_IMAGE_NUMBERS, options.showVerseNumbers)
            .putBoolean(KEY_VERSE_IMAGE_CENTERED, options.centered)
            .putBoolean(KEY_VERSE_IMAGE_MARK, options.showMark)
            .putBoolean(KEY_VERSE_IMAGE_LABEL, options.showLabel)
            .apply()
    }

    fun saveVerseTextScale(scale: VerseTextScale) {
        prefs.edit().putFloat(KEY_VERSE_TEXT_SCALE, scale.multiplier).apply()
    }

    fun loadVerseTextScale(): VerseTextScale =
        VerseTextScale.fromMultiplier(prefs.getFloat(KEY_VERSE_TEXT_SCALE, VerseTextScale.DEFAULT.multiplier))

    /**
     * Persisted across process restarts so the app reopens on the last book/chapter/verse read,
     * in [versionId]. Only an actual move counts: reopening the stored position (every app start)
     * changes nothing. A move also records when it happened — the time sync sends as the
     * position's `readAt` (#82). Returns that time, or null if nothing moved.
     */
    fun saveReadingPosition(bookId: Int, chapter: Int, verse: Int, versionId: String): String? = synchronized(readingLock) {
        if (ReadingPosition(bookId, chapter, verse) == loadReadingPosition()) return null
        val changedAt = isoTimestampNow()
        prefs.edit()
            .putInt(KEY_READING_BOOK, bookId)
            .putInt(KEY_READING_CHAPTER, chapter)
            .putInt(KEY_READING_VERSE, verse)
            .putString(KEY_READING_VERSION, versionId)
            .putString(KEY_READING_CHANGED_AT, changedAt)
            .apply()
        changedAt
    }

    fun loadReadingPosition(): ReadingPosition = ReadingPosition(
        bookId = prefs.getInt(KEY_READING_BOOK, 1),
        chapter = prefs.getInt(KEY_READING_CHAPTER, 1),
        verse = prefs.getInt(KEY_READING_VERSE, 1)
    )

    override fun loadReadingProgress(): LocalReadingProgress = synchronized(readingLock) {
        LocalReadingProgress(
            position = loadReadingPosition(),
            // Positions saved before the version was stored with them (#82) fall back to the
            // selected one, which is what was pushed for them back then.
            versionId = prefs.getString(KEY_READING_VERSION, null) ?: loadSelectedVersion(),
            changedAt = prefs.getString(KEY_READING_CHANGED_AT, null),
            pushedAt = prefs.getString(KEY_READING_PUSHED_AT, null),
            hasLocalPosition = prefs.contains(KEY_READING_BOOK)
        )
    }

    override fun markReadingPositionPushed(readAt: String): Unit = synchronized(readingLock) {
        if (prefs.getString(KEY_READING_CHANGED_AT, null) == readAt) {
            prefs.edit().putString(KEY_READING_PUSHED_AT, readAt).apply()
        }
    }

    /** Stores [server]'s position as already in sync (changed and pushed at its own time), so it
     *  isn't sent back. Skipped if the position moved here since [expectedChangedAt] was read. */
    override fun saveReadingPositionFromServer(server: ReadingProgressResponseDto, expectedChangedAt: String?): Boolean =
        synchronized(readingLock) {
            if (prefs.getString(KEY_READING_CHANGED_AT, null) != expectedChangedAt) return false
            prefs.edit()
                .putInt(KEY_READING_BOOK, server.bookId)
                .putInt(KEY_READING_CHAPTER, server.chapter)
                .putInt(KEY_READING_VERSE, server.verse)
                .putString(KEY_READING_VERSION, server.versionId)
                .putString(KEY_READING_CHANGED_AT, server.updatedAt)
                .putString(KEY_READING_PUSHED_AT, server.updatedAt)
                .apply()
            true
        }

    /** The single reading bookmark — where the user marked they're actually reading, separate
     *  from [loadReadingPosition], which follows every jump. Device-local and translation-independent. */
    fun saveBookmark(position: ReadingPosition?) {
        prefs.edit().apply {
            if (position == null) {
                remove(KEY_BOOKMARK_BOOK).remove(KEY_BOOKMARK_CHAPTER).remove(KEY_BOOKMARK_VERSE)
            } else {
                putInt(KEY_BOOKMARK_BOOK, position.bookId)
                putInt(KEY_BOOKMARK_CHAPTER, position.chapter)
                putInt(KEY_BOOKMARK_VERSE, position.verse)
            }
        }.apply()
    }

    fun loadBookmark(): ReadingPosition? {
        if (!prefs.contains(KEY_BOOKMARK_BOOK)) return null
        return ReadingPosition(
            bookId = prefs.getInt(KEY_BOOKMARK_BOOK, 1),
            chapter = prefs.getInt(KEY_BOOKMARK_CHAPTER, 1),
            verse = prefs.getInt(KEY_BOOKMARK_VERSE, 1)
        )
    }

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

    /** Set by SyncWorker only once it's given up retrying a pass (see MAX_RETRY_ATTEMPTS),
     *  cleared on the next fully successful one — a simple "is the *last* attempt's outcome an
     *  error" flag rather than a log, so it never needs timestamp-ordering against
     *  [loadLastSyncCompletedAt] to know which is current (Phase H). Drives Settings' "Sync
     *  failed" status line. */
    fun isLastSyncFailed(): Boolean = prefs.getBoolean(KEY_LAST_SYNC_FAILED, false)
    fun saveLastSyncFailed(failed: Boolean) {
        prefs.edit().putBoolean(KEY_LAST_SYNC_FAILED, failed).apply()
    }

    /** Sign-out (#81): the account's highlights and notes leave this device — they're safe on the
     *  server and come back on the next sign-in — so the next person to sign in here never sees
     *  them, and none of them get uploaded to another account. Bible Helper conversations, kept
     *  only on the phone, are removed for good (#127). */
    suspend fun removeAccountContent() = withContext(Dispatchers.IO) {
        BibleDatabaseManager.deleteAllUserContent(context)
        clearSyncState(context)
        MemorizeWidgetUpdates.refreshNow(context) // its passage left with the account (#364)
    }

    /**
     * Called on every sign-in with the account's id (#81). Signing into a different account than
     * the one this device's content was synced with (e.g. after a session expired without a
     * sign-out) removes the old account's content and sync state first, so the new account
     * downloads all of its own and none of the old one's is pushed to it. Items made on this
     * device that were never uploaded stay, and go to the new account like any signed-out work.
     */
    suspend fun adoptContentOwner(userId: String?) = withContext(Dispatchers.IO) {
        val owner = prefs.getString(KEY_CONTENT_OWNER, null)
        if (owner != null && owner != userId) {
            BibleDatabaseManager.deleteContentSyncedToAnAccount(context)
            clearSyncState(context)
        }
        prefs.edit().putString(KEY_CONTENT_OWNER, userId).apply()
    }

    /** For installs from before [adoptContentOwner] existed: the account already signed in owns
     *  what's on the device. */
    fun adoptContentOwnerIfUnknown(userId: String?) {
        if (userId != null && !prefs.contains(KEY_CONTENT_OWNER)) {
            prefs.edit().putString(KEY_CONTENT_OWNER, userId).apply()
        }
    }

    internal companion object {
        private const val PREFS_NAME = "bible_prefs"

        /** Guards the reading position's compare-then-write steps (#82): the reader saves it on
         *  the main thread while a sync pass reads and writes it in the background. */
        private val readingLock = Any()

        /** Forgets the sync watermarks and status, so the next sync pulls everything, and which
         *  account the content belongs to. Also forgets when the reading position last changed,
         *  so a position from before isn't pushed as new to whatever account syncs next (#82).
         *  Also called by [BibleDatabaseManager] whenever it installs a fresh DB with no
         *  highlights or notes in it: e.g. after a cloud restore, which brings back these prefs
         *  but not the DB (see res/xml/data_extraction_rules.xml). Keeping the old watermark then
         *  would skip everything the server had before it. */
        fun clearSyncState(context: Context) {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_HIGHLIGHTS_SYNC_WATERMARK)
                .remove(KEY_NOTES_SYNC_WATERMARK)
                .remove(KEY_MEMORY_VERSES_SYNC_WATERMARK)
                .remove(KEY_LAST_SYNC_COMPLETED_AT)
                .remove(KEY_LAST_SYNC_FAILED)
                .remove(KEY_CONTENT_OWNER)
                .remove(KEY_READING_CHANGED_AT)
                .remove(KEY_READING_PUSHED_AT)
                .apply()
        }

        const val KEY_SELECTED_VERSION = "selected_version_id"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_THEME_PALETTE = "theme_palette"
        const val KEY_WIDGET_COLORS = "widget_colors"
        const val KEY_HOME_MEMORIZE_DAY = "home_memorize_pick_day"
        const val KEY_HOME_MEMORIZE_ID = "home_memorize_pick_id"
        const val KEY_HOME_MEMORIZE_PREVIOUS_ID = "home_memorize_pick_previous_id"
        const val KEY_VERSE_TEXT_SCALE = "verse_text_scale"
        const val KEY_VERSE_IMAGE_STYLE = "verse_image_style"
        const val KEY_VERSE_IMAGE_FORMAT = "verse_image_format"
        const val KEY_VERSE_IMAGE_NUMBERS = "verse_image_numbers"
        const val KEY_VERSE_IMAGE_CENTERED = "verse_image_centered"
        const val KEY_VERSE_IMAGE_MARK = "verse_image_mark"
        const val KEY_VERSE_IMAGE_LABEL = "verse_image_label"
        const val KEY_READING_BOOK = "reading_book_id"
        const val KEY_READING_CHAPTER = "reading_chapter"
        const val KEY_READING_VERSE = "reading_verse"
        const val KEY_READING_VERSION = "reading_version_id"
        const val KEY_READING_CHANGED_AT = "reading_changed_at"
        const val KEY_READING_PUSHED_AT = "reading_pushed_at"
        const val KEY_CONTENT_OWNER = "content_owner_user_id"
        const val KEY_BOOKMARK_BOOK = "bookmark_book_id"
        const val KEY_BOOKMARK_CHAPTER = "bookmark_chapter"
        const val KEY_BOOKMARK_VERSE = "bookmark_verse"
        const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        const val KEY_NOTIFICATION_HOUR = "notification_hour"
        const val KEY_NOTIFICATION_MINUTE = "notification_minute"
        const val KEY_HIGHLIGHTS_SYNC_WATERMARK = "highlights_sync_watermark"
        const val KEY_NOTES_SYNC_WATERMARK = "notes_sync_watermark"
        const val KEY_MEMORY_VERSES_SYNC_WATERMARK = "memory_verses_sync_watermark"
        const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        const val KEY_WIFI_ONLY_SYNC = "wifi_only_sync"
        const val KEY_LAST_SYNC_COMPLETED_AT = "last_sync_completed_at"
        const val KEY_LAST_SYNC_FAILED = "last_sync_failed"
        const val DEFAULT_NOTIFICATION_HOUR = 8
        const val DEFAULT_NOTIFICATION_MINUTE = 0

        // The catalog rarely changes; this just bounds how stale the picker's list can
        // get without forcing a fresh 850KB fetch on every cold start.
        const val TRANSLATIONS_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        /** Bump when [BibleTranslation] gains data an older cache file wouldn't have. 2 = licenses (#98). */
        private const val TRANSLATIONS_CACHE_FORMAT = 2
    }
}