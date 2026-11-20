package com.application.bibleapp.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.application.bibleapp.R
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.ChapterAudio
import com.application.bibleapp.data.model.ChapterAudioItem
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** How far the back and forward buttons skip. */
const val CHAPTER_AUDIO_SKIP_MS = 15_000L

/**
 * Plays a chapter's recording (#367), and keeps playing with the screen off or the app in the
 * background. Media3's session gives the notification, lock-screen and headphone controls for
 * free; the reader talks to it through [ChapterAudioViewModel][com.application.bibleapp.viewmodel.ChapterAudioViewModel].
 *
 * The queue always holds the chapter after the one playing, so the next one starts on its own
 * and the notification's Next button works. Streaming only: nothing is saved on the phone.
 */
@OptIn(UnstableApi::class)
class ChapterAudioService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // The app's own status-bar icon rather than Media3's generic play button.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification_daily_verse)
            }
        )
        val player = ExoPlayer.Builder(this)
            // Speech, so another app's sound pauses it (audio focus) rather than mixing in, and
            // unplugging headphones pauses it instead of playing out loud.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            // Keeps the CPU and Wi-Fi awake while it streams with the screen off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(CHAPTER_AUDIO_SKIP_MS)
            .setSeekForwardIncrementMs(CHAPTER_AUDIO_SKIP_MS)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = queueNextChapter(player)
        })

        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {
                // A controller's items arrive without their address (Media3 strips it between
                // processes), so each is rebuilt here from its id, which also refuses any that
                // isn't one of our chapters.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.mapNotNull { ChapterAudio.parseMediaId(it.mediaId)?.let(::chapterMediaItem) }.toMutableList()
                )
            })
            .apply { openApp?.let(::setSessionActivity) }
            .build()
    }

    /** Adds the chapter after the current one when nothing follows it yet. */
    private fun queueNextChapter(player: Player) {
        if (player.currentMediaItemIndex != player.mediaItemCount - 1) return
        val current = ChapterAudio.parseMediaId(player.currentMediaItem?.mediaId) ?: return
        ChapterAudio.next(current)?.let { player.addMediaItem(chapterMediaItem(it)) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** Swiping the app away stops the service unless it's playing, as music apps do. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

/**
 * [item] as the player's media item. The notification shows the book's English name: every
 * version with audio is English today (BSB); a later one in another language would need its
 * own book names here.
 */
fun chapterMediaItem(item: ChapterAudioItem): MediaItem {
    val bookName = BibleBooks.getBookById(item.bookId)?.name.orEmpty()
    return MediaItem.Builder()
        .setMediaId(item.mediaId)
        .apply { ChapterAudio.url(item)?.let { setUri(it.toUri()) } }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("$bookName ${item.chapter}")
                .setArtist(item.narrator.displayName)
                .setAlbumTitle(item.versionId)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                .build()
        )
        .build()
}
