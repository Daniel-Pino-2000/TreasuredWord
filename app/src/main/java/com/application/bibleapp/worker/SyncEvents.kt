package com.application.bibleapp.worker

import com.application.bibleapp.data.repository.ReadingPosition
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What [SyncWorker] tells the open screens (#79). The worker writes straight to the database, and
 * the screens loaded their data before it ran, so without this they kept showing what was there
 * before the sync (e.g. an empty Home right after signing in on a fresh install).
 *
 * In-process only: WorkManager runs the worker in the app's own process, and when no screen is
 * open there's nothing to refresh — the next launch reads the database anyway.
 */
object SyncEvents {
    private val _isRunning = MutableStateFlow(false)
    /** True while a signed-in sync pass is running. */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _contentVersion = MutableStateFlow(0)
    /** Bumped after a pass that changed this device's highlights or notes — only then, so a
     *  screen where nothing changed doesn't reload and flicker. */
    val contentVersion: StateFlow<Int> = _contentVersion.asStateFlow()

    private val _passesFinished = MutableStateFlow(0)
    /** Bumped after every pass, successful or not, so the sync status line can re-read itself. */
    val passesFinished: StateFlow<Int> = _passesFinished.asStateFlow()

    private val _restoredReadingPosition = MutableSharedFlow<ReadingPosition>(extraBufferCapacity = 1)
    /** A position the sync brought in for a device that hadn't been read on yet (#82) — the
     *  reader can jump there, since there's nothing of the person's own to jump away from. */
    val restoredReadingPosition: SharedFlow<ReadingPosition> = _restoredReadingPosition.asSharedFlow()

    internal fun passStarted() {
        _isRunning.value = true
    }

    internal fun passFinished(outcome: SyncEngine.Outcome?) {
        if (outcome?.contentChanged == true) _contentVersion.update { it + 1 }
        outcome?.restoredReadingPosition?.let { _restoredReadingPosition.tryEmit(it) }
        _isRunning.value = false
        _passesFinished.update { it + 1 }
    }
}
