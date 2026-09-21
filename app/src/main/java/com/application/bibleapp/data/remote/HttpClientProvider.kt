package com.application.bibleapp.data.remote

import android.content.Context
import com.application.bibleapp.data.local.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Provides a single shared HttpClient instance for the whole app.
 *
 * Why we use an object:
 *  - 'object' makes this a singleton (only one instance exists)
 *  - prevents memory leaks
 *  - prevents creating multiple clients unnecessarily
 *
 * Why this matters:
 *  - HttpClient is expensive to create
 *  - Reusing one instance improves performance and battery usage
 */
object HttpClientProvider {

    /**
     * The backend server's API root — see server/docs/api_contract.md for what lives under it.
     * Points at the deployed TreasuredWord backend on Render (server/DEPLOY.md, Phase 5), reachable
     * from an emulator, a real device, or anywhere else — HTTPS, no network_security_config
     * cleartext exception needed. The local-dev alternative
     * (http://10.0.2.2:8080/api/v1, the emulator's alias for the host machine's own
     * ./gradlew :server:run) still works for offline backend iteration; swap this constant back
     * temporarily if debugging against a local server, but don't commit that swap.
     */
    const val BASE_URL = "https://treasuredword.onrender.com/api/v1"

    private lateinit var tokenStore: TokenStore

    /**
     * Must be called once, before [client] is first accessed anywhere in the app.
     * [com.application.bibleapp.BibleApplication.onCreate] does this as the very first thing
     * it runs, the same way it already wires up the daily-verse jobs.
     */
    fun init(context: Context) {
        tokenStore = TokenStore(context.applicationContext)
    }

    /**
     * Shared JSON config — reused both for Ktor's automatic content negotiation
     * and for manual `json.decodeFromString(...)` calls (e.g. HelloAoBibleDataSource
     * decoding a manually-buffered response body) so behavior stays consistent.
     */
    val json = Json {
        // If API returns extra fields your models don't have, your app will NOT crash
        ignoreUnknownKeys = true

        // Allows slightly non-strict JSON (e.g., missing quotes)
        isLenient = true
    }

    /**
     * Lazily-initialized Ktor HTTP client.
     *
     * 'by lazy' means:
     *  - client is created ONLY when first accessed
     *  - not created at app startup
     *  - thread-safe
     */
    val client: HttpClient by lazy {

        // OkHttp, not CIO - see the dependency comment in app/build.gradle.kts for why:
        // CIO's TLS handshake breaks HTTPS to any host once the app's network security
        // config has a <domain-config> block, which ours does (see src/debug's
        // network_security_config.xml).
        HttpClient(OkHttp) {

            // Install automatic content negotiation
            install(ContentNegotiation) {
                // Tell Ktor to use kotlinx.serialization for JSON
                json(json)
            }

            // Prevents a hung/slow request from blocking a bulk download indefinitely.
            // Large single-request downloads (e.g. a translation's complete.json) override
            // requestTimeoutMillis per-call since a multi-MB body can legitimately take
            // longer than this default on a slow connection.
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }

            // Automatically attaches "Authorization: Bearer <accessToken>" to every request
            // to the backend, and on a 401, transparently calls /auth/refresh and retries
            // once with the new token — callers (AuthRepository, HighlightRepository, etc.)
            // never have to think about the access token's 15-minute expiry themselves.
            install(Auth) {
                bearer {
                    // Without this, the Auth plugin's default behavior attaches our
                    // backend's bearer token to EVERY request through this shared client -
                    // including the third-party Bible/verse APIs (bible.helloao.org,
                    // OurManna) - leaking the user's access token to unrelated services.
                    // Only skip attaching it when the request isn't going to our own
                    // backend host.
                    sendWithoutRequest { request ->
                        request.url.host == Url(BASE_URL).host
                    }

                    // Called before every request that needs auth. Reading fresh from
                    // TokenStore each time (not a cached value) so a login/logout that
                    // happened since this client was created is always picked up.
                    loadTokens {
                        val access = tokenStore.accessToken
                        val refresh = tokenStore.refreshToken
                        if (access != null && refresh != null) BearerTokens(access, refresh) else null
                    }

                    // Called once, automatically, the first time a request comes back 401.
                    refreshTokens {
                        val currentRefreshToken = tokenStore.refreshToken ?: return@refreshTokens null

                        // Uses this callback's own `client` (not HttpClientProvider.client)
                        // and marks the request as a refresh so the Auth plugin doesn't try
                        // to intercept/retry it too — Ktor's own documented pattern for
                        // avoiding infinite recursion here.
                        val response = client.post("$BASE_URL/auth/refresh") {
                            markAsRefreshTokenRequest()
                            contentType(ContentType.Application.Json)
                            setBody(RefreshRequestDto(refreshToken = currentRefreshToken))
                        }

                        if (!response.status.isSuccess()) {
                            // The refresh token itself is invalid, expired, or was already
                            // rotated away by an earlier refresh — there's no way to recover
                            // silently. Clear the session so the app treats the user as
                            // logged out instead of retrying a refresh that will never work.
                            tokenStore.clear()
                            return@refreshTokens null
                        }

                        val refreshed = response.body<AuthResponseDto>()
                        tokenStore.updateTokens(refreshed.accessToken, refreshed.refreshToken)
                        BearerTokens(refreshed.accessToken, refreshed.refreshToken)
                    }
                }
            }
        }
    }
}
