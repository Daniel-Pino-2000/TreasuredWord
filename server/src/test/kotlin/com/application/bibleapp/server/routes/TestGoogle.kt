package com.application.bibleapp.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.RSAKeyProvider
import com.sun.net.httpserver.HttpServer
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

/**
 * A stand-in for Google in the Google sign-in tests (#112): its own RSA key signs ID tokens the
 * way Google's would, and [certsUrl] serves the matching public key in Google's JWKS format, so
 * the server's real verifier checks them end to end without reaching Google.
 */
object TestGoogle {
    const val CLIENT_ID = "test-web-client.apps.googleusercontent.com"
    const val KEY_ID = "test-key"
    private const val ISSUER = "https://accounts.google.com"

    val keyPair: KeyPair = newKeyPair()

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()

    /** A fresh Google account ID, so tests never collide in the shared dev database. */
    fun newSubject(): String = "google-test-${UUID.randomUUID()}"

    fun newEmail(): String = "googletest+${UUID.randomUUID()}@example.com"

    fun idToken(
        subject: String,
        email: String,
        emailVerified: Boolean = true,
        audience: String = CLIENT_ID,
        issuer: String = ISSUER,
        expiresAt: Instant = Instant.now().plusSeconds(3600),
        signingKey: KeyPair = keyPair
    ): String = JWT.create()
        .withKeyId(KEY_ID)
        .withIssuer(issuer)
        .withAudience(audience)
        .withSubject(subject)
        .withClaim("email", email)
        .withClaim("email_verified", emailVerified)
        .withIssuedAt(Date.from(expiresAt.minusSeconds(3600)))
        .withExpiresAt(Date.from(expiresAt))
        .sign(Algorithm.RSA256(signingKey.public as RSAPublicKey, signingKey.private as RSAPrivateKey))

    /** Hands out [keyPair]'s public key for [KEY_ID], like the server's lookup in Google's keys. */
    val keyProvider = object : RSAKeyProvider {
        override fun getPublicKeyById(keyId: String?): RSAPublicKey? =
            if (keyId == KEY_ID) keyPair.public as RSAPublicKey else null

        override fun getPrivateKey(): RSAPrivateKey? = null
        override fun getPrivateKeyId(): String? = null
    }

    /** A local HTTP endpoint serving [keyPair]'s public key as Google's certs URL does. */
    val certsUrl: String by lazy {
        val key = keyPair.public as RSAPublicKey
        val jwks = """{"keys":[{"kty":"RSA","alg":"RS256","use":"sig","kid":"$KEY_ID",""" +
            """"n":"${base64Url(key.modulus)}","e":"${base64Url(key.publicExponent)}"}]}"""
        val body = jwks.toByteArray()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/certs") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        // A daemon thread, so the server never keeps the test JVM alive.
        server.executor = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        server.start()
        "http://127.0.0.1:${server.address.port}/certs"
    }

    /** application.conf with Google sign-in pointed at this stand-in. */
    fun config(): ApplicationConfig = ApplicationConfig("application.conf").mergeWith(
        MapApplicationConfig("google.clientId" to CLIENT_ID, "google.certsUrl" to certsUrl)
    )

    /** JWKS numbers are unsigned big-endian bytes, base64url without padding. */
    private fun base64Url(n: BigInteger): String {
        val bytes = n.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
