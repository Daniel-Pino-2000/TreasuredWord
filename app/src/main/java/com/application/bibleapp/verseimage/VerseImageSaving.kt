package com.application.bibleapp.verseimage

import android.Manifest
import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The folder in Pictures that saved verse images go into (#255). A name, so never translated. */
const val VERSE_IMAGE_FOLDER = "TreasuredWord"

/**
 * A saved image's file name, e.g. `TreasuredWord_John_3_16_20261109_153000.png`: the reference
 * and the time, so a gallery lists them in order and two saves never collide. Letters and digits
 * of any script are kept ("요한복음_3_16" is a fine name); everything else, including the
 * characters file systems reject, becomes "_". The time is the phone's local time; SimpleDateFormat
 * rather than java.time, which needs API 26 (minSdk is 24).
 */
fun verseImageFileName(reference: String, time: Date): String {
    val ref = reference
        .map { if (it.isLetterOrDigit()) it else '_' }
        .joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(60)
        .trimEnd('_')
    // Locale.US so the digits are always ASCII, whatever the app language.
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(time)
    return if (ref.isEmpty()) "${VERSE_IMAGE_FOLDER}_$stamp.png" else "${VERSE_IMAGE_FOLDER}_${ref}_$stamp.png"
}

/** Where a verse image goes when it's set as wallpaper. */
enum class WallpaperTarget { HOME, LOCK, BOTH }

/**
 * Saves verse images to the phone's photos and sets them as wallpaper (#255). Everything stays on
 * the phone. Run the suspend functions from a coroutine; they move to the IO dispatcher.
 */
object VerseImageSaving {

    /** Android 9 and older need the storage permission to write into Pictures (#255); 10 and
     *  newer save through MediaStore without one. */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    /** Writes [bitmap] as a PNG into Pictures/TreasuredWord. Throws if it can't. */
    suspend fun saveToPictures(context: Context, bitmap: Bitmap, fileName: String) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$VERSE_IMAGE_FOLDER")
                // Hidden from the gallery until it's fully written.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused the image")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG encoding failed")
                } ?: throw IOException("No output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), VERSE_IMAGE_FOLDER)
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Couldn't create $folder")
            val file = File(folder, fileName)
            file.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG encoding failed")
            }
            // Tells the gallery the new file is there.
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
        }
    }

    /** Whether this phone lets apps change the wallpaper (not, e.g., in some work profiles). */
    fun canSetWallpaper(context: Context): Boolean {
        val manager = WallpaperManager.getInstance(context)
        return manager.isWallpaperSupported && manager.isSetWallpaperAllowed
    }

    /** The screen's full size in pixels, portrait way up, so a wallpaper is drawn to fit it. */
    fun screenSize(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
        return minOf(w, h) to maxOf(w, h)
    }

    suspend fun setWallpaper(context: Context, bitmap: Bitmap, target: WallpaperTarget) = withContext(Dispatchers.IO) {
        val which = when (target) {
            WallpaperTarget.HOME -> WallpaperManager.FLAG_SYSTEM
            WallpaperTarget.LOCK -> WallpaperManager.FLAG_LOCK
            WallpaperTarget.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, which)
    }
}
