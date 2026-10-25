package com.application.bibleapp.data.remote

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

class NetworkErrorClassifierTest {

    @Test
    fun `IOException family is retriable`() {
        assertTrue(isRetriableNetworkError(UnknownHostException()))
        assertTrue(isRetriableNetworkError(SocketTimeoutException()))
        assertTrue(isRetriableNetworkError(ConnectException()))
        assertTrue(isRetriableNetworkError(IOException("connection reset")))
    }

    @Test
    fun `DNS resolution failure is retriable even though it does not extend IOException`() {
        // This is the exact exception from the field report: UnresolvedAddressException
        // extends IllegalArgumentException, not IOException — it needs its own branch.
        assertTrue(isRetriableNetworkError(UnresolvedAddressException()))
    }

    @Test
    fun `validation and parsing errors are not retried`() {
        assertFalse(isRetriableNetworkError(IllegalArgumentException("bad book slug")))
        assertFalse(isRetriableNetworkError(SerializationException("malformed json")))
    }

    @Test
    fun `waiting too long for the server's answer is a server timeout`() {
        assertTrue(isServerResponseTimeout(HttpRequestTimeoutException("https://example.test/health", 90_000)))
        assertTrue(isServerResponseTimeout(io.ktor.client.network.sockets.SocketTimeoutException("read timed out")))
    }

    @Test
    fun `no connection is not a server timeout`() {
        // Render accepts connections straight away even while asleep, so these mean the phone
        // couldn't reach it at all: "check your connection" is the right message (#87).
        assertFalse(isServerResponseTimeout(ConnectTimeoutException("connect timed out")))
        assertFalse(isServerResponseTimeout(UnknownHostException()))
        assertFalse(isServerResponseTimeout(ConnectException()))
    }
}
