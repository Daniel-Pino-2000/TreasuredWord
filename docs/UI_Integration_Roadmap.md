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
| F | Auth UX polish | Next up |
| G | "More" → Profile hub redesign | Not started |
| H | Edge cases (empty states, conflicts, retries) | Not started |

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

### Phase F — Auth UX polish

- Inline validation as-you-type (email format, password strength), not just on submit.
- Generic error banner for bad login, confirming the UI never reveals which field was wrong
  (matches contract decision 4's deliberate `INVALID_CREDENTIALS` ambiguity).
- Disable submit + spinner while in flight, to prevent double-submits.
- Password visibility toggle.
- Silent token refresh on app start via `TokenStore`; graceful drop to signed-out state on
  refresh failure.
- No "Forgot password" link yet — deferred in the backend contract (no email provider wired up).
  Omit rather than ship a dead end.
- Sign-in prompted contextually (e.g. "Sign in to back this up" the first time someone highlights
  while signed out), not as a forced wall — consistent with local-first.

### Phase G — "More" → Profile hub redesign

Restructure `screens/SettingsView.kt` into sectioned rows instead of adding a 5th bottom-nav tab
(Home/Bible/Search stay the daily-use tabs; Library/Account are lower-frequency):

- **Header** — signed in: avatar-initial circle, email, sync status (`Synced · 2m ago` / spinner /
  offline). Signed out: "Sign in to sync across devices" CTA, not a gate.
- **My Library** — `Highlights` and `Notes` rows, both opening the Phase D `Screen.Library` route
  pre-selected to that tab.
- **Reading** — continue-reading shortcut, reading-progress display.
- **Preferences** — existing Appearance/Text Size/Notifications, plus new **Sync** section:
  Wi-Fi-only toggle, auto-sync toggle, manual "Sync now" (these are UI-only until Phase E's worker
  reads them — e.g. a `NetworkType` constraint on the `WorkManager` job for the Wi-Fi-only toggle;
  don't ship this section before Phase E respects it, or the toggles will be dead switches).
- **Account** — Sign out, Delete Account (existing dialog, unchanged).
- **About** — existing, unchanged.

### Phase H — Edge cases

Empty states, conflict/offline banners, retry affordances for failed syncs, polish on the
delete-account cascade confirmation (already partly built).

---

## Open items

- Curated highlight color palette (exact swatches) — TBD when Phase B starts, pull from
  `ui/theme/Color.kt`.
- Whether a "Sign in to back this up" nudge (Phase F) is a one-time dismissible banner or persists
  until signed in.
