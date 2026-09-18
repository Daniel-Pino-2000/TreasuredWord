package com.application.bibleapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.application.bibleapp.data.remote.UserResponseDto
import com.application.bibleapp.data.repository.AuthException
import com.application.bibleapp.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the login/register screens and the account section in Settings. [isLoggedIn] is
 * mirrored here (not read fresh from AuthRepository each time) so Compose can actually
 * recompose when it changes — AuthRepository's own property is a plain synchronous read of
 * TokenStore, which by itself doesn't notify anyone that the value changed.
 */
class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _isLoggedIn = MutableStateFlow(authRepository.isLoggedIn)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _currentUser = MutableStateFlow<UserResponseDto?>(null)
    val currentUser: StateFlow<UserResponseDto?> = _currentUser.asStateFlow()

    init {
        if (_isLoggedIn.value) refreshCurrentUser()
    }

    fun register(email: String, password: String, onSuccess: () -> Unit) {
        runAuthAction(onSuccess) { authRepository.register(email, password) }
    }

    fun login(email: String, password: String, onSuccess: () -> Unit) {
        runAuthAction(onSuccess) { authRepository.login(email, password) }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _isLoggedIn.value = false
            _currentUser.value = null
        }
    }

    /** [onResult] receives null on success, or a message to show on failure (e.g. the
     *  password was wrong) — the caller (Settings' delete-account dialog) decides how to
     *  present that rather than this ViewModel owning dialog-specific error state. */
    fun deleteAccount(password: String, onResult: (errorMessage: String?) -> Unit) {
        viewModelScope.launch {
            authRepository.deleteAccount(password).fold(
                onSuccess = {
                    _isLoggedIn.value = false
                    _currentUser.value = null
                    onResult(null)
                },
                onFailure = { onResult(errorText(it)) }
            )
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun refreshCurrentUser() {
        viewModelScope.launch {
            authRepository.getCurrentUser().fold(
                onSuccess = { _currentUser.value = it },
                onFailure = {
                    // A failed fetch here often means the access token expired and the
                    // automatic refresh attempt also failed (HttpClientProvider's Auth plugin
                    // already cleared TokenStore in that case — see its refreshTokens doc).
                    // Re-reading isLoggedIn reflects that in the UI instead of leaving it
                    // showing "Signed in" for a session that's actually already gone.
                    _isLoggedIn.value = authRepository.isLoggedIn
                }
            )
        }
    }

    private fun runAuthAction(onSuccess: () -> Unit, action: suspend () -> Result<Unit>) {
        _errorMessage.value = null
        _isLoading.value = true
        viewModelScope.launch {
            val result = action()
            _isLoading.value = false
            result.fold(
                onSuccess = {
                    _isLoggedIn.value = true
                    refreshCurrentUser()
                    onSuccess()
                },
                onFailure = { _errorMessage.value = errorText(it) }
            )
        }
    }

    private fun errorText(throwable: Throwable): String = when {
        throwable is AuthException -> throwable.error.displayMessage
        // Covers Ktor's ConnectTimeoutException/SocketTimeoutException and plain
        // UnknownHostException — anything where the request never actually reached the
        // server, as opposed to the server responding with an error.
        throwable is java.io.IOException -> "Couldn't reach the server. Check your connection and try again."
        else -> throwable.message ?: "Something went wrong"
    }
}
