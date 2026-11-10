package com.application.bibleapp.verseimage

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Must match the provider's authority in AndroidManifest.xml. */
private const val AUTHORITY_SUFFIX = ".fileprovider"

/** Under cacheDir; listed in res/xml/file_paths.xml, the only folder the provider shares. */
private const val CACHE_FOLDER = "verse_images"

/**
 * Hands a verse image (#254) to another app. The PNG is written to the app's cache, never to
 * shared storage, and the receiving app gets a content:// link with read access to that one
 * file. Each share gets a new file name, since some apps keep showing an old picture for a link
 * they've already seen; the folder is emptied first, so only the latest image is ever kept.
 */
object VerseImageSharing {

    suspend fun writeToCache(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, CACHE_FOLDER).apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val file = File(folder, "TreasuredWord_${System.currentTimeMillis()}.png")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    }

    fun shareIntent(uri: Uri): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData lets the share sheet show the picture (Android 10+) and carries the grant.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
