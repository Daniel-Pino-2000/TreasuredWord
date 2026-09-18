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
| C | Notes UI | Next up |
| D | Library screen (saved highlights/notes) | Not started |
| E | Sync worker (local ↔ backend) | Not started |
| F | Auth UX polish | Not started |
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

### Phase C — Notes UI

`Note` toolbar action opens a bottom sheet: removable chips for selected verse refs, multiline
text field, `Save`/`Cancel`, writing to `insertNote`/`updateNote`. Verses with a note get an inline
glyph; tapping it opens that note's editor directly (verses + text both editable, per contract).

**Done when:** you can attach a note to a selection, see the inline glyph on saved verses, reopen
and edit an existing note's text and verse list, and delete it — offline and signed out.

### Phase D — Library screen

One screen (new `Screen.Library` route, pushed without the bottom bar — same pattern as
`BookPicker`/`VersePicker`), with a segmented control at the top switching between Highlights and
Notes, rather than two separate screens/routes. Reads straight from
`getAllActiveHighlights`/`getAllActiveNotes` — fully usable and demoable before any sync code
exists. Card list (color swatch or note preview, verse ref, timestamp), swipe-to-delete,
tap-to-jump-to-passage, search/filter by book or note text.

**Done when:** both tabs render real local data, deleting a card removes it (soft-delete, so it's
gone from the list but still queued to push once Phase E exists), and tapping a card navigates to
that verse in `BibleView`.

### Phase E — Sync worker

A `WorkManager` job (same shape as `DailyVerseFetchWorker`): when signed in, pushes
`getPendingHighlights`/`getPendingNotes` via the existing repositories, pulls `updatedSince` from
the server, reconciles (last-write-wins per contract decision 18), marks rows `SYNCED`. No-op when
signed out. Also wires `ReadingProgressRepository` to the existing `saveReadingPosition`/
`loadReadingPosition` in `BibleRepository` (already persisted locally via `SharedPreferences` —
just needs a `readAt` timestamp and a push/pull on this same worker, no new local table needed).

**Done when:** a highlight/note created signed-out shows up on a second device after signing in
there, a change made on two devices while offline resolves without crashing (last-write-wins, no
duplicate rows), and reading position picks up where another device left off.

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
