package com.application.bibleapp.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Rate-limit zone name for AuthRoutes' unauthenticated endpoints (register/login/refresh) —
 *  see routes/AuthRoutes.kt. These are the only endpoints an attacker can hit without already
 *  holding a valid token, which makes them the actual brute-force/credential-stuffing target;
 *  everything else sits behind `authenticate("auth-jwt")` already. */
val AUTH_RATE_LIMIT = RateLimitName("auth")

/** Rate-limit zone for every endpoint that sends an email (#156). Much stricter than [AUTH_RATE_LIMIT]:
 *  each email costs part of the provider's daily allowance and lands in a real person's inbox. */
val EMAIL_RATE_LIMIT = RateLimitName("email")

/** Rate-limit zone for the Bible Helper's questions (#124), on top of its 15-a-day limit: a
 *  loop sending questions would otherwise use a whole day's free neurons in seconds. */
val AI_RATE_LIMIT = RateLimitName("ai")

/** Rate-limit zone for reporting Bible Helper answers (#125): plenty for real reports, not enough to flood the table. */
val AI_REPORT_RATE_LIMIT = RateLimitName("ai-report")

/**
 * The caller's IP address, as both rate limits' key. Ktor's own default key is the same for
 * every caller, which made a limit server-wide (#162). On Render the server sits behind
 * Cloudflare and Render's proxy, so the TCP connection always comes from a proxy: Cloudflare puts
 * the real address in True-Client-IP, and Render puts it first in X-Forwarded-For. Either header
 * can be forged by a client that reaches the server another way, so these limits slow abuse
 * rather than stop it; the per-account limit in EmailCodeStore is what protects one person's
 * inbox, and bcrypt's slowness what protects a password.
 */
internal fun ApplicationCall.clientIp(): String =
    request.headers["True-Client-IP"]?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.origin.remoteHost

/**
 * Keyed by the caller's IP ([clientIp]) rather than by account, since the whole point is
 * limiting an attacker who doesn't have valid credentials yet — a per-account key would do
 * nothing for the register/credential-stuffing case, only for an already-known email. 10
 * requests/minute is generous for a real user (a handful of login attempts, or a client retrying
 * a dropped connection) while still cutting off a brute-force loop by roughly two orders of
 * magnitude (Phase 5).
 *
 * Until #162 the auth zone had no key of its own, so Ktor's default (one key for everyone) made
 * it 10 a minute for the whole server: ten people signing in at once locked out the eleventh.
 */
fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(AUTH_RATE_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> call.clientIp() }
        }
        // 10 emails an hour from one IP: enough for a household creating accounts and asking
        // for a new code or two, far too few to be worth abusing (#156).
        register(EMAIL_RATE_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.hours)
            requestKey { call -> call.clientIp() }
        }
        // 5 questions a minute per account (#124). Unlike the zones above, the caller is signed
        // in (the zone sits inside authenticate), so the key is the account and a shared IP
        // (a church's Wi-Fi) doesn't hold back everyone on it.
        register(AI_RATE_LIMIT) {
            rateLimiter(limit = 5, refillPeriod = 1.minutes)
            requestKey { call -> call.principal<JWTPrincipal>()?.payload?.subject ?: call.clientIp() }
        }
        // 10 reports an hour per account (#125), keyed like the ai zone.
        register(AI_REPORT_RATE_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.hours)
            requestKey { call -> call.principal<JWTPrincipal>()?.payload?.subject ?: call.clientIp() }
        }
    }
}
