package com.application.bibleapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.bibleapp.data.remote.ServerWakeUp
import com.application.bibleapp.data.remote.isServerResponseTimeout
import com.application.bibleapp.data.repository.AuthError
import com.application.bibleapp.data.repository.AuthException
import com.application.bibleapp.data.repository.AuthRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import com.application.bibleapp.R
import com.application.bibleapp.utils.GoogleSignInResult
import com.application.bibleapp.utils.UiText
import com.application.bibleapp.worker.AccountContentSync

/** Where a sign-out is (#81) — see [AuthViewModel.signOut]. */
sealed interface SignOutState {
    data object Idle : SignOutState
    /** Uploading unsynced highlights and notes before they're removed from the device. */
    data object Uploading : SignOutState
    /** Some couldn't be uploaded (e.g. offline): signing out now would lose them, so ask first. */
    data object UnsyncedChanges : SignOutState
}

/** How the signed-in account can sign in, from /users/me (#112). Null fields: not known yet. */
data class SignInMethods(val hasPassword: Boolean?, val hasGoogle: Boolean?)

/**
 * Backs the login/register screens and the account section in Settings. [isLoggedIn] is
 * mirrored here (not read fresh from AuthRepository each time) so Compose can actually
 * recompose when it changes — AuthRepository's own property is a plain synchronous read of
 * TokenStore, which by itself doesn't notify anyone that the value changed.
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val accountContent: AccountContentSync
) : ViewModel() {

    private val _isLoggedIn = MutableStateFlow(authRepository.isLoggedIn)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** The [isLoading] action is Google sign-in, so its button shows the spinner (#112). */
    private val _isGoogleLoading = MutableStateFlow(false)
    val isGoogleLoading: StateFlow<Boolean> = _isGoogleLoading.asStateFlow()

    private val _signInMethods = MutableStateFlow(SignInMethods(hasPassword = null, hasGoogle = null))
    val signInMethods: StateFlow<SignInMethods> = _signInMethods.asStateFlow()

    private val _errorMessage = MutableStateFlow<UiText?>(null)
    val errorMessage: StateFlow<UiText?> = _errorMessage.asStateFlow()

    /** The signed-in account's email, starting from the copy saved on the device so it shows
     *  on launch even with the server asleep or the phone offline (#109). Null when signed
     *  out, or for an older session that /users/me hasn't answered for yet. */
    private val _email = MutableStateFlow(authRepository.savedEmail)
    val email: StateFlow<String?> = _email.asStateFlow()

    private val _signedIn = Channel<String?>(Channel.BUFFERED)
    /** One event per successful sign-in or new account, with the account's email. The
     *  after-sign-in work and leaving the sign-in screen key off this and [isLoggedIn], not a
     *  callback from the screen: a waking server can take ~50 s, and by then the person may be
     *  on another screen, or the screen recreated by a rotation (#134). Buffered, so an event
     *  sent while nothing is collecting (mid-rotation) is still delivered. */
    val signedIn: Flow<String?> = _signedIn.receiveAsFlow()

    private val serverWakeUp = ServerWakeUp(ping = authRepository::pingServer)

    init {
        if (_isLoggedIn.value) refreshCurrentUser()
    }

    /** The sign-in and create-account screens call this when they open, so a sleeping server
     *  starts up while the form is filled in (#87). */
    fun wakeServer() {
        viewModelScope.launch { serverWakeUp.wakeUp() }
    }

    fun register(email: String, password: String) {
        runAuthAction { authRepository.register(email, password) }
    }

    fun login(email: String, password: String) {
        runAuthAction { authRepository.login(email, password) }
    }

    /** What the sign-in screens' "Continue with Google" got from Credential Manager (#112). */
    fun onGoogleResult(result: GoogleSignInResult) {
        when (result) {
            is GoogleSignInResult.Success -> runAuthAction(google = true) {
                authRepository.signInWithGoogle(result.idToken, result.email)
            }
            GoogleSignInResult.Cancelled -> Unit
            is GoogleSignInResult.Failed -> _errorMessage.value = result.message
        }
    }

    private val _signOutState = MutableStateFlow<SignOutState>(SignOutState.Idle)
    val signOutState: StateFlow<SignOutState> = _signOutState.asStateFlow()

    /**
     * Signs out and removes the account's highlights and notes from this device (#81) — they're
     * safe on the server and come back on the next sign-in. Anything not uploaded yet is sent
     * first; if some still can't be, this stops at [SignOutState.UnsyncedChanges] unless
     * [discardUnsynced] (the person chose "Sign out anyway"). [onSignedOut] lets the reader and
     * Library drop what they were showing.
     */
    fun signOut(discardUnsynced: Boolean = false, onSignedOut: () -> Unit) {
        if (_signOutState.value == SignOutState.Uploading) return
        viewModelScope.launch {
            if (!discardUnsynced) {
                _signOutState.value = SignOutState.Uploading
                if (accountContent.uploadPendingChanges() > 0) {
                    _signOutState.value = SignOutState.UnsyncedChanges
                    return@launch
                }
            }
            val refreshToken = accountContent.signOut { authRepository.endLocalSession() }
            _isLoggedIn.value = false
            _email.value = null
            _signInMethods.value = SignInMethods(hasPassword = null, hasGoogle = null)
            _signOutState.value = SignOutState.Idle
            onSignedOut()
            authRepository.forgetGoogleSignIn()
            authRepository.revokeOnServer(refreshToken)
        }
    }

    /** "Cancel" on the unsynced-changes warning: stay signed in. */
    fun cancelSignOut() {
        _signOutState.value = SignOutState.Idle
    }

    /** [onResult] receives null on success, or a message to show on failure (e.g. the
     *  password was wrong) — the caller (Settings' delete-account dialog) decides how to
     *  present that rather than this ViewModel owning dialog-specific error state. Confirmed
     *  with the [password] or, for a Google account, a fresh [googleIdToken] (#112).
     *
     *  The account's highlights and notes leave the device too, exactly as on sign-out (#141):
     *  kept here, the next sign-in would upload them into the new account, and deleting the
     *  account would look like it did nothing. */
    fun deleteAccount(
        password: String? = null,
        googleIdToken: String? = null,
        onResult: (errorMessage: UiText?) -> Unit
    ) {
        viewModelScope.launch {
            authRepository.deleteAccount(password, googleIdToken).fold(
                onSuccess = {
                    // The server already deleted its refresh tokens, so there's nothing to revoke.
                    accountContent.signOut { authRepository.endLocalSession() }
                    authRepository.forgetGoogleSignIn()
                    _isLoggedIn.value = false
                    _email.value = null
                    _signInMethods.value = SignInMethods(hasPassword = null, hasGoogle = null)
                    onResult(null)
                },
                onFailure = {
                    val wrongGoogleAccount = googleIdToken != null &&
                        (it as? AuthException)?.error == AuthError.InvalidCredentials
                    onResult(
                        if (wrongGoogleAccount) UiText.Res(R.string.account_delete_google_wrong_account)
                        else errorText(it)
                    )
                }
            )
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun refreshCurrentUser() {
        viewModelScope.launch {
            authRepository.getCurrentUser().fold(
                // Read back from the store rather than the reply, which only saved it if the
                // person is still signed in (a sign-out may have happened meanwhile).
                onSuccess = { user ->
                    _email.value = authRepository.savedEmail
                    _signInMethods.value = SignInMethods(user.hasPassword, user.hasGoogle)
                },
                onFailure = {
                    // A failed fetch here often means the access token expired and the
                    // automatic refresh attempt also failed (HttpClientProvider's Auth plugin
                    // already cleared TokenStore in that case — see its refreshTokens doc).
                    // Re-reading isLoggedIn reflects that in the UI instead of leaving it
                    // showing "Signed in" for a session that's actually already gone.
                    _isLoggedIn.value = authRepository.isLoggedIn
                    _email.value = authRepository.savedEmail
                }
            )
        }
    }

    private fun runAuthAction(google: Boolean = false, action: suspend () -> Result<Unit>) {
        _errorMessage.value = null
        _isLoading.value = true
        _isGoogleLoading.value = google
        viewModelScope.launch {
            val result = action()
            // Before the session's first sync (a [signedIn] collector triggers it), so a different account's
            // content left on the device is cleared first instead of mixed in (#81).
            if (result.isSuccess) accountContent.onSignedIn(authRepository.currentUserId)
            _isLoading.value = false
            _isGoogleLoading.value = false
            result.fold(
                onSuccess = {
                    _isLoggedIn.value = true
                    _email.value = authRepository.savedEmail
                    refreshCurrentUser()
                    _signedIn.send(authRepository.savedEmail)
                },
                onFailure = { _errorMessage.value = errorText(it) }
            )
        }
    }

    private fun errorText(throwable: Throwable): UiText = when {
        throwable is AuthException -> throwable.error.displayMessage
        // The server got the request but didn't answer in time (usually still waking up), so
        // "check your connection" would send people to fix the wrong thing (#87).
        isServerResponseTimeout(throwable) -> UiText.Res(R.string.auth_error_server_slow)
        // Covers Ktor's ConnectTimeoutException/SocketTimeoutException and plain
        // UnknownHostException — anything where the request never actually reached the
        // server, as opposed to the server responding with an error.
        throwable is java.io.IOException -> UiText.Res(R.string.auth_error_unreachable)
        else -> throwable.message?.let { UiText.Raw(it) } ?: UiText.Res(R.string.error_generic)
    }
}
