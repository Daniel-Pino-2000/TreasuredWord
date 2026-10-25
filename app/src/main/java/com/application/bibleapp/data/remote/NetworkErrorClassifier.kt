package com.application.bibleapp.data.remote

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException as KtorSocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.nio.channels.UnresolvedAddressException

/**
 * True for transient, retriable network failures (DNS resolution blips, connect/read
 * timeouts, connection resets) as opposed to permanent conditions (bad request params,
 * malformed response) that retrying won't fix.
 */
fun isRetriableNetworkError(e: Throwable): Boolean = when (e) {
    // Covers UnknownHostException, SocketException, java.net.SocketTimeoutException,
    // "connection reset by peer", etc.
    is IOException -> true
    // DNS resolution failure — notably does NOT extend IOException.
    is UnresolvedAddressException -> true
    is HttpRequestTimeoutException -> true
    is ConnectTimeoutException -> true
    is KtorSocketTimeoutException -> true
    else -> false
}

/**
 * True when the request reached the server but no answer came back in time: usually our
 * server still waking up on Render's free tier (#87), not a problem with the phone's
 * connection. A connect timeout doesn't count, since Render's edge accepts connections
 * straight away even while the server sleeps. Ktor's own socket timeout extends
 * [java.net.SocketTimeoutException] on the JVM, and its connect timeout doesn't.
 */
fun isServerResponseTimeout(e: Throwable): Boolean =
    e is HttpRequestTimeoutException || (e is java.net.SocketTimeoutException && e !is ConnectTimeoutException)
