package com.application.bibleapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.ChapterStructure
import com.application.bibleapp.data.model.DEFAULT_VERSION
import com.application.bibleapp.data.model.DailyVerseRef
import com.application.bibleapp.data.model.DailyVerseUI
import com.application.bibleapp.data.model.DownloadedVersionInfo
import com.application.bibleapp.data.model.Footnote
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.Note
import com.application.bibleapp.data.model.SelectedBibleVersion
import com.application.bibleapp.data.model.VerseOfTheDay
import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.data.model.chapterCount
import com.application.bibleapp.data.model.resolveDailyVerseUI
import com.application.bibleapp.data.remote.LanguageGroup
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.data.remote.groupVersionsByLanguage
import com.application.bibleapp.data.repository.AuthRepository
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.data.repository.NotificationTime
import com.application.bibleapp.data.repository.ReadingPosition
import com.application.bibleapp.data.repository.ReadingProgressRepository
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.VerseTextScale
import com.application.bibleapp.utils.buildVerseShareText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.application.bibleapp.R
import com.application.bibleapp.utils.UiText
import com.application.bibleapp.data.local.NoContentException
import com.application.bibleapp.data.local.NoInternetException

/**
 * State for the open note editor sheet — either a new note being composed from the current
 * verse selection ([existingNote] null) or an existing one opened via its inline glyph
 * (see BibleText's note-glyph tap handling). [verses] is a working copy the sheet can remove
 * chips from before saving; the contract requires at least one verse (see api_contract.md
 * decision 17), enforced by [BibleViewModel.removeVerseFromNoteEditor] refusing to drop the last one.
 */
data class NoteEditorState(
    val existingNote: Note?,
    val verses: List<VerseLocationDto>,
    val text: String
) {
    /** Whether closing the editor now would lose something — a non-blank new note, or any edit
     *  to an existing note's text or verses. Drives NoteEditorSheet's "Discard note?" guard. */
    val hasUnsavedChanges: Boolean
        get() = if (existingNote == null) text.isNotBlank()
        else text != existingNote.text || verses != existingNote.verses
}

/** Something deleted that a Snackbar's Undo can bring back — see [BibleViewModel.undoDeletion]. */
sealed interface UndoableDeletion {
    data class OfHighlight(val highlight: Highlight) : UndoableDeletion
    data class OfNote(val note: Note) : UndoableDeletion
    data class OfBookmark(val bookmark: ReadingPosition) : UndoableDeletion
}

/** A one-shot message for MainActivity's Snackbar — with [undo], it gets an "Undo" action. */
data class UserMessage(val text: UiText, val undo: UndoableDeletion? = null)

/** Looks up one verse's cached scripture text (see [BibleViewModel.libraryVerseText]) — a location
 *  alone isn't a safe key since the same verse's text can differ across versions. */
data class VerseTextKey(val versionId: String, val location: VerseLocationDto)

/** A stray pilcrow the plain-text fallback (no rich content) can't turn into a real paragraph
 *  break — same marker BibleText strips for the reading view; the Library screen's verse
 *  previews need the same cleanup. */
private const val LEGACY_PARAGRAPH_MARKER = "¶ "

/**
 * Version state is split across three flows that the version picker composes
 * together rather than one combined "picker state" object:
 * - [selectedVersion] — which translation is on screen right now.
 * - [downloadedVersions] — every translation that actually has rows in local
 *   storage, keyed by id with a [DownloadedVersionInfo.schemaVersion] so the
 *   picker can tell "downloaded" apart from "downloaded but stale" (see
 *   [refreshDownloadedVersions]). This is queried from SQLite once per
 *   version-list load, not per row, and re-queried after every
 *   download/re-download so it never drifts from what's on disk.
 * - [downloadingVersionId]/[downloadProgress] — in-flight download state,
 *   `null` when nothing is downloading.
 *
 * Kept separate because they change independently: switching versions doesn't
 * touch the downloaded set, and a download in progress doesn't change which
 * version is currently selected until it finishes.
 */
class BibleViewModel(
    private val repository: BibleRepository,
    private val authRepository: AuthRepository,
    private val readingProgressRepository: ReadingProgressRepository
) : ViewModel() {

    private val _verses = MutableStateFlow<List<VerseUI>>(emptyList())
    val verses: StateFlow<List<VerseUI>> = _verses.asStateFlow()

    private val _footnotes = MutableStateFlow<List<Footnote>>(emptyList())
    val footnotes: StateFlow<List<Footnote>> = _footnotes.asStateFlow()

    // The footnote currently shown in a bottom sheet, or null when none is open.
    private val _selectedFootnote = MutableStateFlow<Footnote?>(null)
    val selectedFootnote: StateFlow<Footnote?> = _selectedFootnote.asStateFlow()

    // Active highlights covering the chapter currently on screen — reloaded on every
    // loadChapter() and after any create/recolor/delete (see data/local/BibleDatabaseManager.kt).
    private val _highlightsInChapter = MutableStateFlow<List<Highlight>>(emptyList())
    val highlightsInChapter: StateFlow<List<Highlight>> = _highlightsInChapter.asStateFlow()

    // Verses the user has long-pressed/tapped into selection, before choosing a highlight color.
    // Non-empty means "selection mode" is active — see onVerseTap/onVerseLongPress.
    private val _selectedVerses = MutableStateFlow<Set<VerseLocationDto>>(emptySet())
    val selectedVerses: StateFlow<Set<VerseLocationDto>> = _selectedVerses.asStateFlow()

    // The highlight created (or already covering) the current, still-open selection — set the
    // first time a color is picked for it, so picking a *different* color afterward (without
    // cancelling first) recolors that same highlight instead of stacking a duplicate on top of
    // it. Reset whenever the selected verse set itself changes, since a highlight's verse list
    // is immutable and an id tracked against the old set is meaningless once it's changed.
    private var activeSelectionHighlightId: Long? = null

    // The highlight whose "change color / remove" sheet is open, or null when none is —
    // shown when tapping (not long-pressing) an already-highlighted verse outside selection mode.
    private val _highlightPopup = MutableStateFlow<Highlight?>(null)
    val highlightPopup: StateFlow<Highlight?> = _highlightPopup.asStateFlow()

    // Active notes covering the chapter currently on screen — same reload points as
    // _highlightsInChapter (loadChapter, and after any create/update/delete).
    private val _notesInChapter = MutableStateFlow<List<Note>>(emptyList())
    val notesInChapter: StateFlow<List<Note>> = _notesInChapter.asStateFlow()

    // The open note editor sheet's state, or null when it's closed — see [NoteEditorState].
    private val _noteEditor = MutableStateFlow<NoteEditorState?>(null)
    val noteEditor: StateFlow<NoteEditorState?> = _noteEditor.asStateFlow()

    // One-shot Snackbar messages (deletion undo, copy confirmation) — a SharedFlow, not state,
    // so a message is shown once rather than re-shown on every recomposition/config change.
    private val _userMessages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<UserMessage> = _userMessages.asSharedFlow()

    // The verse whose notes list sheet is open (a verse with 2+ notes was tapped), or null.
    private val _noteListVerse = MutableStateFlow<VerseLocationDto?>(null)
    val noteListVerse: StateFlow<VerseLocationDto?> = _noteListVerse.asStateFlow()

    // Every active highlight/note across all chapters — the Library screen's data, loaded on
    // demand (not kept live like _highlightsInChapter/_notesInChapter, since it's only ever
    // shown on its own screen) via [loadLibrary].
    private val _libraryHighlights = MutableStateFlow<List<Highlight>>(emptyList())
    val libraryHighlights: StateFlow<List<Highlight>> = _libraryHighlights.asStateFlow()

    private val _libraryNotes = MutableStateFlow<List<Note>>(emptyList())
    val libraryNotes: StateFlow<List<Note>> = _libraryNotes.asStateFlow()

    // Scripture text for every verse a library highlight/note covers, keyed by the version it was
    // made in (a highlight/note is immutable to its own version, and the same verse's text can
    // differ across versions) — the Library screen wants to show the actual verse, not just its
    // reference, but Highlight/Note only ever store locations, not text.
    private val _libraryVerseText = MutableStateFlow<Map<VerseTextKey, String>>(emptyMap())
    val libraryVerseText: StateFlow<Map<VerseTextKey, String>> = _libraryVerseText.asStateFlow()

    // Prompts sign-in the first time someone highlights or notes a verse while signed out —
    // not a wall (the feature already worked, local-first), just a one-time nudge that it'd
    // sync if they did. Shown at most once per app session: [signInNudgeOffered] tracks
    // "already offered" independent of [_showSignInNudge]'s own true/false, so dismissing it
    // doesn't cause the next highlight to immediately offer it again.
    private var signInNudgeOffered = false
    private val _showSignInNudge = MutableStateFlow(false)
    val showSignInNudge: StateFlow<Boolean> = _showSignInNudge.asStateFlow()

    private fun maybeOfferSignIn() {
        if (!authRepository.isLoggedIn && !signInNudgeOffered) {
            signInNudgeOffered = true
            _showSignInNudge.value = true
        }
    }

    fun dismissSignInNudge() {
        _showSignInNudge.value = false
    }

    private val _currentBook = MutableStateFlow(1)
    val currentBook: StateFlow<Int> = _currentBook

    // Book names in the selected version's own language, keyed by book_id — empty
    // for the bundled KJV and for a version downloaded before this was captured, in
    // which case lookups fall back to BibleBooks' hardcoded English names.
    private val _bookNames = MutableStateFlow<Map<Int, String>>(emptyMap())
    val bookNames: StateFlow<Map<Int, String>> = _bookNames.asStateFlow()

    // The selected version's own chapter/verse structure — empty (falls back to
    // BibleBooks' KJV-based structure) for the same reasons as _bookNames above.
    // Loaded alongside it everywhere the selected version changes.
    private val _chapterStructure = MutableStateFlow<ChapterStructure>(emptyMap())
    val chapterStructure: StateFlow<ChapterStructure> = _chapterStructure.asStateFlow()

    /** The currently-open book's name, in the selected version's own language. */
    val currentBookName: StateFlow<String> =
        combine(_currentBook, _bookNames) { bookId, names ->
            names[bookId] ?: BibleBooks.getBookById(bookId)?.name ?: "Unknown"
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _currentChapter = MutableStateFlow(1)
    val currentChapter: StateFlow<Int> = _currentChapter

    private val _currentVerse = MutableStateFlow(1)
    val currentVerse: StateFlow<Int> = _currentVerse

    // Bumped to make the reader scroll to [currentVerse] again even when that value hasn't
    // changed — e.g. jumping to a bookmark in the chapter already on screen after scrolling away.
    private val _scrollRequest = MutableStateFlow(0)
    val scrollRequest: StateFlow<Int> = _scrollRequest.asStateFlow()

    // The verse at the top of the reader right now — what a new bookmark points at. Not state:
    // nothing renders it, and it changes on every scroll.
    private var topVisibleVerse = 1

    private val _bookmark = MutableStateFlow(repository.loadBookmark())
    val bookmark: StateFlow<ReadingPosition?> = _bookmark.asStateFlow()

    /** The bookmark as a display reference ("John 3:16") in the current translation's book names. */
    val bookmarkLabel: StateFlow<String?> =
        combine(_bookmark, _bookNames) { bookmark, names -> bookmark?.let { bookmarkLabelFor(it, names) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun bookmarkLabelFor(position: ReadingPosition, names: Map<Int, String> = _bookNames.value): String =
        "${names[position.bookId] ?: BibleBooks.getBookById(position.bookId)?.name ?: "Unknown"} ${position.chapter}:${position.verse}"

    private val _availableVersions = MutableStateFlow<List<BibleTranslation>>(emptyList())
    val availableVersions: StateFlow<List<BibleTranslation>> = _availableVersions.asStateFlow()

    private val _isLoadingVersions = MutableStateFlow(false)
    val isLoadingVersions: StateFlow<Boolean> = _isLoadingVersions.asStateFlow()

    private val _versionsError = MutableStateFlow<String?>(null)
    val versionsError: StateFlow<String?> = _versionsError.asStateFlow()

    private val _versionSearchQuery = MutableStateFlow("")
    val versionSearchQuery: StateFlow<String> = _versionSearchQuery.asStateFlow()

    // Search-as-you-type for the Search screen; it reads the version at search time.
    private val verseSearch = VerseSearch(
        scope = viewModelScope,
        search = repository::searchVerses,
        currentVersionId = { _selectedVersion.value.id }
    )
    val searchQuery: StateFlow<String> = verseSearch.query
    val searchState: StateFlow<SearchUiState> = verseSearch.state

    // Derived from availableVersions + the search query — recomputed locally, no re-fetch.
    val groupedVersions: StateFlow<List<LanguageGroup>> =
        combine(_availableVersions, _versionSearchQuery) { versions, query ->
            groupVersionsByLanguage(versions, query)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedVersion = MutableStateFlow(DEFAULT_VERSION)
    val selectedVersion: StateFlow<SelectedBibleVersion> = _selectedVersion.asStateFlow()

    /**
     * Short label for the version button (e.g. "KJV", "ESV") — looked up from the
     * translation catalog by id. Falls back to "KJV" for the bundled default (it has no
     * catalog entry, it isn't a real helloao translation id) and to the raw id, uppercased,
     * if the catalog hasn't loaded yet.
     */
    val currentVersionLabel: StateFlow<String> =
        combine(_selectedVersion, _availableVersions) { selected, versions ->
            versions.firstOrNull { it.id == selected.id }?.displayName
                ?: if (selected.id == DEFAULT_VERSION.id) "KJV" else selected.id.uppercase()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "KJV")

    private val _themeMode = MutableStateFlow(repository.loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _verseTextScale = MutableStateFlow(repository.loadVerseTextScale())
    val verseTextScale: StateFlow<VerseTextScale> = _verseTextScale.asStateFlow()

    // Shown on Home — fetched independently of currentBook/currentChapter so it
    // doesn't disturb the user's actual reading position.
    private val _verseOfTheDay = MutableStateFlow<DailyVerseUI?>(null)
    val verseOfTheDay: StateFlow<DailyVerseUI?> = _verseOfTheDay.asStateFlow()

    private val _notificationEnabled = MutableStateFlow(repository.isNotificationEnabled())
    val notificationEnabled: StateFlow<Boolean> = _notificationEnabled.asStateFlow()

    private val _notificationTime = MutableStateFlow(repository.loadNotificationTime())
    val notificationTime: StateFlow<NotificationTime> = _notificationTime.asStateFlow()

    // ---- Sync preferences & status (Phase G) — actual WorkManager (re)scheduling happens in the
    // composable that has a Context (SettingsView), same split already used for
    // SyncScheduler.triggerImmediateSync in Navigation.kt; this ViewModel just owns the
    // persisted values and the StateFlows Settings observes. ----

    private val _autoSyncEnabled = MutableStateFlow(repository.isAutoSyncEnabled())
    val autoSyncEnabled: StateFlow<Boolean> = _autoSyncEnabled.asStateFlow()

    private val _wifiOnlySync = MutableStateFlow(repository.isWifiOnlySyncEnabled())
    val wifiOnlySync: StateFlow<Boolean> = _wifiOnlySync.asStateFlow()

    private val _lastSyncCompletedAt = MutableStateFlow(repository.loadLastSyncCompletedAt())
    val lastSyncCompletedAt: StateFlow<String?> = _lastSyncCompletedAt.asStateFlow()

    // Phase H — mutually exclusive with a fresh success (SyncWorker clears this the moment a pass
    // fully succeeds), so it's always the current truth without comparing timestamps against
    // lastSyncCompletedAt to see which is "newer".
    private val _lastSyncFailed = MutableStateFlow(repository.isLastSyncFailed())
    val lastSyncFailed: StateFlow<Boolean> = _lastSyncFailed.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    fun setAutoSyncEnabled(enabled: Boolean) {
        _autoSyncEnabled.value = enabled
        repository.setAutoSyncEnabled(enabled)
    }

    fun setWifiOnlySync(enabled: Boolean) {
        _wifiOnlySync.value = enabled
        repository.setWifiOnlySyncEnabled(enabled)
    }

    /** Re-reads the persisted "last synced"/"last failed" stamps — call when Settings opens,
     *  since a background periodic sync could have updated either since the ViewModel's initial
     *  value was read. */
    fun refreshSyncStatus() {
        _lastSyncCompletedAt.value = repository.loadLastSyncCompletedAt()
        _lastSyncFailed.value = repository.isLastSyncFailed()
    }

    /** Drives a short-lived spinner for the manual "Sync now" button — polls for
     *  [BibleRepository.loadLastSyncCompletedAt] to change rather than wiring a full WorkManager
     *  WorkInfo observer just for this one button. The actual trigger
     *  ([com.application.bibleapp.worker.SyncScheduler.triggerImmediateSync]) is called by the
     *  composable, which has the Context it needs; this just watches for it to finish.
     *
     *  Doesn't poll [BibleRepository.isLastSyncFailed] to end the wait early — SyncWorker only
     *  sets it once retries are exhausted (see MAX_RETRY_ATTEMPTS), and WorkManager's backoff
     *  between retries already runs well past this 10s window, so a manual tap can't observe a
     *  "final" failure this way; it can only ever time out waiting on one. The status line still
     *  picks up the flag on the next [refreshSyncStatus] once a background retry does resolve. */
    fun awaitManualSync() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        val before = repository.loadLastSyncCompletedAt()
        viewModelScope.launch {
            repeat(20) {
                delay(500)
                val current = repository.loadLastSyncCompletedAt()
                if (current != before) {
                    _lastSyncCompletedAt.value = current
                    _lastSyncFailed.value = false
                    _isSyncing.value = false
                    return@launch
                }
            }
            _isSyncing.value = false
        }
    }

    /** See [BibleRepository.detachLocalContentFromDeletedAccount] — called once account deletion
     *  succeeds (Phase H). Refreshes the sync status flows afterward since the watermarks/status
     *  it just cleared feed straight into them. */
    fun detachLocalContentFromDeletedAccount() {
        viewModelScope.launch {
            repository.detachLocalContentFromDeletedAccount()
            refreshSyncStatus()
        }
    }

    // Locally downloaded versions, keyed by id, so the picker can badge "downloaded" /
    // "update available" without a DB query per row. Refreshed after every download.
    private val _downloadedVersions = MutableStateFlow<Map<String, DownloadedVersionInfo>>(emptyMap())
    val downloadedVersions: StateFlow<Map<String, DownloadedVersionInfo>> = _downloadedVersions.asStateFlow()

    // Download state
    private val _downloadingVersionId = MutableStateFlow<String?>(null)
    val downloadingVersionId: StateFlow<String?> = _downloadingVersionId.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadError = MutableStateFlow<UiText?>(null)
    val downloadError: StateFlow<UiText?> = _downloadError.asStateFlow()

    // Informational (not an error): set when a download succeeded but some content
    // wasn't available for this version (e.g. an NT-only translation).
    private val _downloadInfo = MutableStateFlow<UiText?>(null)
    val downloadInfo: StateFlow<UiText?> = _downloadInfo.asStateFlow()

    init {
        // The last version the user picked is persisted across process restarts. Only
        // trust it if it's still actually downloaded — local data may have been cleared.
        viewModelScope.launch {
            val persistedId = repository.loadSelectedVersion()
            val isAvailable = persistedId == DEFAULT_VERSION.id || repository.isVersionDownloaded(persistedId)
            _selectedVersion.value = if (isAvailable) SelectedBibleVersion(id = persistedId) else DEFAULT_VERSION
            _bookNames.value = repository.getBookNames(_selectedVersion.value.id)
            _chapterStructure.value = repository.getChapterStructure(_selectedVersion.value.id)
            val lastPosition = repository.loadReadingPosition()
            loadChapter(lastPosition.bookId, lastPosition.chapter, lastPosition.verse)
            loadVerseOfTheDay()
        }
        loadAvailableVersions()
        refreshDownloadedVersions()
    }

    // Tries the remote daily-verse API first (cached once per day by the repository);
    // falls back to the local curated list if the app is offline, the API is down, or
    // it returns a reference this app can't resolve (unrecognized book name, verse not
    // present in the currently selected translation, etc.) — this must never throw, so
    // the Home screen always has something to show instead of crashing on launch.
    private fun loadVerseOfTheDay() {
        viewModelScope.launch {
            val remoteVerse = runCatching { repository.getDailyVerse() }
                .mapCatching { ref -> resolveVerse(ref) ?: error("Reference not found: $ref") }
                .getOrNull()
            _verseOfTheDay.value = remoteVerse ?: resolveVerse(VerseOfTheDay.forToday())
        }
    }

    private suspend fun resolveVerse(ref: DailyVerseRef): DailyVerseUI? {
        val bookName = _bookNames.value[ref.bookId] ?: BibleBooks.getBookById(ref.bookId)?.name ?: return null
        val chapterVerses = repository.getChapter(ref.bookId, ref.chapter, _selectedVersion.value.id)
        return resolveDailyVerseUI(bookName, ref, chapterVerses)
    }

    fun loadChapter(bookId: Int, chapterId: Int, verseId: Int = 1) {
        // A new chapter means any in-progress selection or open popup belongs to verses that
        // are about to leave the screen.
        _selectedVerses.value = emptySet()
        activeSelectionHighlightId = null
        _highlightPopup.value = null
        _noteEditor.value = null
        viewModelScope.launch {
            val versionId = _selectedVersion.value.id
            val chapterData = repository.getChapter(bookId = bookId, chapter = chapterId, versionId = versionId)
            _verses.value = chapterData
            _footnotes.value = repository.getFootnotes(bookId = bookId, chapter = chapterId, versionId = versionId)
            _currentBook.value = bookId
            _currentChapter.value = chapterId
            val verse = verseId.coerceIn(1, chapterData.size.coerceAtLeast(1))
            _currentVerse.value = verse
            repository.saveReadingPosition(bookId, chapterId, verse)
            syncReadingProgressToServer(versionId, bookId, chapterId, verse)
            _highlightsInChapter.value = repository.getHighlightsForChapter(bookId, chapterId)
            _notesInChapter.value = repository.getNotesForChapter(bookId, chapterId)
        }
    }

    private fun reloadHighlightsInChapter() {
        viewModelScope.launch {
            _highlightsInChapter.value = repository.getHighlightsForChapter(_currentBook.value, _currentChapter.value)
        }
    }

    private fun reloadNotesInChapter() {
        viewModelScope.launch {
            _notesInChapter.value = repository.getNotesForChapter(_currentBook.value, _currentChapter.value)
        }
    }

    /** Long-press always starts/extends a selection, regardless of whether the verse is already highlighted. */
    fun onVerseLongPress(location: VerseLocationDto) {
        toggleVerseSelection(location)
    }

    /** In selection mode, a tap toggles that verse. Outside selection mode, tapping an
     *  already-highlighted verse opens its change-color/remove sheet (which links its notes);
     *  tapping a verse with only notes opens them the same way its badge does, so the small
     *  badge isn't the only way in; tapping a plain verse is a no-op. */
    fun onVerseTap(location: VerseLocationDto) {
        if (_selectedVerses.value.isNotEmpty()) {
            toggleVerseSelection(location)
            return
        }
        // Most recent wins if an overlap from before decision 19 (or another client) survives —
        // the same one BibleView paints, so the sheet matches the color on screen.
        val highlight = _highlightsInChapter.value.filter { location in it.verses }.maxByOrNull { it.createdAt }
        if (highlight != null) {
            _highlightPopup.value = highlight
        } else {
            onNoteBadgeTap(location)
        }
    }

    private fun toggleVerseSelection(location: VerseLocationDto) {
        // The set is about to change, so any highlight tracked against the *old* set no longer
        // means anything — see activeSelectionHighlightId's own doc comment.
        activeSelectionHighlightId = null
        _selectedVerses.update { current ->
            if (location in current) current - location else current + location
        }
    }

    /** Copy/Share text for the current selection (see [buildVerseShareText]), or null when
     *  nothing is selected. Ends selection mode — both actions hand the verses off and are done. */
    fun takeSelectionShareText(): String? {
        val selection = _selectedVerses.value.toList()
        if (selection.isEmpty()) return null
        val textByLocation = _verses.value.mapNotNull { verse ->
            val bookId = verse.bookId ?: return@mapNotNull null
            val chapter = verse.chapter ?: return@mapNotNull null
            val number = verse.verse ?: return@mapNotNull null
            VerseLocationDto(bookId, chapter, number) to verse.text.removePrefix(LEGACY_PARAGRAPH_MARKER)
        }.toMap()
        val names = _bookNames.value
        val text = buildVerseShareText(
            verses = selection,
            textOf = textByLocation::get,
            bookName = { id -> names[id] ?: BibleBooks.getBookById(id)?.name ?: "" },
            versionLabel = currentVersionLabel.value
        )
        clearSelection()
        return text
    }

    /** For confirmations the system doesn't already show (e.g. copy on Android 12 and older). */
    fun showMessage(text: UiText) {
        _userMessages.tryEmit(UserMessage(text))
    }

    fun clearSelection() {
        _selectedVerses.value = emptySet()
        activeSelectionHighlightId = null
    }

    /** Applies a color to every currently selected verse — the *first* color choice for this
     *  selection creates a new highlight; picking a different color afterward, without
     *  cancelling first, recolors that same highlight instead of stacking a duplicate on top of
     *  it. Deliberately leaves the selection (and this toolbar) open afterward, so a note can
     *  still be added to the same verses without having to reselect them.
     *
     *  A verse belongs to at most one highlight (api_contract.md decision 19): selecting exactly
     *  one existing highlight's verses just recolors it; otherwise every highlight the selection
     *  overlaps gives those verses up — deleted and re-created over its remaining verses, since a
     *  highlight's verse list is immutable (decision 14) — before the new one is created. */
    fun highlightSelection(colorArgb: Int) {
        val selection = _selectedVerses.value
        if (selection.isEmpty()) return
        val versionId = _selectedVersion.value.id
        val existingId = activeSelectionHighlightId
        viewModelScope.launch {
            if (existingId != null) {
                repository.recolorHighlight(existingId, colorArgb)
            } else {
                val overlapped = _highlightsInChapter.value.filter { h -> h.verses.any { it in selection } }
                val exactMatch = overlapped.singleOrNull()?.takeIf { it.verses.toSet() == selection }
                if (exactMatch != null) {
                    repository.recolorHighlight(exactMatch.localId, colorArgb)
                    activeSelectionHighlightId = exactMatch.localId
                } else {
                    overlapped.forEach { old ->
                        repository.deleteHighlight(old.localId)
                        val remaining = old.verses.filterNot { it in selection }
                        if (remaining.isNotEmpty()) repository.createHighlight(old.versionId, remaining, old.color)
                    }
                    activeSelectionHighlightId = repository.createHighlight(versionId, selection.toList(), colorArgb).localId
                    maybeOfferSignIn()
                }
            }
            reloadHighlightsInChapter()
        }
    }

    fun recolorHighlight(highlight: Highlight, colorArgb: Int) {
        _highlightPopup.value = null
        viewModelScope.launch {
            repository.recolorHighlight(highlight.localId, colorArgb)
            reloadHighlightsInChapter()
        }
    }

    fun removeHighlight(highlight: Highlight) {
        _highlightPopup.value = null
        viewModelScope.launch {
            repository.deleteHighlight(highlight.localId)
            reloadHighlightsInChapter()
        }
        _userMessages.tryEmit(UserMessage(UiText.Res(R.string.message_highlight_removed), UndoableDeletion.OfHighlight(highlight)))
    }

    /** The Snackbar's Undo after any highlight/note deletion — restores the soft-deleted row
     *  (or re-creates it, if a sync already purged it) and refreshes both the reader and Library. */
    fun undoDeletion(deletion: UndoableDeletion) {
        viewModelScope.launch {
            when (deletion) {
                is UndoableDeletion.OfHighlight -> repository.restoreHighlight(deletion.highlight)
                is UndoableDeletion.OfNote -> repository.restoreNote(deletion.note)
                is UndoableDeletion.OfBookmark -> {
                    setBookmark(deletion.bookmark)
                    return@launch
                }
            }
            reloadHighlightsInChapter()
            reloadNotesInChapter()
            loadLibrary()
        }
    }

    fun dismissHighlightPopup() {
        _highlightPopup.value = null
    }

    /** Opens the editor to attach a new note to the current selection — sorted into Bible order
     *  since [selectedVerses] is a Set with no guaranteed iteration order (contract decision 12). */
    fun openNoteEditorForSelection() {
        val verses = _selectedVerses.value.toList().sortedWith(
            compareBy({ it.bookId }, { it.chapter }, { it.verse })
        )
        if (verses.isEmpty()) return
        _selectedVerses.value = emptySet()
        activeSelectionHighlightId = null
        _noteEditor.value = NoteEditorState(existingNote = null, verses = verses, text = "")
    }

    /** Opens the editor on an existing note — reached from its inline glyph in the reading view. */
    fun openNoteEditorForNote(note: Note) {
        _noteEditor.value = NoteEditorState(existingNote = note, verses = note.verses, text = note.text)
    }

    /** A verse's note badge was tapped: one note opens straight into the editor; several open
     *  the list sheet so every one of them is reachable, not just whichever came first. */
    fun onNoteBadgeTap(location: VerseLocationDto) {
        val notes = _notesInChapter.value.filter { location in it.verses }
        when {
            notes.size == 1 -> openNoteEditorForNote(notes.first())
            notes.size > 1 -> _noteListVerse.value = location
        }
    }

    fun dismissNoteList() {
        _noteListVerse.value = null
    }

    fun openNoteFromList(note: Note) {
        _noteListVerse.value = null
        openNoteEditorForNote(note)
    }

    /** "Add another note" from a verse's notes list — a new note over just that verse. */
    fun addNoteToVerse(location: VerseLocationDto) {
        _noteListVerse.value = null
        _noteEditor.value = NoteEditorState(existingNote = null, verses = listOf(location), text = "")
    }

    /** Reached from a Library highlight card: jumps to its chapter and arms selection mode over
     *  its exact verses, with color-picking already wired to recolor it (see
     *  [activeSelectionHighlightId]) rather than stack a duplicate on top of it — one tap away
     *  from either recoloring or attaching a note, instead of having to re-find and re-select
     *  the verse(s) by hand. */
    fun openHighlightFromLibrary(highlight: Highlight) {
        val first = highlight.verses.minWithOrNull(compareBy({ it.bookId }, { it.chapter }, { it.verse })) ?: return
        setBook(first.bookId, first.chapter, first.verse)
        _selectedVerses.value = highlight.verses.toSet()
        activeSelectionHighlightId = highlight.localId
    }

    /** Reached from a Library note card: jumps to its chapter and opens the note straight into
     *  its editor, pre-filled — generic verse selection wouldn't help edit a note's *text*, and
     *  routing it through [openNoteEditorForSelection] would risk saving a second, blank note
     *  over the same verses instead of updating this one. */
    fun openNoteFromLibrary(note: Note) {
        val first = note.verses.minWithOrNull(compareBy({ it.bookId }, { it.chapter }, { it.verse })) ?: return
        setBook(first.bookId, first.chapter, first.verse)
        openNoteEditorForNote(note)
    }

    fun updateNoteEditorText(text: String) {
        _noteEditor.update { it?.copy(text = text) }
    }

    /** No-ops if this would empty the verse list — a note must cover at least one verse
     *  (contract decision 17). */
    fun removeVerseFromNoteEditor(location: VerseLocationDto) {
        _noteEditor.update { state ->
            if (state == null || state.verses.size <= 1) state
            else state.copy(verses = state.verses.filterNot { it == location })
        }
    }

    fun dismissNoteEditor() {
        _noteEditor.value = null
    }

    /** Creates a new note, or updates the one being edited — both the verse list and text are
     *  editable in place for notes (contract decision 14, unlike highlights). */
    fun saveNoteEditor() {
        val state = _noteEditor.value ?: return
        if (state.verses.isEmpty() || state.text.isBlank()) return
        _noteEditor.value = null
        viewModelScope.launch {
            val existing = state.existingNote
            if (existing == null) {
                repository.createNote(_selectedVersion.value.id, state.verses, state.text)
                maybeOfferSignIn()
            } else {
                repository.updateNote(existing.localId, state.verses, state.text)
            }
            reloadNotesInChapter()
        }
    }

    fun deleteNoteEditor() {
        val existing = _noteEditor.value?.existingNote ?: return
        _noteEditor.value = null
        _userMessages.tryEmit(UserMessage(UiText.Res(R.string.message_note_deleted), UndoableDeletion.OfNote(existing)))
        viewModelScope.launch {
            repository.deleteNote(existing.localId)
            reloadNotesInChapter()
        }
    }

    /** Loads every active highlight/note for the Library screen, plus the actual scripture text
     *  each one covers — call once when it opens. */
    fun loadLibrary() {
        viewModelScope.launch {
            val highlights = repository.getAllActiveHighlights()
            val notes = repository.getAllActiveNotes()
            _libraryHighlights.value = highlights
            _libraryNotes.value = notes

            // One chapter fetch per distinct (version, book, chapter) triple covered by any
            // highlight/note, however many individual verses in it are actually referenced —
            // cheap local-SQLite reads, and far fewer round trips than one per verse.
            val neededChapters = (highlights.asSequence().flatMap { h -> h.verses.asSequence().map { Triple(h.versionId, it.bookId, it.chapter) } } +
                notes.asSequence().flatMap { n -> n.verses.asSequence().map { Triple(n.versionId, it.bookId, it.chapter) } })
                .toSet()

            val textByKey = mutableMapOf<VerseTextKey, String>()
            neededChapters.forEach { (versionId, bookId, chapter) ->
                repository.getChapter(bookId, chapter, versionId).forEach { verse ->
                    val v = verse.verse ?: return@forEach
                    val location = VerseLocationDto(bookId, chapter, v)
                    textByKey[VerseTextKey(versionId, location)] = verse.text.removePrefix(LEGACY_PARAGRAPH_MARKER)
                }
            }
            _libraryVerseText.value = textByKey
        }
    }

    /** Swipe-to-delete from the Library list. Also refreshes the in-chapter highlight state
     *  in case the deleted highlight belonged to whatever chapter is currently loaded in
     *  BibleView — cheap no-op reload otherwise, since the ViewModel (and its StateFlows) is
     *  shared across screens rather than recreated per navigation. */
    fun deleteLibraryHighlight(highlight: Highlight) {
        _libraryHighlights.update { it.filterNot { h -> h.localId == highlight.localId } }
        _userMessages.tryEmit(UserMessage(UiText.Res(R.string.message_highlight_removed), UndoableDeletion.OfHighlight(highlight)))
        viewModelScope.launch {
            repository.deleteHighlight(highlight.localId)
            reloadHighlightsInChapter()
        }
    }

    fun deleteLibraryNote(note: Note) {
        _libraryNotes.update { it.filterNot { n -> n.localId == note.localId } }
        _userMessages.tryEmit(UserMessage(UiText.Res(R.string.message_note_deleted), UndoableDeletion.OfNote(note)))
        viewModelScope.launch {
            repository.deleteNote(note.localId)
            reloadNotesInChapter()
        }
    }

    /** Fire-and-forget: a failed background sync shouldn't interrupt someone just reading —
     *  the position is already safely saved locally via [BibleRepository.saveReadingPosition]
     *  regardless of whether this succeeds. No-ops when signed out. */
    private fun syncReadingProgressToServer(versionId: String, bookId: Int, chapter: Int, verse: Int) {
        if (!authRepository.isLoggedIn) return
        viewModelScope.launch {
            readingProgressRepository.updateProgress(versionId, bookId, chapter, verse)
        }
    }

    /** Pulls the position saved from any other device and jumps there — called right after a
     *  successful login/register (see Navigation.kt), so signing in on a new device picks up
     *  where the user left off elsewhere instead of staying on this device's local position. */
    fun syncReadingProgressFromServer() {
        if (!authRepository.isLoggedIn) return
        viewModelScope.launch {
            readingProgressRepository.getProgress().onSuccess { remote ->
                if (remote != null) {
                    loadChapter(remote.bookId, remote.chapter, remote.verse)
                }
            }
        }
    }

    fun onTopVisibleVerseChange(verse: Int) {
        topVisibleVerse = verse
    }

    /** Bookmarks the verse at the top of the reader, replacing any earlier bookmark. */
    fun bookmarkCurrentPosition() {
        val position = ReadingPosition(_currentBook.value, _currentChapter.value, topVisibleVerse)
        setBookmark(position)
        showMessage(UiText.Res(R.string.message_bookmarked, bookmarkLabelFor(position)))
    }

    fun removeBookmark() {
        val removed = _bookmark.value ?: return
        setBookmark(null)
        _userMessages.tryEmit(UserMessage(UiText.Res(R.string.message_bookmark_removed), UndoableDeletion.OfBookmark(removed)))
    }

    private fun setBookmark(position: ReadingPosition?) {
        _bookmark.value = position
        repository.saveBookmark(position)
    }

    /** Opens the reader at the bookmark — scrolling to it even within the chapter already on screen. */
    fun goToBookmark() {
        val bookmark = _bookmark.value ?: return
        if (bookmark.bookId == _currentBook.value && bookmark.chapter == _currentChapter.value && _verses.value.isNotEmpty()) {
            _currentVerse.value = bookmark.verse
            repository.saveReadingPosition(bookmark.bookId, bookmark.chapter, bookmark.verse)
        } else {
            loadChapter(bookmark.bookId, bookmark.chapter, bookmark.verse)
        }
        _scrollRequest.update { it + 1 }
    }

    /** Opens the footnote bottom sheet for [noteId] in the chapter currently on screen, if found. */
    fun selectFootnote(noteId: Int) {
        _selectedFootnote.value = _footnotes.value.firstOrNull { it.noteId == noteId }
    }

    fun dismissFootnote() {
        _selectedFootnote.value = null
    }

    fun previousChapter() {
        if (_currentChapter.value > 1) {
            loadChapter(_currentBook.value, _currentChapter.value - 1)
        } else if (_currentBook.value > 1) {
            val previousBookId = _currentBook.value - 1
            loadChapter(previousBookId, _chapterStructure.value.chapterCount(previousBookId))
        }
    }

    fun nextChapter() {
        if (_currentChapter.value < _chapterStructure.value.chapterCount(_currentBook.value)) {
            loadChapter(_currentBook.value, _currentChapter.value + 1)
        } else if (_currentBook.value < BibleBooks.allBooks.size) {
            loadChapter(_currentBook.value + 1, 1)
        }
    }

    fun setBook(bookId: Int, chapter: Int = 1, verse: Int = 1) {
        _currentBook.value = bookId
        _currentChapter.value = chapter
        loadChapter(bookId, chapter, verse)
    }

    fun setChapter(chapterId: Int) {
        _currentChapter.value = chapterId
        loadChapter(_currentBook.value, chapterId)
    }

    fun onSearchQueryChange(newQuery: String) = verseSearch.onQueryChange(newQuery)

    /** Search now, without waiting for typing to pause (keyboard Search key, a suggestion, Retry). */
    fun onSearchSubmit() = verseSearch.submit()

    fun onVersionSearchQueryChange(query: String) {
        _versionSearchQuery.value = query
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        repository.saveThemeMode(mode)
    }

    fun setVerseTextScale(scale: VerseTextScale) {
        _verseTextScale.value = scale
        repository.saveVerseTextScale(scale)
    }

    /** Persisting the flag also (de)schedules the reminder worker — see
     *  [BibleRepository.setNotificationEnabled]. */
    fun setNotificationEnabled(enabled: Boolean) {
        _notificationEnabled.value = enabled
        repository.setNotificationEnabled(enabled)
    }

    fun setNotificationTime(hour: Int, minute: Int) {
        _notificationTime.value = NotificationTime(hour, minute)
        repository.setNotificationTime(hour, minute)
    }

    fun loadAvailableVersions() {
        viewModelScope.launch {
            _isLoadingVersions.value = true
            _versionsError.value = null
            try {
                _availableVersions.value = repository.getAllVersions()
            } catch (e: Exception) {
                android.util.Log.e("BibleViewModel", "Failed to load available Bible versions", e)
                _versionsError.value = e.message
            } finally {
                _isLoadingVersions.value = false
            }
        }
    }

    /**
     * If already the active version, this is a no-op (no re-fetch, no re-switch) —
     * the caller still gets [onFinished](true), since from the UI's point of view
     * nothing needs to happen but the picker can still be dismissed.
     * If already downloaded, switches immediately.
     * If not, downloads first then switches.
     * [onFinished] fires with true if the version is now selected (either it
     * was already active/downloaded, or the download just succeeded), false on
     * failure — callers can use this to decide whether it's safe to navigate away.
     *
     * Guards against re-entrancy: the UI already disables the row while a
     * download is in flight, but this check is synchronous (set before the
     * coroutine is even launched) so a double-tap that beats recomposition
     * can't start a second overlapping download of the same DB connection.
     */
    fun selectVersion(versionId: String, onFinished: (success: Boolean) -> Unit = {}) {
        if (versionId == _selectedVersion.value.id) {
            onFinished(true)
            return
        }
        if (_downloadingVersionId.value != null) return
        _downloadingVersionId.value = versionId
        _downloadInfo.value = null

        viewModelScope.launch {
            if (repository.isVersionDownloaded(versionId)) {
                _downloadingVersionId.value = null
                switchToVersion(versionId)
                onFinished(true)
                return@launch
            }
            val success = downloadAndSwitch(versionId)
            onFinished(success)
        }
    }

    /**
     * Re-downloads an already-downloaded version from scratch — the only way to pick
     * up a newer [DownloadedVersionInfo.schemaVersion] (e.g. a version downloaded
     * before footnotes existed). No-ops while any other download is in flight.
     */
    fun redownloadVersion(versionId: String) {
        if (_downloadingVersionId.value != null) return
        _downloadingVersionId.value = versionId
        _downloadProgress.value = 0f
        _downloadError.value = null
        _downloadInfo.value = null

        viewModelScope.launch {
            val result = repository.redownloadVersion(
                translationId = versionId,
                onProgress = { _downloadProgress.value = it }
            )
            _downloadingVersionId.value = null

            result.fold(
                onSuccess = {
                    refreshDownloadedVersions()
                    // Reload so an already-open chapter picks up the newly-fetched footnotes/rich
                    // content/book names/chapter structure.
                    if (_selectedVersion.value.id == versionId) {
                        _bookNames.value = repository.getBookNames(versionId)
                        _chapterStructure.value = repository.getChapterStructure(versionId)
                        loadChapter(_currentBook.value, _currentChapter.value)
                    }
                },
                onFailure = { _downloadError.value = downloadErrorText(it) }
            )
        }
    }

    fun useLocalBible() {
        viewModelScope.launch { switchToVersion(DEFAULT_VERSION.id) }
    }

    /**
     * Downloads [versionId] without switching to it — the picker's per-row download
     * action, for grabbing a translation ahead of time without leaving whatever is
     * currently being read. No-ops while any other download is in flight, same
     * reentrancy guard as [selectVersion]/[redownloadVersion].
     */
    fun downloadVersion(versionId: String) {
        if (_downloadingVersionId.value != null) return
        _downloadInfo.value = null
        viewModelScope.launch { downloadAndSwitch(versionId, switchAfter = false) }
    }

    /**
     * Permanently removes [versionId]'s downloaded content from local storage (not just
     * the in-memory picker state). Refuses to delete the version currently being read —
     * the picker UI shouldn't even offer the action for the active version, but this is
     * a second guard in case a row goes stale after a switch. No-ops while a download is
     * in flight, same reentrancy guard as [selectVersion]/[redownloadVersion].
     */
    fun deleteVersion(versionId: String) {
        if (versionId == _selectedVersion.value.id) return
        if (_downloadingVersionId.value != null) return
        viewModelScope.launch {
            repository.deleteVersion(versionId)
            refreshDownloadedVersions()
        }
    }

    private suspend fun switchToVersion(versionId: String) {
        _selectedVersion.value = SelectedBibleVersion(id = versionId)
        repository.saveSelectedVersion(versionId)
        _bookNames.value = repository.getBookNames(versionId)
        _chapterStructure.value = repository.getChapterStructure(versionId)
        loadChapter(_currentBook.value, _currentChapter.value)
        verseSearch.onVersionChange()
        // Re-resolve (not re-fetch) so the card matches the newly selected translation
        // right away — the reference is already cached for today, so this is just a
        // local chapter lookup, no network call.
        loadVerseOfTheDay()
    }

    private fun refreshDownloadedVersions() {
        viewModelScope.launch {
            _downloadedVersions.value = repository.getDownloadedVersions().associateBy { it.id }
        }
    }

    private suspend fun downloadAndSwitch(versionId: String, switchAfter: Boolean = true): Boolean {
        _downloadingVersionId.value = versionId
        _downloadProgress.value = 0f
        _downloadError.value = null

        val result = repository.downloadVersion(
            translationId = versionId,
            onProgress = { _downloadProgress.value = it }
        )

        _downloadingVersionId.value = null

        return result.fold(
            onSuccess = { summary ->
                if (summary.skippedBookCount > 0) {
                    _downloadInfo.value = UiText.Res(
                        R.string.download_partial_summary,
                        UiText.Plural(R.plurals.download_verse_count, summary.downloadedVerseCount),
                        UiText.Plural(R.plurals.download_chapter_count, summary.downloadedChapterCount)
                    )
                }
                if (switchAfter) switchToVersion(versionId)
                refreshDownloadedVersions()
                true
            },
            onFailure = {
                _downloadError.value = downloadErrorText(it)
                false
            }
        )
    }

    /** The two failures a reader can act on get their own message; anything else (a server or
     *  parsing error) shows its technical detail, as before. */
    private fun downloadErrorText(error: Throwable): UiText = when (error) {
        is NoInternetException -> UiText.Res(R.string.download_error_offline)
        is NoContentException -> UiText.Res(R.string.download_error_no_content)
        else -> error.message?.let { UiText.Raw(it) } ?: UiText.Res(R.string.download_failed)
    }
}