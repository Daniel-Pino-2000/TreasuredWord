package com.application.bibleapp.utils

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * The Google account's profile picture on the You tab (#335). Google sign-in hands the app its
 * address; the phone downloads it from Google's image server once and keeps it in its own
 * cache, so it also shows offline. It never goes to our server.
 *
 * The cache has its own loader and folder, in no_backup/ (never backed up, and not cleared by
 * the system when storage runs low the way cache/ is), so signing out can empty it without
 * touching anything else. Coil 3 doesn't follow the image's Cache-Control (a day, on Google's
 * server) by default: a cached picture is used as is, with no network.
 */
object ProfilePicture {

    @Volatile
    private var loader: ImageLoader? = null

    fun imageLoader(context: Context): ImageLoader =
        loader ?: synchronized(this) {
            loader ?: build(context.applicationContext).also { loader = it }
        }

    /** Signing out, deleting the account or signing in to another one: the picture leaves the phone. */
    suspend fun forget(context: Context) = withContext(Dispatchers.IO) {
        val imageLoader = imageLoader(context)
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }

    private fun build(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.noBackupFilesDir, "profile_picture").toOkioPath())
                    // One small picture; room for a couple of sizes after a density change.
                    .maxSizeBytes(2L * 1024 * 1024)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder().maxSizeBytes(1024 * 1024).build()
            }
            .build()
}

/** Google's picture addresses end in a size, like `=s96-c` (96 px, cropped square). */
private val GOOGLE_SIZE_SUFFIX = Regex("=s\\d+-c$")

/**
 * The address to load for an avatar [sizePx] pixels wide, or null to show the initial instead:
 * no picture, or one that isn't https (only Google's https addresses are expected). A Google
 * address's size is set to [sizePx], so the picture is sharp without downloading a large one.
 */
fun profilePictureRequestUrl(pictureUrl: String?, sizePx: Int): String? {
    val url = pictureUrl?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) } ?: return null
    return if (GOOGLE_SIZE_SUFFIX.containsMatchIn(url) && sizePx > 0) {
        url.replace(GOOGLE_SIZE_SUFFIX, "=s$sizePx-c")
    } else {
        url
    }
}
