package com.application.bibleapp.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Persists the backend session (access token, refresh token, user id, email) in an encrypted
 * SharedPreferences file rather than a plain one. These are real bearer credentials —
 * equivalent to a password for as long as they're valid — so they get the same protection a
 * password would, not the same treatment as an ordinary app preference.
 *
 * The file's keyset is encrypted with an Android Keystore key that never leaves the device, so
 * the file is unreadable anywhere else. It's excluded from backup and device transfer
 * (res/xml/data_extraction_rules.xml, backup_rules.xml), but if a copy still turns up without
 * its key (an older build's backup, a manual restore, a wiped Keystore), opening it throws.
 * Rather than crash on every launch, the store is wiped and recreated: the user is signed out
 * and signs in again, which is all those tokens were worth without their key anyway.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences = openOrResetOnce(
        open = { open(context) },
        reset = { error ->
            Log.w(TAG, "Couldn't open $PREFS_NAME, resetting it (user is signed out)", error)
            reset(context)
        }
    )

    val userId: String? get() = prefs.getString(KEY_USER_ID, null)
    val accessToken: String? get() = prefs.getString(KEY_ACCESS_TOKEN, null)
    val refreshToken: String? get() = prefs.getString(KEY_REFRESH_TOKEN, null)

    /** The account's email, kept so the You tab can show it on launch without waiting for
     *  /users/me — which can take 30-50 s while the server wakes up, or never come offline
     *  (#109). Null for sessions saved before it was stored, until /users/me fills it in. */
    val email: String? get() = prefs.getString(KEY_EMAIL, null)

    /** True once a session has been saved and hasn't been cleared - doesn't verify the
     *  tokens are still valid server-side, just that we have something to try sending. */
    val isLoggedIn: Boolean get() = accessToken != null && refreshToken != null

    fun saveSession(userId: String, email: String, accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_EMAIL, email)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    /** Replaces the saved email with the one /users/me returned, the account's source of truth. */
    fun updateEmail(email: String) {
        prefs.edit().putString(KEY_EMAIL, email).apply()
    }

    /** Called after a successful /auth/refresh - the backend rotates the refresh token on
     *  every use (the old one stops working), so the new pair must replace the old one here
     *  too, not just be handed back to whoever asked for the refresh. */
    fun updateTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val TAG = "TokenStore"
        const val PREFS_NAME = "auth_tokens"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_EMAIL = "email"

        fun open(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        /** Deletes the prefs file and the master key; [open] then creates both fresh. Nothing
         *  else uses that key, so dropping it only loses what was already unreadable. */
        fun reset(context: Context) {
            context.deleteSharedPreferences(PREFS_NAME)
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            }.onFailure { Log.w(TAG, "Couldn't delete the master key", it) }
        }
    }
}

/**
 * Runs [open]; if it throws, runs [reset] with that error and tries [open] once more. A second
 * failure is thrown, since that means the Keystore itself is broken, not just this file.
 * Kept free of Android types so the recovery path can be unit-tested.
 */
internal fun <T> openOrResetOnce(open: () -> T, reset: (Exception) -> Unit): T =
    try {
        open()
    } catch (e: Exception) {
        reset(e)
        open()
    }
