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
import com.application.bibleapp.data.repository.ReadingProgressRepository
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.VerseTextScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

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

    // Every active highlight/note across all chapters — the Library screen's data, loaded on
    // demand (not kept live like _highlightsInChapter/_notesInChapter, since it's only ever
    // shown on its own screen) via [loadLibrary].
    private val _libraryHighlights = MutableStateFlow<List<Highlight>>(emptyList())
    val libraryHighlights: StateFlow<List<Highlight>> = _libraryHighlights.asStateFlow()

    private val _libraryNotes = MutableStateFlow<List<Note>>(emptyList())
    val libraryNotes: StateFlow<List<Note>> = _libraryNotes.asStateFlow()

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

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<VerseUI>>(emptyList())
    val searchResults: StateFlow<List<VerseUI>> = _searchResults.asStateFlow()

    private val _availableVersions = MutableStateFlow<List<BibleTranslation>>(emptyList())
    val availableVersions: StateFlow<List<BibleTranslation>> = _availableVersions.asStateFlow()

    private val _isLoadingVersions = MutableStateFlow(false)
    val isLoadingVersions: StateFlow<Boolean> = _isLoadingVersions.asStateFlow()

    private val _versionsError = MutableStateFlow<String?>(null)
    val versionsError: StateFlow<String?> = _versionsError.asStateFlow()

    private val _versionSearchQuery = MutableStateFlow("")
    val versionSearchQuery: StateFlow<String> = _versionSearchQuery.asStateFlow()

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

    /** Re-reads the persisted "last synced" stamp — call when Settings opens, since a background
     *  periodic sync could have updated it since the ViewModel's initial value was read. */
    fun refreshSyncStatus() {
        _lastSyncCompletedAt.value = repository.loadLastSyncCompletedAt()
    }

    /** Drives a short-lived spinner for the manual "Sync now" button — polls for
     *  [BibleRepository.loadLastSyncCompletedAt] to change rather than wiring a full WorkManager
     *  WorkInfo observer just for this one button. The actual trigger
     *  ([com.application.bibleapp.worker.SyncScheduler.triggerImmediateSync]) is called by the
     *  composable, which has the Context it needs; this just watches for it to finish. */
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
                    _isSyncing.value = false
                    return@launch
                }
            }
            _isSyncing.value = false
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

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    // Informational (not an error): set when a download succeeded but some content
    // wasn't available for this version (e.g. an NT-only translation).
    private val _downloadInfo = MutableStateFlow<String?>(null)
    val downloadInfo: StateFlow<String?> = _downloadInfo.asStateFlow()

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
     *  already-highlighted verse opens its change-color/remove sheet; tapping a plain verse is a no-op. */
    fun onVerseTap(location: VerseLocationDto) {
        if (_selectedVerses.value.isNotEmpty()) {
            toggleVerseSelection(location)
            return
        }
        _highlightPopup.value = _highlightsInChapter.value.firstOrNull { location in it.verses }
    }

    private fun toggleVerseSelection(location: VerseLocationDto) {
        _selectedVerses.update { current ->
            if (location in current) current - location else current + location
        }
    }

    fun clearSelection() {
        _selectedVerses.value = emptySet()
    }

    /** Creates one highlight covering every currently selected verse, then clears the selection. */
    fun highlightSelection(colorArgb: Int) {
        val verses = _selectedVerses.value.toList()
        if (verses.isEmpty()) return
        val versionId = _selectedVersion.value.id
        _selectedVerses.value = emptySet()
        viewModelScope.launch {
            repository.createHighlight(versionId, verses, colorArgb)
            reloadHighlightsInChapter()
            maybeOfferSignIn()
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
        _noteEditor.value = NoteEditorState(existingNote = null, verses = verses, text = "")
    }

    /** Opens the editor on an existing note — reached from its inline glyph in the reading view. */
    fun openNoteEditorForNote(note: Note) {
        _noteEditor.value = NoteEditorState(existingNote = note, verses = note.verses, text = note.text)
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
        viewModelScope.launch {
            repository.deleteNote(existing.localId)
            reloadNotesInChapter()
        }
    }

    /** Loads every active highlight/note for the Library screen — call once when it opens. */
    fun loadLibrary() {
        viewModelScope.launch {
            _libraryHighlights.value = repository.getAllActiveHighlights()
            _libraryNotes.value = repository.getAllActiveNotes()
        }
    }

    /** Swipe-to-delete from the Library list. Also refreshes the in-chapter highlight state
     *  in case the deleted highlight belonged to whatever chapter is currently loaded in
     *  BibleView — cheap no-op reload otherwise, since the ViewModel (and its StateFlows) is
     *  shared across screens rather than recreated per navigation. */
    fun deleteLibraryHighlight(highlight: Highlight) {
        _libraryHighlights.update { it.filterNot { h -> h.localId == highlight.localId } }
        viewModelScope.launch {
            repository.deleteHighlight(highlight.localId)
            reloadHighlightsInChapter()
        }
    }

    fun deleteLibraryNote(note: Note) {
        _libraryNotes.update { it.filterNot { n -> n.localId == note.localId } }
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

    fun onSearchQueryChange(newQuery: String) {
        _searchQuery.value = newQuery
    }

    fun onSearchButtonClick() {
        val query = _searchQuery.value
        if (query.isNotBlank()) {
            viewModelScope.launch {
                _searchResults.value = repository.searchVerses(query, _selectedVersion.value.id)
            }
        }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
        _searchQuery.value = ""
    }

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
                onFailure = { _downloadError.value = it.message ?: "Download failed" }
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
                    _downloadInfo.value = "Downloaded ${summary.downloadedVerseCount} verses across " +
                        "${summary.downloadedChapterCount} chapters — some books aren't available in this translation."
                }
                if (switchAfter) switchToVersion(versionId)
                refreshDownloadedVersions()
                true
            },
            onFailure = {
                _downloadError.value = it.message ?: "Download failed"
                false
            }
        )
    }
}