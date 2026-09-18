# BibleApp UI Integration Roadmap

**Status:** living document — update it as phases complete or decisions change, same convention
as `api_contract.md`. Companion to `docs/api_contract.md` (the backend contract this UI wires up
to) and `docs/Ktor_Backend_Contract_And_Roadmap.pdf` (the backend's own phase plan).

Covers wiring the Ktor sync backend (auth, highlights, notes, reading progress) into the Android
app: verse selection/highlighting, notes, a redesigned account/library/settings hub, and auth UX
polish.

| Phase | Focus | Status |
|---|---|---|
| A | Local persistence (highlights/notes tables) | ✅ Done (`874e415`) |
| B | Verse selection & highlighting UI | ✅ Done (`bebb05d`) |
| C | Notes UI | ✅ Done (`95ba129`) |
| D | Library screen (saved highlights/notes) | ✅ Done (`88993d0`) |
| E | Sync worker (local ↔ backend) | ✅ Done (`f9ad4a3`, `8cd3ce9`) |
| F | Auth UX polish | ✅ Done (`ef4a57e`) |
| G | "More" → Profile hub redesign | ✅ Done (`441a90c`) |
| H | Edge cases (empty states, conflicts, retries) | ✅ Done (`b9e08a0`) |

Ordered so each phase is demoable before the next depends on it: B/C/D work fully offline with no
backend involved, E only wires sync on top of what B–D already built, F/G/H are polish once the
core loop works.

---

## Starting point (as of 2026-09-18)

- **Auth UI already exists and works** — `screens/LoginView.kt`, `screens/RegisterView.kt`,
  backed by `AuthViewModel`, with sign-in/out and delete-account (password re-confirm) already in
  `screens/SettingsView.kt`. Not a 0-to-1 build — a polish pass (Phase F).
- **Highlights/notes UI was 0-to-1.** `components/BibleText.kt` rendered verses as a single
  `AnnotatedString` with no selection interaction. `data/model/UserVerse.kt` was a dead Room
  `@Entity` (Room is a declared but unused Gradle dependency — no `@Database`/`@Dao` anywhere),
  superseded by the tables described below.
- **The sync repos were remote-only** — `HighlightRepository`/`NoteRepository`/
  `ReadingProgressRepository` called the backend directly, no local cache. Fixed by the
  architecture decision below.
- **Design system already established**: Material3, warm/paper palette, light/dark
  (`ui/theme/Theme.kt`), spacing scale (`ui/theme/Spacing.kt`), reading-specific text styles
  (`ui/theme/ReadingStyle.kt`) — extend these, don't introduce a second visual language.
- **WorkManager is already a pattern** (`worker/DailyVerseFetchWorker.kt`,
  `worker/DailyVerseScheduler.kt`) — the sync worker (Phase E) follows the same shape.
- **Persistence convention is raw SQLite**, not Room — `data/local/BibleDatabaseManager.kt` owns
  the single on-device SQLite connection, with idempotent `CREATE TABLE IF NOT EXISTS` /
  `addColumnIfMissing` instead of a migration framework. New local tables follow this, not Room.

## Architecture decision: local-first

Highlights/notes save to local SQLite instantly and work offline/signed-out; a background sync
worker reconciles with the backend (last-write-wins, per contract) whenever signed in and online.
Signing in turns on *cross-device sync*, not the *feature itself*.

Rejected alternative: remote-only (write straight through `HighlightRepository`/`NoteRepository`).
Simpler, but means a network round-trip on every highlight tap, no offline support, and forces
sign-in just to annotate a verse — a bad fit for an app whose core value (reading) works fine
offline. Confirmed with the user 2026-09-18.

**Collaboration mode:** unlike the Ktor backend (where the user writes the code and this assistant
guides/reviews), Android/Compose UI work is implemented directly, in reviewed commits, at normal
working speed — confirmed with the user 2026-09-18.

---

## Phases

### Phase A — Local persistence — ✅ Done (commit `874e415`)

Extended `BibleDatabaseManager` with `highlights` and `notes` tables: `local_id`, `remote_id`
(null until first sync), `version_id`, `verses_json` (JSON-encoded `List<VerseLocationDto>`,
reusing the existing DTO rather than a duplicate type), `color`/`text`, `created_at`/`updated_at`,
`deleted_at` (soft delete, mirrors the server's tombstone model), `sync_status`
(`PENDING`/`SYNCED`).

New files: `data/model/Highlight.kt`, `data/model/Note.kt`, `data/model/SyncStatus.kt`,
`utils/TimeUtils.kt` (shared ISO-8601 timestamp helper, pulled out of
`ReadingProgressRepository`). CRUD in `BibleDatabaseManager`: insert/recolor/soft-delete/query for
highlights, insert/update/soft-delete/query for notes, plus `getPending*` for the sync worker's
future queue and `get*ForChapter` (any-verse-matches filter, mirroring the server's `bookId`/
`chapter` query params) for rendering the reading view.

### Phase B — Verse selection & highlighting UI — ✅ Done (`bebb05d`)

In `BibleText.kt`/`BibleView.kt`, writing to the Phase A tables only (no backend involved):

- Long-press a verse → selection mode (tinted background) → bottom contextual toolbar
  (`VerseSelectionToolbar`: selection counter, 5 curated color swatches from
  `ui/theme/HighlightColors.kt`, cancel). Tap toggles additional verses, including non-contiguous
  ones. Tapping a swatch applies the highlight immediately and clears the selection.
- Saved highlights render as translucent background spans in the `AnnotatedString`, sourced from
  `highlightsInChapter`.
- Tapping (not long-pressing) an already-highlighted verse opens `HighlightActionSheet` —
  `Change color` / `Remove` — never a fresh selection (verses are immutable after creation; only
  color is editable).
- All tap/long-press detection is unified through one gesture handler per paragraph (character
  offset via `TextLayoutResult.getOffsetForPosition`, mapped back to whichever verse or footnote
  marker owns that offset), replacing the old `withLink`/`LinkAnnotation` footnote-click mechanism
  rather than layering a second pointer-input handler on top of it — see the commit message for why.

Deferred, not in this pass: drag handles to extend a contiguous range (tap-to-toggle already
covers it), Copy/Share actions, and Note (Phase C).

Verified end-to-end on a running emulator (long-press select, non-contiguous multi-select,
highlight/recolor/remove, cancel, persistence across a full app restart) rather than by compile
success alone.

### Phase C — Notes UI — ✅ Done (`95ba129`)

`VerseSelectionToolbar`'s Note action opens `NoteEditorSheet`: removable chips for selected verse
refs (an `InputChip`'s whole tap area removes it — Material3 gives one click slot per chip, not a
separately-clickable trailing icon — and the X only shows once more than one verse remains, since
a note needs at least one), a multiline text field, `Save`/`Delete` (delete only when editing an
existing note). Writes to `createNote`/`updateNote`/`deleteNote`.

A noted verse gets an inline glyph right after its verse number — a real `Icon` via
`InlineTextContent` in the `AnnotatedString` (not a gambled-on emoji glyph), with its own tracked
character range checked in the same unified tap-to-offset handler Phase B built for footnotes and
verse taps (checked ahead of a plain verse tap, same reasoning as the footnote-marker check).
Tapping it opens that note's editor directly, pre-filled (verses + text both editable, per
contract decision 14).

Verified end-to-end on a running emulator: attach a note to a selection, see the glyph render,
reopen and edit an existing note's text, delete it, and persistence across a full app restart.

### Phase D — Library screen — ✅ Done (`88993d0`)

One screen (`Screen.Library` route, pushed without the bottom bar — same pattern as
`BookPicker`/`VersePicker`), with a `SingleChoiceSegmentedButtonRow` switching between Highlights
and Notes, rather than two separate screens/routes. Reads from `BibleViewModel.libraryHighlights`/
`libraryNotes`, populated by `loadLibrary()` (`getAllActiveHighlights`/`getAllActiveNotes`) when
the screen opens — fully usable and demoable before any sync code exists. Card list (solid color
swatch or note-icon, `formatVerseRefs` reference, `formatDisplayDate` timestamp, note preview
truncated to 2 lines), `SwipeToDismissBox` swipe-to-delete, tap-to-jump (`setBook` + navigate to
`Screen.Bible`), search filtering by resolved book name (highlights) or book name/note text
(notes). A minimal "My Library" row was added to `SettingsView` as the entry point, ahead of the
fuller Profile hub redesign in Phase G.

One real bug caught during testing: highlight swatches initially rendered at their stored 35%
text-overlay alpha, so every color looked like the same washed-out gray in the list — fixed to
render the swatch dot at full opacity (a color *legend* needs to be distinguishable; the
translucency is only needed where it overlays actual verse text in `BibleText`).

Verified end-to-end on a running emulator: both tabs render real data (including items left over
from earlier phase testing, confirming cross-chapter aggregation), swipe-to-delete removes a card
and survives a restart, and tapping a card navigates to and correctly highlights the right verse.

### Phase E — Sync worker — ✅ Done (`f9ad4a3`, `8cd3ce9`)

`SyncWorker` (a `CoroutineWorker`, same shape as `DailyVerseFetchWorker`): when signed in, pushes
every PENDING row via `getPendingHighlights`/`getPendingNotes` (create → POST, edit → PATCH/PUT,
soft-delete → DELETE, or a local purge if it was never pushed to begin with), then pulls
`updatedSince` the last successful pull and upserts by the server's id (`markHighlightPushed`/
`upsertHighlightFromServer`/`deleteHighlightByRemoteId` and the note equivalents in
`BibleDatabaseManager`). Push always runs before pull, so the pull side never needs to compare
timestamps against what this device just pushed. No-op when signed out. `ReadingProgressRepository`
gets a safety-net push (in case the opportunistic push in `BibleViewModel.loadChapter` missed a
connectivity window) plus a pull that updates the stored position only, never the live reading UI.
`SyncScheduler` enqueues a 15-minute periodic job plus an immediate one-off triggered on app start
and right after login/register.

Two real bugs found and fixed during verification, not just "written and assumed correct":

- **Duplicate rows from concurrent runs.** The periodic job's un-delayed first execution can race
  the immediate trigger fired on the same app launch — confirmed on-device, it produced two
  server-side highlights (and, once pulled back, two local rows) for what should have been one,
  since periodic and immediate sync use different WorkManager unique-work names and don't dedupe
  against each other. Fixed with a process-wide `Mutex` in `SyncWorker` serializing every run.
- **"Immediate" sync wasn't immediate.** A plain `OneTimeWorkRequest` is still subject to ordinary
  JobScheduler deferral — a request enqueued right after login sat unrun for over a minute.
  Fixed with `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`.

**Verified against a throwaway test account created via the API directly, not the real signed-in
account** (see the incident note below): local-first CRUD from Phase A, both bugs above and their
fixes, and a full push+pull cycle — content created signed-out shows up on the account after
signing in, and content created "on another device" (via a direct API call while the app is
signed in) pulls into the app with no duplicates. Reading-progress push/pull exercised as part of
the same sync pass; no dedicated multi-device test.

**Incident, worth knowing if you're picking this up later:** testing this phase repeatedly hit the
real signed-in account (`pinoponedaniel@gmail.com`) rather than a test account, because a stale
Ktor bearer token cached from an earlier session masked which account was actually active, and
separately a local Postgres outage (Docker had been stopped) made `/auth/login` 500 in a way that
looked like an app bug at first. Both are now understood and not code issues, but the sequence
pushed throwaway test content (a highlight, a note) to the real account twice before it was caught;
both times it was cleaned up via the app's own Library swipe-to-delete once noticed. Two local rows
from the first incident are now permanently orphaned (marked `SYNCED` with a `remote_id` the
currently-authenticated test account doesn't own, so deleting them via the API 404s per contract
decision 4's ownership-hiding rule) — harmless (soft-deleted, invisible in the UI, never
re-surface) but not purged; not worth the risk of a manual on-device SQLite file replacement to
remove two dead rows. When testing sync by hand again, confirm which account is actually signed in
(the Settings screen shows the email) before creating throwaway content.

### Phase F — Auth UX polish — ✅ Done (`ef4a57e`)

Most of this list turned out to already exist from the original auth build — this phase mainly
closed real gaps and fixed one live bug found during Phase E's debugging:

- **Inline as-you-type validation** — new. `utils/AuthValidation.kt` mirrors the backend's own
  `EMAIL_REGEX`/`MIN_PASSWORD_LENGTH` (server/routes/AuthRoutes.kt) so `LoginView`/`RegisterView`
  show a field-level error (red border + `supportingText`) and disable the submit button the
  moment something's invalid, not just after a round trip. `RegisterView` also validates
  confirm-password match live.
- **Generic error for bad login** — already correct, confirmed not changed:
  `AuthError.InvalidCredentials.displayMessage` is the same "Invalid email or password" whether
  the email doesn't exist or the password is wrong (contract decision 4).
- **Disable submit + spinner while in flight** — already implemented in both screens (`enabled =
  !isLoading`, `CircularProgressIndicator` swapped in for the button label).
- **Password visibility toggle** — new, both `LoginView` and `RegisterView` (the confirm-password
  field follows the same toggle rather than getting its own).
- **Silent token refresh / graceful drop to signed-out** — the refresh mechanism itself
  (`HttpClientProvider`'s Auth plugin) already existed and already clears `TokenStore` on a failed
  refresh. What was missing, and is now fixed: `AuthViewModel.refreshCurrentUser()` left
  `_isLoggedIn` stuck `true` after that happened, so Settings kept showing "Signed in" with no
  resolvable email — this is the exact bug hit live during Phase E testing. Fixed by re-reading
  `authRepository.isLoggedIn` on that failure path.
- **No "Forgot password" link** — confirmed already absent from both screens; nothing to do.
- **Contextual sign-in nudge** — new. `components/SignInNudgeBanner.kt`, shown once per app
  session after the first highlight or note created while signed out
  (`BibleViewModel.maybeOfferSignIn`/`showSignInNudge`), dismissible, and cleared automatically on
  a successful sign-in from any entry point (not just the banner's own button).

Verified on a running emulator: field-level validation and the visibility toggle on both screens,
the nudge appearing once and staying dismissed through a second highlight the same session, and
no stale "Signed in" state across sign-out/sign-in cycles.

### Phase G — "More" → Profile hub redesign

Restructured `screens/SettingsView.kt` into sectioned rows instead of adding a 5th bottom-nav tab
(Home/Bible/Search stay the daily-use tabs; Library/Account are lower-frequency):

- **Header** (`ProfileHeader`) — avatar-initial circle (first letter of the signed-in email, "?"
  signed out), email or "Not signed in", and a sync status line: "Sign in to sync across devices"
  signed out, else "Syncing…" / "Synced `<relative time>`" / "Not synced yet". New
  `formatRelativeSyncTime()` in `utils/TimeUtils.kt` renders "just now"/"5m ago"/"3h ago", falling
  back to the existing `formatDisplayDate()` past a day.
- **My Library** — `Highlights` and `Notes` rows, both opening `Screen.Library` pre-selected to
  that tab. `Screen.Library`'s route grew a `{tab}` path arg (`createRoute(tab)`,
  `TAB_HIGHLIGHTS`/`TAB_NOTES` constants) and `LibraryView` takes an `initialTab` param consumed
  once via `rememberSaveable { mutableStateOf(LibraryTab.fromRouteArg(initialTab)) }`.
- **Reading** — a "Continue reading" row showing the current book/chapter
  (`bibleViewModel.currentBookName`/`currentChapter`), tapping through to `Screen.Bible`.
- **Sync** (signed in only; signed out shows a one-line sign-in pitch instead) — Auto-sync and
  Wi-Fi-only toggles, a status line, and a manual "Sync now". Toggling either preference calls
  `bibleViewModel.setAutoSyncEnabled`/`setWifiOnlySync` (persists + updates a `StateFlow`) **and**
  `SyncScheduler.schedulePeriodicSync(context)` right there in the composable, since the ViewModel
  has no `Context` to reschedule `WorkManager` itself. `SyncScheduler` now reads both preferences
  itself on every call (`currentConstraints()`, `isAutoSyncEnabled()`) rather than taking them as
  parameters, so every enqueue site — app startup, post-login, this screen — automatically respects
  whatever was last chosen. "Sync now" calls `bibleViewModel.awaitManualSync()` (a `delay(500)`
  polling loop watching `loadLastSyncCompletedAt()` for up to 10s, driving a spinner) alongside
  `SyncScheduler.triggerImmediateSync(context)`. `SyncWorker` stamps
  `bibleRepository.saveLastSyncCompletedAt(isoTimestampNow())` only in the fully-successful branch
  of `doWork()`, so the status line never claims a sync that a mid-pass failure actually left
  PENDING.
- **Notifications** — unchanged from Phase D/F.
- **Account** — Sign out, Delete Account (existing dialog, unchanged); the email/sync-status
  display that used to live here moved to the header, so this section is action-only now.
- **About** — existing, unchanged.

Verified live on a running emulator against a fresh throwaway account
(`phaseg_test@example.com` — not the real signed-in account, consistent with the Phase E incident
lesson below): both Library rows land on the correct pre-selected tab; toggling Wi-Fi-only flips
the scheduled job's network capability from `INTERNET&TRUSTED&VALIDATED&...` to
`NOT_METERED&INTERNET&TRUSTED&VALIDATED&...` immediately, confirmed via
`dumpsys jobscheduler`; "Sync now" flips the status line from "Not synced yet" to "Synced just
now" within a couple seconds; the header correctly shows the avatar initial, email, and sync
status in both signed-in and signed-out states.

### Phase H — Edge cases — ✅ Done (`b9e08a0`)

Scoped down to what the earlier phases had actually left open, rather than building speculative
UI for things that turned out to already be handled or to not need a UI at all:

- **Empty states** — already fully covered before this phase started: `LibraryView.kt`
  (Phase D) has `EmptyLibraryState` for both "nothing saved yet" and "nothing matches this
  search" on each tab, and `SearchView.kt` has its own equivalent pair. Nothing to add.
- **Conflicts** — not a UI concern by design, not something this phase built a banner for. The
  architecture decision (see above) already made this server-owned last-write-wins, invisible to
  the client on purpose — inventing a conflict-resolution UI would contradict that decision, not
  complete it.
- **Offline banner** — Settings' Sync section now distinguishes three states that used to all
  render as the same "Not synced yet": actually offline, a sync that failed after exhausting
  retries, and genuinely never having synced. Offline is checked via `NetworkUtils.isOnline()` on
  `ON_RESUME` (the same pattern the Notifications section already used for its
  battery-optimization check) rather than a live `NetworkCallback` — a connectivity flip while the
  screen is already open just catches up next time you look at it, which is an acceptable
  tradeoff for a status line, not something worth a persistent registered callback. Offline hides
  "Sync now" (tapping it would just enqueue a job that sits unrun until `WorkManager`'s
  `CONNECTED` constraint clears, with no visible feedback either way) rather than leaving it live.
- **Retry affordance for failed syncs** — a new `lastSyncFailed` flag (`BibleRepository`,
  persisted, cleared on the next fully-successful pass) is set by `SyncWorker` only once retries
  are exhausted (see `MAX_RETRY_ATTEMPTS`), so it never flags a transient hiccup a plain retry
  would've quietly recovered from. Settings shows it as "Sync failed" in error styling with a
  "Retry" button — which is just the same "Sync now" action under a different label, since manual
  retry (already built in Phase G) *is* the correct retry affordance; no separate mechanism was
  needed.
- **Delete-account cascade — the one actual bug found here.** Deleting an account left every
  local highlight/note pointing at a `remote_id` that no longer resolved to anything server-side,
  so every future sync pass would silently 404 retrying it forever — precisely the orphaned-row
  problem Phase E's incident notes already describe, but guaranteed to recur on every deletion
  since it invalidates every `remote_id` at once rather than just the couple of rows Phase E hit
  by chance. `BibleRepository.detachLocalContentFromDeletedAccount()` (called from Settings'
  delete-account confirmation on success) now purges any already-pending tombstone outright and
  resets everything else to local-only PENDING content — kept on the device, detached from the
  dead account, ready to push as a fresh create the next time the user signs into any account.
  The confirmation dialog's copy was inaccurate about this before (it implied local copies were
  deleted along with the account) and now says plainly that they stay on the device.
- **Also added**: per-item push-failure logging in `SyncWorker` (`logPushFailure`). A single
  item's push failure is deliberately swallowed by design so it doesn't fail the whole pass or
  block everything queued behind it — but that also made it completely invisible, even in
  logcat, which is exactly what got in the way of diagnosing the delete-account bug above while
  building this phase. Doesn't change the retry behavior, just makes the next "why won't this
  ever sync" investigation possible without re-deriving this instrumentation from scratch.

Verified live on-device against two throwaway accounts (never the real signed-in one): disabled
Wi-Fi/data via `adb shell svc wifi disable` / `svc data disable` and confirmed the offline banner
appeared and "Sync now" disappeared, then re-enabled both and confirmed it reverted to "Synced
just now"; deleted an account that had 4 highlights and 1 note, pulled the local SQLite DB via
`run-as` + `sqlite3` and confirmed all 5 rows had `remote_id` cleared and `sync_status` reset to
`PENDING`; registered a second fresh account, tapped "Sync now", and confirmed via the same
DB-pull technique that all 5 rows pushed cleanly and now hold fresh server `remote_id`s with
`sync_status = SYNCED` — no orphaned-404 recurrence.

This closes out the roadmap's originally planned phases (A through H). Anything past this point
is new work, not a gap in what was scoped here.

---

## Open items

- Curated highlight color palette (exact swatches) — TBD when Phase B starts, pull from
  `ui/theme/Color.kt`.
- Whether a "Sign in to back this up" nudge (Phase F) is a one-time dismissible banner or persists
  until signed in.
