# BibleApp API Contract

**Status:** Draft v1 — complete for **Auth**, **Account**, **Reading Progress**, **Notes**, and
**Highlights**: everything the roadmap's "user accounts and sync" feature needs.

This is a living document — update it *before* changing a route, not after.

---

## Conventions (apply to every endpoint below)

- **Base path:** `/api/v1`
- **Auth:** every endpoint requires `Authorization: Bearer <access token>` unless marked
  `Auth: none`. Resource endpoints (highlights, notes, reading progress, account) are implicitly
  scoped to the authenticated user — nobody can read, edit, or delete another user's data, and
  the user is never passed as a request field (it comes from the token).
- **IDs:** server-generated UUID strings.
- **Timestamps:** ISO-8601 strings, UTC (e.g. `2026-09-11T14:32:00Z`) — never epoch millis.
- **Errors:** every non-2xx response uses the shared `ErrorResponse` shape (below).

---

## Design decisions

Written down so they don't get re-litigated per-endpoint later — see
[Section 1 of the roadmap PDF](Ktor_Backend_Contract_And_Roadmap.pdf) for why this step happens
before code.

### Auth & sessions

1. **Two tokens, not one.** A short-lived access JWT (minutes, validated by the existing
   `plugins/Security.kt` scaffold) plus a longer-lived opaque refresh token, stored hashed
   server-side per session/device. A single long-lived JWT can't be revoked before it expires;
   this can — logging out actually ends that session instead of just deleting a local copy of a
   token that's still valid.
2. **Refresh token rotation.** Every call to `/auth/refresh` returns a *new* refresh token and
   invalidates the one that was just used. If an already-used refresh token shows up again later,
   that's a signal the token was copied/stolen, not normal client behavior.
3. **Tokens travel in the JSON body, not cookies.** This is a native Android client, not a
   browser — there's no cookie jar to lean on. The client stores both tokens in encrypted local
   storage (Phase 6 of the roadmap), same as any other app secret.
4. **Login failures are deliberately generic** (`INVALID_CREDENTIALS`, whether the email doesn't
   exist or the password is wrong). The API should never let a caller distinguish "wrong
   password" from "no such account" — standard defense against account enumeration.
5. **Account deletion requires re-entering the password**, not just a valid access token. A
   destructive, irreversible action deserves a stronger check than "whoever is currently holding
   the bearer token" — that token could be from a device the user no longer trusts. An account
   that uses Google sign-in can instead confirm by signing in with that same Google account
   again (#112), since an account made with Google has no password.
6. **Account deletion cascades** to every highlight, note, reading-progress record, and refresh
   token owned by that user. This isn't optional polish: Google Play's User Data policy requires
   an in-app path to delete an account and its data for any app that lets users create one, so
   this endpoint has to exist before accounts can ship at all, not just be a nice-to-have.

21. **Google sign-in finds the account by Google's account ID, then by email** (#112). The
    server checks the Google ID token itself (Google's signature, issuer, audience = our web
    client ID, expiry, and `email_verified`), never just the email the app reports. A Google
    account already linked signs in to its account even if its email changed since. Otherwise
    an account with the same email is linked to it, rather than a second account created.
    Google has verified that the person controls the inbox. If the account's email is verified
    too (decision 22), whoever set the password proved the same thing, so they're the same
    person: linking **keeps the password and every session** (#158). If it isn't verified,
    whoever set that password may not be its owner (someone can register another person's
    address and wait for them to arrive with Google), so linking hands the account to the
    Google user: **the password is removed and every existing refresh token revoked**, in the
    same transaction (#142), and it signs in with Google only until a password reset adds one.
    Access tokens already issued still work until they expire (15 minutes). Either way the
    account is verified afterwards. If that account is already linked to a *different* Google
    account, it's refused rather than taken over. Accounts made with Google have no password
    until a password reset adds one (decision 24), so password login can't match them.
22. **Emails are verified with a 6-digit code, not a link** (#148, #156). The person types the
    code into the app, so it needs no web page or deep link, and it works on whichever device
    reads the email. Each code works once, for 15 minutes and 5 tries; asking for a new one
    cancels the old one. The server stores only an HMAC of each code, keyed with the server's
    secret, since with a million possible codes a plain hash could be reversed by trying them
    all. A failed check gives the same answer whether the code was wrong, expired, used or out
    of tries. Registering doesn't send a code itself: the app calls `verify-email/send` right
    after, so a register never fails because email is down, and every email goes through the
    stricter email rate limit. Unverified accounts work fully (sync included); verification
    only matters for linking Google (decision 21) and later features that rely on the email.
23. **Sending email is limited twice** (#156): 10 emails an hour per IP address (the `email`
    rate-limit zone, much stricter than the `auth` zone's 10 a minute), and 5 codes an hour per
    account across every kind of code, so one person's inbox can't be flooded from many IPs.
    Each email also costs part of the provider's daily allowance.
24. **"Forgot password" never says whether an account exists** (#157, like decision 4).
    `password-reset/send` always answers `204`: it answers first and looks the email up
    afterwards, so neither the answer nor its timing depends on the account. Resetting needs the
    email, the newest reset code and a new password; an unknown email gets the same
    `INVALID_CODE` as a wrong code. A reset **revokes every refresh token** (every device signs
    in again), and marks the email verified, since the code reached the inbox. A Google-only
    account can use it to add a password: the code proves the inbox is theirs, as Google does.

### Reading progress

7. **Modeled as a singleton per user** (`GET`/`PUT` only — no id, no list). There's exactly one
   "where I left off," not a collection of them.
8. **The client sends its own `readAt` timestamp with every update; the server only applies it if
   `readAt` is at least as new as the stored value's `updatedAt`.** Otherwise a device that was
   offline for a while and syncs last would silently overwrite a newer position from another
   device — a visible, confusing regression ("why did it jump back to yesterday's chapter") that
   this one extra field prevents. A stale write isn't an error; the server just keeps its current
   value and returns it unchanged.

### Notes & Highlights

9. **A highlight or note covers an explicit list of verses** (`verses: List<VerseLocation>`), not
   a range. This matches the actual UX — select some verses (a contiguous passage, a couple of
   scattered cross-references, whatever) and save them as one highlight or note, shown as a
   single library card.
10. **Verses may span multiple chapters or books.** Nothing in the contract restricts a
    highlight/note to one chapter; most will stay within one passage in practice, but there's no
    artificial limit forcing that.
11. **`versionId` lives on the highlight/note itself, not per verse.** A highlight is anchored to
    the translation the user was reading in; mixing versions within one highlight isn't
    meaningful, since versification can differ between translations.
12. **Display order is a client concern.** `verses` isn't guaranteed to come back in any
    particular order; sort client-side by `(bookId, chapter, verse)` if the library needs Bible
    order.
13. **Deletions are soft, not hard** (`deletedAt: String?`). A plain `GET` returns only active
    items; passing `updatedSince` also returns tombstones for anything changed since that time,
    so a syncing device can tell "deleted" apart from "not sent yet."
14. **Highlights are recolor-only after creation; Notes allow editing both text and the verse
    list.** Recoloring is the only realistic post-creation edit for a highlight — delete and
    recreate for anything else. Notes are more often revised from a dedicated editor, so
    re-picking the covered verses is a reasonable edit, not just a fix-up.
15. **No pagination yet**, but filtering matters now that verses is a list: `GET
    .../?bookId=&chapter=` matches if **any** entry in `verses` is in that book/chapter.
16. **Server does not validate verse locations against real per-version verse counts.** The
    client already has this data (`ChapterStructure`) and is trusted to send valid ones.
17. **`verses` must be non-empty**, capped at a sane maximum (e.g. 500) as a payload guard.

### General

18. **No conflict resolution beyond last-write-wins** (reading progress's `readAt` check is the
    one deliberate exception, since a silent regression there is unusually annoying). Fine for
    one person syncing across their own devices; would need real thought if this ever became
    multi-user/collaborative.
19. **A verse belongs to at most one highlight — enforced by the client, not the server.** Like
    a real highlighter, highlighting an already-highlighted verse re-colors it rather than
    stacking a second highlight on top. Since a highlight's verse list is immutable (decision 14),
    the Android client does this by deleting each overlapped highlight and re-creating it over
    its remaining verses, in its original color, before creating the new one. The server still
    accepts overlaps (another client or an old sync could produce them); the reader then shows
    the most recent one.
20. **List responses carry the server's clock** (`serverTime`, #84). A syncing client sends it
    back as the next `updatedSince` instead of its own clock: a phone whose clock runs ahead of
    the server's would otherwise skip every change made in that gap, deletes included. It's read
    before the query runs, so anything saved while the query runs is at or after it; the Android
    client still starts its next pull a minute earlier, since re-pulling a few rows is harmless.

---

## Shared types

```kotlin
@Serializable
data class VerseLocation(
    val bookId: Int,   // 1..66, standard canon numbering
    val chapter: Int,
    val verse: Int
)

@Serializable
data class ErrorResponse(
    val code: String,                        // e.g. "VALIDATION_ERROR", "NOT_FOUND"
    val message: String,                     // human-readable, safe to show or log
    val fieldErrors: Map<String, String>? = null
)
```

**Error codes used below:**

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Malformed body — bad email format, password too short, empty/oversized `verses`, a `bookId` outside 1..66, etc. |
| `INVALID_CREDENTIALS` | 401 | Login or account-deletion password confirmation failed — same response whether the email doesn't exist or the password is wrong (decision 4) |
| `UNAUTHORIZED` | 401 | Missing, invalid, or expired access/refresh token |
| `EMAIL_ALREADY_REGISTERED` | 409 | Registration attempted with an email already in use |
| `EMAIL_ALREADY_VERIFIED` | 409 | Asked for a verification code for an email that's already verified |
| `INVALID_CODE` | 400 | An emailed code is wrong, expired, already used or out of tries (one answer for all, decision 22) |
| `TOO_MANY_EMAILS` | 429 | This account has been sent 5 codes in the last hour (decision 23) |
| `EMAIL_UNAVAILABLE` | 503 | The server can't send email: no provider configured, or the provider refused or didn't answer |
| `NOT_FOUND` | 404 | No resource with that id — **also returned when the id belongs to another user**, so ownership is never leaked by a 403 |

---

## Auth

```kotlin
@Serializable
data class RegisterRequest(val email: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class GoogleSignInRequest(val idToken: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class AuthResponse(
    val userId: String,
    val accessToken: String,
    val accessTokenExpiresInSeconds: Long,
    val refreshToken: String
)
```

**Emails are compared without regard to upper/lower case** (#114). Register and login trim
spaces and lower-case the email, so `Ana@example.com` and `ana@example.com` are the same account,
and the server stores and returns emails in lower case (`UserResponse.email` included). Phone
keyboards often capitalise the first letter, and Google sign-in (#112) gives emails in lower case.

### `POST /api/v1/auth/register`
Auth: none
Request: `RegisterRequest`
Success: `201 Created` → `AuthResponse`
Errors: `400 VALIDATION_ERROR`, `409 EMAIL_ALREADY_REGISTERED`

### `POST /api/v1/auth/login`
Auth: none
Request: `LoginRequest`
Success: `200 OK` → `AuthResponse`
Errors: `400 VALIDATION_ERROR`, `401 INVALID_CREDENTIALS`

### `POST /api/v1/auth/google`
Auth: none
Request: `GoogleSignInRequest`: the Google ID token the app got from Credential Manager, requested
with our **web** client ID as the server client ID (#112, decision 21)
Success: `201 Created` → `AuthResponse` for a new account; `200 OK` → `AuthResponse` when the
Google account was already linked, or has just been linked to the account with its email (which
removes that account's password and revokes its other sessions, decision 21)
Errors: `401 INVALID_GOOGLE_TOKEN` (fails any check: signature, issuer, audience, expiry,
unverified email), `409 EMAIL_LINKED_TO_OTHER_GOOGLE_ACCOUNT`, `503 GOOGLE_SIGN_IN_UNAVAILABLE`
(the server has no `GOOGLE_CLIENT_ID`, or couldn't fetch Google's keys)
Rate limited like register and login.

### `POST /api/v1/auth/refresh`
Auth: none — the refresh token itself is the credential
Request: `RefreshRequest`
Success: `200 OK` → `AuthResponse` (new access token **and** a newly-rotated refresh token; decision 2)
Errors: `401 UNAUTHORIZED` (invalid, expired, or already-rotated refresh token)

### `POST /api/v1/auth/logout`
Auth: none — the refresh token in the body is the credential, as for `refresh` (#173). The app
signs out on the phone first and has no access token left by the time it calls this.
Request: `LogoutRequest`
Success: `204 No Content` — revokes only that refresh token/session; other devices stay logged in.
The same `204` for an unknown or already-revoked token, which changes nothing.
Errors: `429` (`auth` zone)

---

## Email verification and password reset

```kotlin
@Serializable
data class VerifyEmailRequest(val code: String)   // the 6 digits from the email; spaces ignored

@Serializable
data class PasswordResetSendRequest(val email: String)

@Serializable
data class PasswordResetRequest(val email: String, val code: String, val newPassword: String)
```

Emails are compared without regard to case, as in register and login.

A rate limit that's hit answers `429` with no body: the `email` zone for endpoints that send
email, the `auth` zone for the others (decision 23).

### `POST /api/v1/auth/verify-email/send`
Auth: required
Request: none
Success: `204 No Content` — a new code was emailed to the account's address (decision 22); any
older one stops working. Sent before answering, so a `204` means the provider accepted it.
Errors: `401 UNAUTHORIZED`, `409 EMAIL_ALREADY_VERIFIED`, `429 TOO_MANY_EMAILS`,
`429` (`email` zone), `503 EMAIL_UNAVAILABLE`

### `POST /api/v1/auth/verify-email`
Auth: required
Request: `VerifyEmailRequest`
Success: `204 No Content` — the email is verified (`emailVerified` in `GET /users/me`). Also
`204` if it already was, so a second tap or another device isn't an error.
Errors: `400 INVALID_CODE`, `401 UNAUTHORIZED`, `429` (`auth` zone)

### `POST /api/v1/auth/password-reset/send`
Auth: none
Request: `PasswordResetSendRequest`
Success: `204 No Content`, **whether or not the email has an account** (decision 24). If it has
one, a reset code is emailed to it shortly after; any older one stops working. Nothing is sent
when the account has been sent 5 codes in the last hour, and the answer is still `204`.
Errors: `429` (`email` zone), `503 EMAIL_UNAVAILABLE` (no provider configured; the same for
every email)

### `POST /api/v1/auth/password-reset`
Auth: none — the emailed code is the credential
Request: `PasswordResetRequest` (`newPassword`: same rules as register)
Success: `204 No Content` — the password is changed, every refresh token for the account is
revoked, and the email is verified (decision 24). The app then signs in with the new password.
Errors: `400 VALIDATION_ERROR` (new password too short; checked before the code, so it doesn't
use up a try), `400 INVALID_CODE` (also for an email with no account), `429` (`auth` zone)

---

## Account

```kotlin
@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val createdAt: String,
    val hasPassword: Boolean,   // false for an account made with Google (#112)
    val hasGoogle: Boolean,     // Google sign-in is linked
    val emailVerified: Boolean  // the owner has proved the inbox is theirs (#155)
)

@Serializable
data class DeleteAccountRequest(val password: String? = null, val googleIdToken: String? = null)
```

`hasPassword` and `hasGoogle` tell the app how to confirm deleting the account: the password, or
signing in with Google again (#112). Older apps ignore them.

`emailVerified` is true once the owner has proved the inbox is theirs (#155): with an emailed
code, or by signing in with Google, which only gives verified emails. Accounts made or linked
with Google before this field existed count as verified; password-only accounts start
unverified. The app uses it to offer "Verify your email".

### `GET /api/v1/users/me`
Auth: required
Success: `200 OK` → `UserResponse`
Errors: `401 UNAUTHORIZED`

### `DELETE /api/v1/users/me`
Auth: required
Request: `DeleteAccountRequest` with exactly one of `password` or `googleIdToken`, a fresh Google
ID token for the Google account linked to this account (decision 5)
Success: `204 No Content` — deletes the account and cascades to all owned highlights, notes, reading progress, and refresh tokens (decision 6)
Errors: `400 VALIDATION_ERROR` (neither given), `401 UNAUTHORIZED` (bad/missing token),
`401 INVALID_CREDENTIALS` (wrong password, or a Google account that isn't this account's),
`401 INVALID_GOOGLE_TOKEN`, `503 GOOGLE_SIGN_IN_UNAVAILABLE`

---

## Reading Progress

```kotlin
@Serializable
data class ReadingProgressResponse(
    val versionId: String,
    val bookId: Int,
    val chapter: Int,
    val verse: Int,
    val updatedAt: String
)

@Serializable
data class UpdateReadingProgressRequest(
    val versionId: String,
    val bookId: Int,
    val chapter: Int,
    val verse: Int,
    val readAt: String   // client's local timestamp for this read event — see decision 8
)
```

### `GET /api/v1/reading-progress`
Auth: required
Success: `200 OK` → `ReadingProgressResponse`
Errors: `401 UNAUTHORIZED`, `404 NOT_FOUND` (nothing synced yet — e.g. a brand-new account)

### `PUT /api/v1/reading-progress`
Auth: required
Request: `UpdateReadingProgressRequest`
Success: `200 OK` → `ReadingProgressResponse` — either the newly-applied position, or the
still-current one if `readAt` was stale (decision 8)
Errors: `400 VALIDATION_ERROR`, `401 UNAUTHORIZED`

*(No `DELETE`: resetting progress is just another `PUT`; there's no meaningful "no position at
all" once one exists.)*

---

## Highlights

A highlight = a set of verses (in one version) + a color. The verse list is immutable after
creation; only the color can be changed.

```kotlin
@Serializable
data class CreateHighlightRequest(
    val versionId: String,
    val verses: List<VerseLocation>,   // non-empty, see decision 17
    val color: Int                     // ARGB, same convention as UserVerse.highlightColor in the app today
)

@Serializable
data class UpdateHighlightRequest(
    val color: Int   // the only editable field — see decision 14
)

@Serializable
data class HighlightResponse(
    val id: String,
    val versionId: String,
    val verses: List<VerseLocation>,
    val color: Int,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null
)

@Serializable
data class HighlightListResponse(
    val highlights: List<HighlightResponse>,
    val serverTime: String             // ISO-8601, the server's clock when the list was read (decision 20)
)
```

### `GET /api/v1/highlights`
Auth: required
Query params: `bookId` (optional Int), `chapter` (optional Int, requires `bookId`), `updatedSince` (optional ISO-8601 — includes soft-deleted tombstones when present)
Success: `200 OK` → `HighlightListResponse` (active highlights only, unless `updatedSince` is set; see decision 15 for filter semantics). Pass its `serverTime` as the next `updatedSince` (decision 20)
Errors: `401 UNAUTHORIZED`

### `POST /api/v1/highlights`
Auth: required
Request: `CreateHighlightRequest`
Success: `201 Created` → `HighlightResponse`
Errors: `400 VALIDATION_ERROR`, `401 UNAUTHORIZED`

### `PATCH /api/v1/highlights/{id}`
Auth: required
Request: `UpdateHighlightRequest`
Success: `200 OK` → `HighlightResponse`
Errors: `400 VALIDATION_ERROR`, `401 UNAUTHORIZED`, `404 NOT_FOUND`

### `DELETE /api/v1/highlights/{id}`
Auth: required
Success: `204 No Content` (soft delete — sets `deletedAt`; see decision 13)
Errors: `401 UNAUTHORIZED`, `404 NOT_FOUND`

---

## Notes

A note = a set of verses (in one version) + free text. Unlike highlights, both the text and the
verse list can be edited in place (decision 14).

```kotlin
@Serializable
data class CreateNoteRequest(
    val versionId: String,
    val verses: List<VerseLocation>,   // non-empty, see decision 17
    val text: String
)

@Serializable
data class UpdateNoteRequest(
    val verses: List<VerseLocation>,   // stays within the same versionId it was created with;
    val text: String                   // to move a note to a different version, delete and recreate
)

@Serializable
data class NoteResponse(
    val id: String,
    val versionId: String,
    val verses: List<VerseLocation>,
    val text: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null
)

@Serializable
data class NoteListResponse(
    val notes: List<NoteResponse>,
    val serverTime: String             // ISO-8601, the server's clock when the list was read (decision 20)
)
```

### `GET /api/v1/notes`
Auth: required
Query params: `bookId` (optional Int), `chapter` (optional Int, requires `bookId`), `updatedSince` (optional ISO-8601)
Success: `200 OK` → `NoteListResponse` (active notes only, unless `updatedSince` is set; see decision 15 for filter semantics). Pass its `serverTime` as the next `updatedSince` (decision 20)
Errors: `401 UNAUTHORIZED`

### `GET /api/v1/notes/{id}`
Auth: required
Success: `200 OK` → `NoteResponse`
Errors: `401 UNAUTHORIZED`, `404 NOT_FOUND`
*(No equivalent single-highlight GET: highlights are always acted on from the already-loaded
list in the reading view. Notes may be opened in a dedicated editor navigated to independently,
so they need a direct fetch.)*

### `POST /api/v1/notes`
Auth: required
Request: `CreateNoteRequest`
Success: `201 Created` → `NoteResponse`
Errors: `400 VALIDATION_ERROR`, `401 UNAUTHORIZED`

### `PUT /api/v1/notes/{id}`
Auth: required
Request: `UpdateNoteRequest`
Success: `200 OK` → `NoteResponse`
Errors: `400 VALIDATION_ERROR`, `401 UNAUTHORIZED`, `404 NOT_FOUND`

### `DELETE /api/v1/notes/{id}`
Auth: required
Success: `204 No Content` (soft delete)
Errors: `401 UNAUTHORIZED`, `404 NOT_FOUND`

---

## Bible Helper

AI answers to Bible questions from a Baptist position (#120; plan: `docs/ai_helper_plan.md`).
The server adds its own fixed instructions and asks Cloudflare Workers AI's free plan. Until
#131 only debug builds of the app call these endpoints.

```kotlin
@Serializable
data class AiMessageDto(val role: String, val content: String)   // "user" or "assistant"

@Serializable
data class AiPassage(
    val reference: String,       // USFM, within one chapter: "JHN 3", "JHN 3:16-18", "LUK 12:4,6-7"
    val text: String             // the passage in the reader's own translation, 1–2,000 characters
)

@Serializable
data class AiAskRequest(
    val messages: List<AiMessageDto>,
    val language: String,
    val passage: AiPassage? = null // only when asked with "Ask about this verse" (#132)
)

@Serializable
data class AiAskResponse(
    val answer: String,          // verses marked as [[JHN 3:16]], [[ROM 8:28-30]] or [[PSA 23]], USFM book codes (see below)
    val crisis: Boolean,         // the person may be in danger: show the help card
    val remainingToday: Int,     // questions left today (UTC) after this one
    val promptVersion: String    // which instructions answered; send it back with a report
)
```

Rules for `AiAskRequest` (any broken one is `400 VALIDATION_ERROR`, with `fieldErrors` keyed
`messages`, `language` or `passage`):
- `messages`: 1–10, oldest first, alternating, the last one from `user`. History may start with
  an `assistant` message (the app drops the oldest to fit). The only roles are `user` and
  `assistant`, so the app can never send instructions of its own.
- Sizes: each user message ≤ 1,000 characters, each assistant message ≤ 4,000, all together
  ≤ 8,000. No blank messages.
- `language`: one of the app's tags: `en`, `de`, `es`, `fr`, `pt-BR`, `id`, `fil`, `sw`, `ko`,
  `zh-Hans`. The answer comes in that language.
- `passage` (optional, #132): `reference` is a USFM code, a space and a chapter, then optionally
  `:verse`, `-verse` and `,verse` parts, all in one chapter; `text` is not blank and at most 2,000
  characters. Its errors are keyed `passage`. The server adds it after its instructions as the
  passage the question is about, quoted, with `"""` and `[[`/`]]` in the text flattened so it can't
  close its quotes or write a marker. The app sends it with each question of a conversation that
  started from the reader, so follow-ups keep it; it isn't part of `messages` or their size limits.

A rate limit that's hit answers `429` with no body: the `ai` zone, 5 questions a minute per
account. An account that has already had today's 15 answers isn't counted against it, so however
quickly it asks, it gets `AI_DAILY_LIMIT`, never the bare rate-limit `429` (#271).

### `POST /api/v1/ai/ask`
Auth: required
Request: `AiAskRequest`
Success: `200 OK` with `AiAskResponse`. Counts as one of the day's 15 questions.
Errors:

| Status | `code` | When | Counted |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | The rules above | No |
| 401 | `UNAUTHORIZED` | Not signed in or the token expired | No |
| 429 | `AI_DAILY_LIMIT` | This account already got 15 answers today (UTC); more at 00:00 UTC | No |
| 429 | (no body) | More than 5 questions in a minute (`ai` zone), while some of today's 15 are left | No |
| 503 | `AI_RESTING` | Cloudflare's free daily allowance is used up (its error `3036`); back at 00:00 UTC | No |
| 503 | `AI_UNAVAILABLE` | The AI failed, took longer than 30 s, or isn't set up on this server | No |

The instructions ask for `[[BOOK C:V]]`, `[[BOOK C:V-V]]` or a whole chapter `[[PSA 23]]` (a card,
allowed since instructions v2, #206) with the USFM code. The app also reads a whole book `[[JHN]]` (its name, no card), a space
after the number (`[[1 JHN 2:3]]`), an English name or abbreviation (`[[PHIL 4:6]]`) and a list
(`[[JHN 1:1, 14]]`) (#190). Any other marker, such as a chapter range `[[REV 13-19]]` or an
unknown book, shows as plain text; the evaluation's automatic checks flag exactly those (#207).

An answer that ran out of room ends with " …" so it doesn't look finished. If the AI returned
only the crisis marker, `answer` is empty, `crisis` is `true`, and the question isn't counted.
The daily count is taken atomically before the AI is asked and given back if no answer comes,
so questions sent at the same moment can't go past 15.

The server never logs question or answer text.

### `POST /api/v1/ai/reports`
Auth: required
Request:

```kotlin
@Serializable
data class AiReportRequest(
    val question: String,             // the question that was answered, 1–1,000 characters
    val answer: String,               // the reported answer, 1–4,000 characters
    val reason: String,               // WRONG_TEACHING, WRONG_VERSE, HARMFUL or OTHER
    val comment: String? = null,      // optional, at most 500 characters
    val language: String,             // one of the tags above
    val promptVersion: String? = null // AiAskResponse.promptVersion, at most 20 characters
)
```

Success: `204 No Content`. Stored **without the user's ID** (we need the bad answer, not who
reported it) and deleted after 90 days. Google Play's AI-Generated Content policy requires a way
to report answers (#125).
Errors: `400 VALIDATION_ERROR` (with `fieldErrors`), `401 UNAUTHORIZED`, `429` with no body (the
`ai-report` zone, 10 reports an hour per account)

---

## Deliberately deferred (not in v1)

- **Change password / change email** — straightforward additions once the account exists, but no
  screen or need for them yet.
- **Multi-session management** (list active devices, revoke one remotely) — `logout` only ends
  the current device's session; there's no way yet to see or kill *other* sessions from one
  device.
- **Rate limiting on auth endpoints** — a Phase 5 (hardening) concern; doesn't change any shape
  in this document, just needs enforcing before this ships publicly.

## Open items carried over from Notes & Highlights

- Whether highlight `color` needs validation against a fixed palette vs. accepting any ARGB int.
- Whether verse locations should eventually be validated server-side against real per-version
  verse counts (decision 16) — needs the server to hold each version's `ChapterStructure`.
- Whether `GET /highlights/{id}` is needed once/if a highlight detail view is added.
- How `verses` is persisted server-side (a `jsonb` column vs. a child table) is a Phase 3 storage
  decision — it doesn't change this contract either way.
