# TreasuredWord notes for the issue loop

Read by the `issue-loop` skill (the developer's own skill in `~/.claude/skills/issue-loop/`)
in every session of the loop. `CLAUDE.md` has the full rules; these are the parts the loop
needs most, plus what earlier runs taught.

## Skip without asking

- Trackers and checklists: #120 (Bible Helper), #131 (its release checklist), release issues
  like #299.
- Play Console or store listing work, such as #338: only the developer can do it.
- Bible Helper changes whose PR is a draft waiting for the developer's blind grading of an
  evaluation run (#359 for #297). Switching an `AI_*` flag on in Render also needs their
  go-ahead.

- #368 (the new logo): the developer wants it done last, with them, outside the loop.
- #363 (practice games) while it's labelled `question`: the developer is still adding design detail.
- #366 (licensed Bible versions) until its Step 1 is done: it needs the developer's YouVersion App Key.

## Branches, PRs, merging

- `fix/<topic>` or `feature/<topic>`, `Closes #N`, merge with a **merge commit**.
- `main` has **no branch protection**, so never enable auto-merge: it would merge at once.
  Wait for CI with `gh run watch` in the foreground.
- Server CI (`.github/workflows/server-ci.yml`) runs on PRs that touch `server/`, and deploys to
  Render from `main` after its tests pass. Docs-only and `.claude/` PRs run no checks.

## Testing

- App: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` (background).
- Server: `./gradlew :server:test --tests "*Name*"` for tests that don't need the database. The
  full suite needs Postgres 18 + pgvector. The local Postgres is 11 and Docker is usually off,
  so let Server CI run it.
- UI: the emulator only (`Pixel_9_Pro_XL`, `adb -s emulator-5554`), every color theme in light
  and dark, portrait and landscape, a translated language, offline when it uses the network. See
  `CLAUDE.md` for switching themes and the S23 rules. Stop it afterwards
  (`adb -s emulator-5554 emu kill`).

## After a server deploy

- Health: `curl https://treasuredword.onrender.com/health`. A cold start takes 30–50 s.
- If the developer is signed in to Render in the browser pane: dashboard.render.com →
  TreasuredWord → treasuredword → Logs, then search (for example `Topic guides`). Only read;
  never change settings there.
- On the free instance, the first request to Cloudflare after a start takes ~8 s (#341).
