# BibleApp

An Android Bible reader (Kotlin, Jetpack Compose, Material3) with a bundled King James
Version and on-demand downloads of other translations for fully offline reading.

## Project status

🚧 **Work in progress — not feature-complete.** The reader (bundled KJV, on-demand
translation downloads, full-text search, footnotes, poem/heading formatting) and user
accounts/sync (highlights, notes, reading progress, kept in sync across devices by a
deployed [Ktor](https://ktor.io) backend — see [Account sync](#account-sync) below) both
work today. Still ahead:

- **Bible chatbot** — an in-app assistant for answering Bible-related questions.

## Screenshots

<table>
  <tr>
    <td><img src="docs/screenshots/home.png" width="200" alt="Home screen"></td>
    <td><img src="docs/screenshots/reading.png" width="200" alt="Reading screen"></td>
    <td><img src="docs/screenshots/version-picker.png" width="200" alt="Version picker"></td>
  </tr>
</table>

## Architecture at a glance

```
UI (screens/, components/)
        v
BibleViewModel                 -- exposes state as StateFlow, owns no I/O itself
        v
BibleRepository                -- the one place that decides local vs. remote
        v              v
BibleDatabaseManager   BibleRemoteDataSource (interface)
   (SQLite, local)            v
                        HelloAoBibleDataSource (bible.helloao.org)
```

`BibleRepository` is the seam: the ViewModel never talks to SQLite or the network
directly, and `BibleRemoteDataSource` is an interface specifically so the backing
API can be swapped (or a second source added) without touching the ViewModel, the
UI, or the local schema.

## Data source: the Free Use Bible API

Translation downloads come from [bible.helloao.org](https://bible.helloao.org), a
real REST API. This replaced an earlier approach that fetched pre-rendered per-chapter
JSON files from a `wldeh`-maintained Bible-JSON GitHub repo via the jsDelivr CDN — that
had no request budget of its own to reason about (a single translation download was
~1,189 separate chapter requests), and at that volume jsDelivr's shared rate limiting
started returning 403s that looked like arbitrary chapter failures rather than what
they actually were: too many requests, too fast, against someone else's shared
infrastructure.

helloao's `GET /api/{translationId}/complete.json` returns an entire translation —
every book, chapter, and verse — in one response (typically 5-10MB). That turns a
download from "~1,189 requests that can each fail independently" into "one request
that either succeeds or fails," which removes the failure mode outright rather than
adding retries around it. [`HelloAoBibleDataSource`](app/src/main/java/com/application/bibleapp/data/remote/HelloAoBibleDataSource.kt)
also calls `GET /api/available_translations.json` once, to populate the version
picker's catalog. `books.json` and the per-chapter endpoint exist on the API but
aren't used — `complete.json` already contains everything they'd return.

## Download flow

1. `HelloAoBibleDataSource.downloadTranslation` fetches `complete.json`, streaming the
   response body in chunks to report real progress (`onProgress`) as the multi-MB file
   arrives, then decodes it in one pass. Transient network failures are retried with
   backoff; a 404 (translation genuinely doesn't exist) is not.
2. [`HelloAoContentParser`](app/src/main/java/com/application/bibleapp/data/remote/HelloAoContentParser.kt)
   walks each chapter's heterogeneous `content[]` array (plain strings, poem/word-of-Jesus
   objects, headings, footnote markers) into plain text plus a structured `StoredVerseContent`.
   It's pure Kotlin — no Android dependency — so it's tested as plain JVM unit tests.
3. [`HelloAoDownloadMapper`](app/src/main/java/com/application/bibleapp/data/remote/HelloAoDownloadMapper.kt)
   maps the API's book ordering onto the app's internal 1-66 canonical numbering,
   skipping anything outside that range (e.g. apocrypha) rather than treating it as an error.
4. [`BibleDatabaseManager.downloadAndSaveVersion`](app/src/main/java/com/application/bibleapp/data/local/BibleDatabaseManager.kt)
   writes everything in **one batched SQLite transaction**, opened only after all
   network I/O and parsing has already finished.

That ordering is the fix for the crash this whole rewrite started from: `SQLiteDatabase`
ties an open transaction to the thread that opened it. `Dispatchers.IO` is free to resume
a suspended coroutine on a *different* pool thread, so awaiting a network call while a
transaction was open would intermittently move execution to a thread with no record of
that transaction — throwing `"Cannot perform this operation because there is no current
transaction"` and, since the connection stayed checked out the whole time, blocking every
other caller (including the main thread) until it failed. The rule this taught us:
**never suspend on I/O while holding a database transaction** — separate the async fetch
phase from the synchronous write phase completely.

## Local storage model

The bundled King James Version ships as a read-only asset database (`KJV_verses`) and is
never modified. Every downloaded translation lives in three separate tables instead:

| Table | Purpose |
|---|---|
| `downloaded_versions` | One row per downloaded translation: id, display name, download timestamp, and `schema_version` (see below). |
| `downloaded_verses` | `text` (always plain, for search) plus a nullable `rich_content` JSON column (for rendering red-letter text, poems, headings, footnote markers). |
| `downloaded_footnotes` | Footnote text, keyed by version/book/chapter/`note_id` — looked up separately at render time rather than duplicated onto every verse that references one. |

**Why red-letter/poem/heading data is a new nullable column but footnotes needed a whole
new table:** a verse's red-letter/poem/heading data is *about that one verse* — it fits
naturally as one more column on the existing `downloaded_verses` row, and a `NULL`
`rich_content` just means "render as plain text," which is exactly what pre-existing rows
already were. A footnote, though, isn't about one verse — it's chapter-wide text that
multiple verses can reference by `note_id`, so it needed its own table with its own key
rather than being duplicated onto every referencing verse.

`text` deliberately stays plain (never JSON) so full-text search's
`GROUP_CONCAT(text, ' ')`/`LIKE` query keeps working unchanged — only the reading screen
ever looks at `rich_content`. New columns/tables are added idempotently (`PRAGMA
table_info` + conditional `ALTER TABLE`) rather than through a versioned migration
framework, since there's no ordered history to replay — just "does this column exist yet."

### Version picker state

The picker shows three states per translation, all sourced from local storage rather
than kept in memory only:
- **Active** — matches `BibleViewModel.selectedVersion`, which is persisted to
  `SharedPreferences` and restored on launch (falling back to the bundled KJV if that
  version is no longer actually downloaded — e.g. after clearing app data).
- **Downloaded** — the translation has a row in `downloaded_versions`, queried once per
  version-list load (not once per row) and refreshed after every download.
- **Update available** — downloaded, but its `schema_version` is behind the current one
  (e.g. it predates footnote support). The only way to backfill that is a full re-download,
  since nothing auto-migrates already-stored rows.

Tapping an already-downloaded, non-active version switches instantly with no network
call; tapping the already-active version is a no-op.

## Account sync

Highlights, notes, and reading progress are **local-first**: they save to local SQLite
instantly and work fully offline or signed out — signing in turns on *cross-device sync*,
not the feature itself. See `docs/UI_Integration_Roadmap.md` for the phase-by-phase build
log (auth UX, the sync worker, a Profile hub, offline/conflict edge cases — all done as of
Phase H) and `docs/api_contract.md` for the wire contract this UI talks to.

**Backend** — a Kotlin/[Ktor](https://ktor.io) service (`server/`) on Exposed ORM over
PostgreSQL, ships JWT access/refresh-token auth (bcrypt-hashed passwords), Flyway-managed
schema migrations, and per-route rate limiting. It's deployed on [Render](https://render.com)
against a [Neon](https://neon.tech) Postgres instance (see `server/DEPLOY.md`), and the app
(`HttpClientProvider.BASE_URL`) talks to that live instance, not a local dev server —
verified by registering a real account through the app's own UI against production, not
just `curl`.

**Sync worker** — [`SyncWorker`](app/src/main/java/com/application/bibleapp/worker/SyncWorker.kt)
reconciles the local highlight/note/reading-progress tables with the backend: it pushes every
locally-pending create/edit/delete first, then pulls anything changed server-side since the
last successful pull, upserting by the server's id so a pull from another device merges
cleanly with this one. Push always completes before pull, so the pull side never needs to
compare local/server timestamps — ordering on write is the server's job (last-write-wins,
per the contract). A [`Mutex`](app/src/main/java/com/application/bibleapp/worker/SyncWorker.kt)
serializes runs against each other: `SyncScheduler` enqueues both a periodic sync and an
immediate one-off under different WorkManager work names (so WorkManager itself won't dedupe
them), and without the lock a periodic run's first execution could race an immediate trigger
fired on the same app launch — confirmed on-device, it double-pushed the same pending row into
two server-side highlights. Sign-out and network failures surface as banners in the UI
(Phase H) rather than silently dropping local changes.

## Testing

Parsing/mapping logic (`HelloAoContentParser`, `HelloAoDownloadMapper`, DTO decoding,
`StoredVerseContent` JSON round-tripping) is pure Kotlin and covered by JVM unit tests
under `app/src/test`. Android-Context-dependent code (SQLite, the ViewModel, Compose UI)
is verified manually on-device rather than through Robolectric/instrumented tests, which
aren't set up in this project.
