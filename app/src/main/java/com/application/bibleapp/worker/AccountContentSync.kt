package com.application.bibleapp.worker

import android.content.Context
import android.util.Log
import com.application.bibleapp.data.remote.HelloAoBibleDataSource
import com.application.bibleapp.data.remote.OurMannaBibleDataSource
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.data.repository.HighlightRepository
import com.application.bibleapp.data.repository.NoteRepository
import com.application.bibleapp.data.repository.ReadingProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/**
 * What signing in and out does to this device's highlights and notes (#81). Every step holds
 * [SyncWorker.syncMutex], so a sync pass can't run halfway through one — e.g. pull the old
 * account's highlights back in right after sign-out removed them.
 */
class AccountContentSync(context: Context) {

    private val bibleRepository = BibleRepository(
        context = context.applicationContext,
        remote = HelloAoBibleDataSource(),
        daily = OurMannaBibleDataSource()
    )

    /** Uploads whatever hasn't synced yet, as far as the server can be reached, and returns how
     *  many highlights and notes are still only on this device. */
    suspend fun uploadPendingChanges(): Int = SyncWorker.syncMutex.withLock {
        if (bibleRepository.countPendingChanges() > 0) {
            val engine = SyncEngine(bibleRepository, HighlightRepository(), NoteRepository(), ReadingProgressRepository())
            try {
                engine.pushPending()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Upload before sign-out failed: ${e.message}")
            }
        }
        bibleRepository.countPendingChanges()
    }

    /** Runs [endSession] (clearing the tokens), then removes the account's content. */
    suspend fun <T> signOut(endSession: () -> T): T = SyncWorker.syncMutex.withLock {
        val result = endSession()
        bibleRepository.removeAccountContent()
        result
    }

    /** See [BibleRepository.adoptContentOwner]. Must finish before the first sync of the session. */
    suspend fun onSignedIn(userId: String?) = SyncWorker.syncMutex.withLock {
        bibleRepository.adoptContentOwner(userId)
    }

    private companion object {
        const val TAG = "AccountContentSync"
    }
}
