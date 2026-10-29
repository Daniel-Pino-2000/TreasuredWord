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
    Google has verified that the person controls the inbox, but registering with a password
    never checks the email, so whoever set that password may not be its owner (someone can
    register another person's address and wait for them to arrive with Google). So linking
    hands the account to the Google user: **the password is removed and every existing refresh
    token revoked**, in the same transaction (#142). Afterwards it signs in with Google only.
    Access tokens already issued still work until they expire (15 minutes). If that account is
    already linked to a *different* Google account, it's refused rather than taken over.
    Accounts made with Google have no password until one is added (no endpoint for that yet),
    so password login can't match them.

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
Auth: required
Request: `LogoutRequest`
Success: `204 No Content` — revokes only that refresh token/session; other devices stay logged in
Errors: `401 UNAUTHORIZED`

---

## Account

```kotlin
@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val createdAt: String,
    val hasPassword: Boolean,   // false for an account made with Google (#112)
    val hasGoogle: Boolean      // Google sign-in is linked
)

@Serializable
data class DeleteAccountRequest(val password: String? = null, val googleIdToken: String? = null)
```

`hasPassword` and `hasGoogle` tell the app how to confirm deleting the account: the password, or
signing in with Google again (#112). Older apps ignore them.

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

## Deliberately deferred (not in v1)

- **Password reset / forgot-password flow** — needs an email-sending provider, which isn't set up
  yet. Until then, a lost password means a lost account; worth revisiting before a wider release.
- **Email verification on register** — same email-infrastructure dependency.
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
