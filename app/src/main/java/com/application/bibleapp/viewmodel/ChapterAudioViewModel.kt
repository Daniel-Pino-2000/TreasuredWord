package com.application.bibleapp.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.application.bibleapp.audio.ChapterAudioService
import com.application.bibleapp.audio.chapterMediaItem
import com.application.bibleapp.data.model.AudioNarrator
import com.application.bibleapp.data.model.ChapterAudio
import com.application.bibleapp.data.model.ChapterAudioItem
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the reader's player shows. [item] is null when nothing is loaded, and the player hides. */
data class ChapterAudioState(
    val item: ChapterAudioItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f
)

sealed interface ChapterAudioEvent {
    /** The recording couldn't be played (lost connection, a file gone from HelloAO). */
    data object PlaybackFailed : ChapterAudioEvent

    /** One chapter ended and the next began on its own, so the reader can follow. */
    data class Advanced(val from: ChapterAudioItem, val to: ChapterAudioItem) : ChapterAudioEvent
}

/**
 * The reader's side of chapter audio (#367): connects to [ChapterAudioService] and turns its
 * player into [state] for the mini player. Activity-scoped, like the other view models; the
 * service, not this, keeps playing once the app is gone.
 */
class ChapterAudioViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(ChapterAudioState(speed = savedSpeed()))
    val state: StateFlow<ChapterAudioState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ChapterAudioEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ChapterAudioEvent> = _events.asSharedFlow()

    private val controllerFuture: ListenableFuture<MediaController> = MediaController.Builder(
        application,
        SessionToken(application, ComponentName(application, ChapterAudioService::class.java))
    ).buildAsync()
    private var controller: MediaController? = null
    /** Runs once the controller connects: a Listen tap that came first. */
    private var pendingAction: ((MediaController) -> Unit)? = null
    private var positionJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
            val to = ChapterAudio.parseMediaId(mediaItem?.mediaId) ?: return
            val from = _state.value.item ?: return
            if (from != to) _events.tryEmit(ChapterAudioEvent.Advanced(from, to))
        }

        override fun onPlayerError(error: PlaybackException) {
            _events.tryEmit(ChapterAudioEvent.PlaybackFailed)
        }
    }

    init {
        controllerFuture.addListener({
            val connected = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(listener)
            publish(connected)
            pendingAction?.invoke(connected)
            pendingAction = null
        }, ContextCompat.getMainExecutor(application))
    }

    /** The narrator Listen starts with in [versionId]: the last one chosen, if they read it. */
    fun narratorFor(versionId: String): AudioNarrator? =
        ChapterAudio.narratorFor(versionId, AudioNarrator.byId(prefs.getString(KEY_NARRATOR, null)))

    /** Plays [bookId] [chapter] in [versionId], unless that chapter is already loaded (then it
     *  just resumes, so a second tap doesn't start it over). */
    fun listen(versionId: String, bookId: Int, chapter: Int) {
        val narrator = narratorFor(versionId) ?: return
        val item = ChapterAudioItem(versionId, bookId, chapter, narrator)
        withController { player ->
            val loaded = ChapterAudio.parseMediaId(player.currentMediaItem?.mediaId)
            if (loaded?.copy(narrator = narrator) == item) {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            } else {
                start(player, item)
            }
        }
    }

    fun togglePlayPause() = withController { player ->
        when {
            player.isPlaying -> player.pause()
            // After an error the player is idle and has to load the file again.
            player.playbackState == Player.STATE_IDLE -> {
                player.prepare()
                player.play()
            }
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0)
                player.play()
            }
            else -> player.play()
        }
    }

    fun seekBack() = withController { it.seekBack() }

    fun seekForward() = withController { it.seekForward() }

    fun setSpeed(speed: Float) {
        prefs.edit { putFloat(KEY_SPEED, speed) }
        withController { it.playbackParameters = PlaybackParameters(speed) }
    }

    /** Switches the chapter playing to [narrator]'s recording, from its start: the recordings are
     *  paced differently, so the same time would land somewhere else in the chapter. */
    fun setNarrator(narrator: AudioNarrator) {
        prefs.edit { putString(KEY_NARRATOR, narrator.id) }
        withController { player ->
            val loaded = ChapterAudio.parseMediaId(player.currentMediaItem?.mediaId) ?: return@withController
            if (loaded.narrator != narrator) start(player, loaded.copy(narrator = narrator), play = player.playWhenReady)
        }
    }

    /** Stops and unloads, which hides the player and the notification. */
    fun stop() = withController { player ->
        player.stop()
        player.clearMediaItems()
    }

    private fun start(player: Player, item: ChapterAudioItem, play: Boolean = true) {
        player.playbackParameters = PlaybackParameters(savedSpeed())
        player.setMediaItem(chapterMediaItem(item))
        player.prepare()
        player.playWhenReady = play
    }

    private fun withController(action: (MediaController) -> Unit) {
        val connected = controller
        if (connected != null) action(connected) else pendingAction = action
    }

    private fun publish(player: Player) {
        val item = ChapterAudio.parseMediaId(player.currentMediaItem?.mediaId)
        _state.value = ChapterAudioState(
            item = item,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
            speed = player.playbackParameters.speed
        )
        // The position has no event of its own: read it twice a second while playing.
        if (player.isPlaying && positionJob?.isActive != true) {
            positionJob = viewModelScope.launch {
                while (isActive) {
                    delay(POSITION_POLL_MS)
                    val current = controller ?: break
                    if (!current.isPlaying) break
                    _state.value = _state.value.copy(positionMs = current.currentPosition.coerceAtLeast(0))
                }
            }
        }
    }

    private fun savedSpeed(): Float =
        prefs.getFloat(KEY_SPEED, 1f).takeIf { it in ChapterAudio.speeds } ?: 1f

    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }

    private companion object {
        const val PREFS_NAME = "chapter_audio"
        const val KEY_NARRATOR = "narrator"
        const val KEY_SPEED = "speed"
        const val POSITION_POLL_MS = 500L
    }
}
