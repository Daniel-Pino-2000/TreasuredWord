package com.application.bibleapp.server.auth

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwk.NetworkException
import com.auth0.jwk.JwkException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.RSAKeyProvider
import io.ktor.server.config.ApplicationConfig
import java.net.URI
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/** Who a verified Google ID token says is signing in (#112). */
data class GoogleIdentity(
    /** Google's permanent ID for the account ("sub"). Unlike the email, it never changes. */
    val subject: String,
    val email: String
)

/** Google's signing keys couldn't be fetched, so no token can be checked right now. */
class GoogleKeysUnavailableException(cause: Throwable) : RuntimeException(cause)

/**
 * Checks a Google ID token from the app's "Continue with Google" button (#112): signed with one
 * of Google's published keys, issued by Google, meant for our web client ID (the audience), not
 * expired, and for an email Google has verified. Anyone can send us a token, so every one of
 * these has to hold before the email in it is trusted.
 *
 * [keyProvider] looks up Google's public keys by the token's key ID; tests pass their own.
 */
class GoogleIdTokenVerifier(clientId: String, keyProvider: RSAKeyProvider) {

    private val verifier = JWT.require(Algorithm.RSA256(keyProvider))
        .withIssuer(*GOOGLE_ISSUERS)
        .withAudience(clientId)
        // Phone and server clocks can differ a little; Google's tokens last an hour anyway.
        .acceptLeeway(CLOCK_LEEWAY_SECONDS)
        .build()

    /**
     * The identity in [idToken], or null if any check fails.
     * @throws GoogleKeysUnavailableException if Google's keys couldn't be fetched.
     */
    fun verify(idToken: String): GoogleIdentity? {
        val jwt = try {
            verifier.verify(idToken)
        } catch (e: JWTVerificationException) {
            return null
        }
        val subject = jwt.subject ?: return null
        val email = jwt.getClaim("email").asString() ?: return null
        if (jwt.getClaim("email_verified").asBoolean() != true) return null
        return GoogleIdentity(subject, email)
    }

    companion object {
        private val GOOGLE_ISSUERS = arrayOf("https://accounts.google.com", "accounts.google.com")
        private const val CLOCK_LEEWAY_SECONDS = 60L

        /**
         * Built from the `google` block of application.conf, or null when GOOGLE_CLIENT_ID isn't
         * set (a local server, or before the Google Cloud client exists): the endpoint then
         * answers GOOGLE_SIGN_IN_UNAVAILABLE instead of the server failing to start.
         */
        fun fromConfig(config: ApplicationConfig): GoogleIdTokenVerifier? {
            val clientId = config.propertyOrNull("google.clientId")?.getString()
                ?.takeIf { it.isNotBlank() } ?: return null
            val certsUrl = config.property("google.certsUrl").getString()
            // Google rotates its keys every few days and publishes the next one ahead of time,
            // so caching them for hours is safe and saves a request per sign-in.
            val jwks = JwkProviderBuilder(URI(certsUrl).toURL())
                .cached(10, 6, TimeUnit.HOURS)
                .rateLimited(10, 1, TimeUnit.MINUTES)
                .build()
            return GoogleIdTokenVerifier(clientId, jwksKeyProvider(jwks))
        }

        /** Looks keys up in Google's published set. An unknown key ID fails the signature check
         *  (null), but a failed download is our problem, not a bad token, so it's reported. */
        private fun jwksKeyProvider(jwks: JwkProvider) = object : RSAKeyProvider {
            override fun getPublicKeyById(keyId: String?): RSAPublicKey? = try {
                jwks.get(keyId).publicKey as? RSAPublicKey
            } catch (e: NetworkException) {
                throw GoogleKeysUnavailableException(e)
            } catch (e: JwkException) {
                null
            }

            override fun getPrivateKey(): RSAPrivateKey? = null
            override fun getPrivateKeyId(): String? = null
        }
    }
}
