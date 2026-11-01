package com.application.bibleapp.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The 6-digit codes emailed to verify an address or reset a password (#156).
 *
 * Unlike a refresh token (see RefreshTokenIssuer.kt), a code has only a million possible values,
 * so a plain SHA-256 of it could be reversed by trying them all: anyone who got a copy of the
 * database could read every live code. So the stored hash is an HMAC keyed with the server's
 * secret (JWT_SECRET), which isn't in the database. Online guessing is stopped separately, by
 * the few tries each code allows (EmailCodeStore.MAX_ATTEMPTS).
 */
object EmailCodeIssuer {
    private const val CODE_DIGITS = 6
    private val secureRandom = SecureRandom()

    /** A new random code, zero-padded, e.g. "042917". */
    fun generateCode(): String = secureRandom.nextInt(1_000_000).toString().padStart(CODE_DIGITS, '0')

    /** The value stored in `email_codes.code_hash`, never the code itself. */
    fun hash(code: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(code.toByteArray(Charsets.UTF_8)).joinToString(separator = "") { "%02x".format(it) }
    }

    /** Whether [code] is the one [storedHash] was made from. Compared in constant time, so how
     *  long the check takes says nothing about how close a guess was. */
    fun matches(code: String, storedHash: String, secret: String): Boolean =
        MessageDigest.isEqual(hash(code, secret).toByteArray(), storedHash.toByteArray())
}
