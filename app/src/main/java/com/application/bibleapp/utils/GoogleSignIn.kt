package com.application.bibleapp.utils

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.application.bibleapp.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** What asking Android for a Google sign-in gave back (#112). */
sealed interface GoogleSignInResult {
    /** [idToken] goes to our server, which checks it; [email] is shown until /users/me answers. */
    data class Success(val idToken: String, val email: String) : GoogleSignInResult
    /** The person closed Google's sheet: not an error, so nothing is shown. */
    data object Cancelled : GoogleSignInResult
    data class Failed(val message: UiText) : GoogleSignInResult
}

/**
 * "Continue with Google" through Android's Credential Manager (#112), the way Google currently
 * recommends. It shows Google's own account picker and returns an ID token for our **web** client
 * ID (res/values/google_sign_in.xml), which is the audience our server checks.
 */
object GoogleSignIn {

    /** False until the web client ID is filled in: the buttons stay hidden rather than fail. */
    fun isConfigured(context: Context): Boolean =
        context.getString(R.string.google_web_client_id).isNotBlank()

    /** [activityContext] must be an Activity: Google's sheet is shown over it. */
    suspend fun requestIdToken(activityContext: Context): GoogleSignInResult {
        val option = GetSignInWithGoogleOption.Builder(
            activityContext.getString(R.string.google_web_client_id)
        ).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(activityContext)
                .getCredential(activityContext, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                GoogleSignInResult.Success(google.idToken, google.id)
            } else {
                GoogleSignInResult.Failed(UiText.Res(R.string.auth_error_google_failed))
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleSignInResult.Failed(UiText.Res(R.string.auth_error_google_no_account))
        } catch (e: GetCredentialException) {
            // Also offline, or the app's signing key isn't registered with Google: the log says
            // which, the person just needs to know to use email instead.
            Log.w(TAG, "Google sign-in failed: ${e.type}", e)
            GoogleSignInResult.Failed(UiText.Res(R.string.auth_error_google_failed))
        } catch (e: GoogleIdTokenParsingException) {
            Log.w(TAG, "Google sign-in returned an unreadable token", e)
            GoogleSignInResult.Failed(UiText.Res(R.string.auth_error_google_failed))
        }
    }

    /**
     * After signing out or deleting the account (#141), Google recommends telling Credential
     * Manager, so the next sign-in starts fresh rather than from the account that just left.
     * Best effort: the person is already signed out of TreasuredWord either way.
     */
    suspend fun clearCredentialState(context: Context) {
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: ClearCredentialException) {
            Log.w(TAG, "Couldn't clear the Google sign-in state: ${e.type}", e)
        }
    }

    private const val TAG = "GoogleSignIn"
}
