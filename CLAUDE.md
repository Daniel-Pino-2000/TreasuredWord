# TreasuredWord — working rules for Claude

The app is called **TreasuredWord**. "BibleApp" is only the codebase, module and code package
name (`com.application.bibleapp`); use TreasuredWord in anything a user or the public sees, and
leave internal identifiers alone unless asked. The installed app's ID (`applicationId`) is
**`com.treasuredword.app`**, reserved in the Play Console and permanent once uploaded.

- `app/` — Android app, Kotlin + Jetpack Compose + Material3, Room, Ktor client.
- `server/` — Ktor + Exposed + PostgreSQL + Flyway backend for accounts and sync, live at
  https://treasuredword.onrender.com (Render free tier, cold start ~30–50 s; database on Neon).
  Deploy notes: `server/DEPLOY.md`. API contract: `docs/api_contract.md`.
- `docs/` — contracts, roadmaps, and the privacy policy (see Privacy below).

## How to work with the developer

- **Android/UI work (`ui` label): implement directly and fast**, like a SWE using Claude Code.
  Show what changed and why, but don't turn it into a lesson unless asked.
- **Backend work in `server/` (`backend` label): guide, don't author.** The developer is learning
  backend by doing it. Break work into small testable steps, explain the concept, point to the
  file and line, suggest the test first, and let them write the code and run the commands —
  including setup and debugging. Read-only checks to confirm a fact are fine. Only implement
  backend code yourself when they explicitly say so for that specific task; that never becomes a
  standing change. For CI/test-config chores, offer them the choice.
- If it's unclear which side a change belongs to (a DTO shared by both), ask.
- After every commit, recap in chat what changed, why, and the commit hash/branch — not just in
  the git message.

## Issues, branches, PRs

- Work is tracked in GitHub Issues on `Daniel-Pino-2000/TreasuredWord`. Start with
  `gh issue list`, `priority: high` first. Labels: `ui`, `backend`, `notes`, `highlights`,
  `landscape`, `priority: high`, plus the defaults.
- File every bug you find as an issue instead of only mentioning it: title, cause, proposal, and a
  **Done when** checklist (see #46–#48 and #55 for the style). Write issues and PRs in plain
  language.
- Never commit to `main`. Branch as `feature/<topic>` or `fix/<topic>`, open a PR with
  `Closes #N` for each issue, and merge with a merge commit.
- The repo is **public**: no secrets, tokens, device serials or personal data in code, issues,
  PRs or commit messages.

## Android conventions

- **Strings:** every user-facing string goes in `res/values/strings.xml` **and** all nine
  translations: `values-de`, `values-es`, `values-fr`, `values-pt`, `values-in`, `values-b+fil`,
  `values-sw`, `values-ko`, `values-b+zh+Hans`. Escape apostrophes (`\'`), and comment what each
  `%1$s`/`%1$d` placeholder is. Debug builds include pseudo-locales to catch hard-coded strings.
- **Messages from outside the UI** use `UiText` (resolved when shown, so they follow a language
  change). Snackbars go through `bibleViewModel.showMessage(...)`.
- **Bottom sheets** (`ModalBottomSheet`) are their own window, drawn over the screen's snackbar:
  a sheet that can fail (offline, etc.) needs its own `SnackbarHost`, as in
  `components/AboutBookSheet.kt`. Follow the existing sheets' pattern:
  `skipPartiallyExpanded = true`, `statusBarsPadding()`, `navigationBarsPadding()`.
- **Theme:** the app has its own Light/Dark/System setting. Inside `BibleAppTheme`,
  `isSystemInDarkTheme()` returns the app's choice, not the phone's (#24). Use
  `MaterialTheme.colorScheme`, `MaterialTheme.shapes` and the `Spacing` tokens, never raw colors
  or dp values for spacing.
- **Landscape:** phones in landscape are "compact height" (`isCompactHeight()`): tabs move to a
  side rail and the reader's chapter controls move into the top bar. Every UI change must work
  there too.
- **Outside links** (web pages, videos) go only through `utils/ExternalLinks.kt`
  (`rememberExternalLinks`): https only, a Chrome Custom Tab in the app's theme, YouTube in its
  app, offline and no-browser handled. Never a WebView.
- **Third-party content is link-only.** Never copy, scrape, cache or re-host GotQuestions.org or
  BibleProject content, and never imply a partnership. Always name the source next to the link.
  BibleProject videos must stay free (no paywall, ads or "premium" near them) and carry the
  "author and owner" credit linking to bibleproject.com.
- **Hand-checked data** (`data/model/BookResources.kt`): verify every URL or video ID before
  adding it (curl the page; check YouTube oEmbed's `author_url`), note the date checked, and keep
  unit tests offline.
- Comments explain *why* and cite issue numbers (`(#24)`), matching the surrounding density.

## Testing

- Commands: `./gradlew :app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`,
  `:app:installDebug`, and `:server:test` (needs a local Postgres; see
  `.github/workflows/server-ci.yml` for the env vars). Run long Gradle tasks in the background.
- Check UI changes on a device in **light and dark, portrait and landscape**, plus a translated
  language and **offline** when the feature touches the network.
- **The physical Galaxy S23 is the developer's personal phone.** Before *every* tap, check
  `adb shell dumpsys window | grep mCurrentFocus` shows `com.treasuredword.app`; if not, stop
  and tell them. Never change its system settings. Only create test highlights/notes you remove
  afterwards, and confirm a sheet's title with `uiautomator dump` before tapping Remove/Delete so
  real data is never touched.
- **Landscape, dark-mode and settings experiments happen on the emulator only** (AVD
  `Pixel_9_Pro_XL`, `adb -s emulator-5554`). To match the S23: `wm size 1080x2340` and
  `wm density 480`. Restore everything you change afterwards (`wm size reset`,
  `wm density reset`, rotation, `cmd uimode night no`, app locale, disabled apps).
- `adb exec-out uiautomator dump /dev/tty` finds tap targets by text. In Git Bash, set
  `MSYS_NO_PATHCONV=1` for adb commands with `/sdcard` paths.

## Privacy

- The privacy policy is `docs/privacy-policy.md`, published by GitHub Pages (branch `main`,
  folder `/docs`, limited by `docs/_config.yml`) at
  https://daniel-pino-2000.github.io/TreasuredWord/privacy/ — the URL in Google Play.
- **Whenever the app starts sending data somewhere new** (a new API, analytics, the planned AI
  chatbot), update the policy and its effective date in the same PR, and tell the developer the
  Play Console **Data safety** answers need updating too.

## Backend gotchas

- Flyway migrations live in `server/src/main/resources/db/migration/`; never edit an applied one.
- `shadowJar` needs `mergeServiceFiles()`: `flyway-core` and `flyway-database-postgresql` ship the
  same `META-INF/services` file, and without merging, migrations silently no-op in the deployed
  jar. After adding a dependency, check `jar tf server/build/libs/server-all.jar | grep META-INF/services`.
