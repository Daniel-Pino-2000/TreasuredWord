package com.application.bibleapp.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.minutes

/** Rate-limit zone name for AuthRoutes' unauthenticated endpoints (register/login/refresh) —
 *  see routes/AuthRoutes.kt. These are the only endpoints an attacker can hit without already
 *  holding a valid token, which makes them the actual brute-force/credential-stuffing target;
 *  everything else sits behind `authenticate("auth-jwt")` already. */
val AUTH_RATE_LIMIT = RateLimitName("auth")

/**
 * Keyed by the caller's IP (Ktor's default key) rather than by account, since the whole point is
 * limiting an attacker who doesn't have valid credentials yet — a per-account key would do
 * nothing for the register/credential-stuffing case, only for an already-known email. 10
 * requests/minute is generous for a real user (a handful of login attempts, or a client retrying
 * a dropped connection) while still cutting off a brute-force loop by roughly two orders of
 * magnitude (Phase 5).
 */
fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(AUTH_RATE_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
        }
    }
}
