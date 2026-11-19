---
name: issue-loop
description: Work through TreasuredWord's open GitHub issues one at a time, in the recommended order. For each one, implement and test it, merge its PR, do house cleaning, then start a new session for the next issue. Use only when the developer runs /issue-loop or asks for "the issue loop", or when a session brief says to run it.
argument-hint: "[issue numbers to do first, or words like 'ui only' / 'skip 297']"
---

# Issue loop

The developer wants open issues done one after another with as little of their time as possible.
**This session does one issue**, from code to merged PR and house cleaning, and then starts a
new session that runs this skill again for the next issue. Every rule in `CLAUDE.md` still
applies (strings in all nine translations, every theme in light and dark, landscape, emulator
only for experiments, the S23 rules, free services only, plain-language issues and PRs).

Arguments from the developer, if any: `$ARGUMENTS`. They can name issues to do first, issues to
skip, or a limit such as "ui only". A brief from the previous session in the loop may say which
issue it just finished.

## 1. Pick the issue

1. `gh issue list --json number,title,labels` and read the candidates with `gh issue view N`.
2. Order: anything the arguments name, then `priority: high`, then bugs, starting with those
   that hurt production now (server or Bible Helper quality), then UI bugs, then enhancements.
   When it's a tie, take the one that's smaller or that unblocks others.
3. Skip, without asking:
   - trackers, epics and release checklists (#120, #131, release issues like #299);
   - issues only the developer can do: Play Console, store listing, Render/Neon settings,
     secrets, signing in to their accounts (for example #338);
   - issues with an open PR that waits on the developer: a draft waiting for blind grading
     (#359 for #297), or a review they asked for. Mention it in the final report;
   - issues another linked session is working on right now (`list_sessions {"linked": true}`).
4. If nothing workable is left, stop: report what the loop did (each issue, PR link, merge
   hash) and what waits on the developer, and don't start a new session.

## 2. Do it

1. `git checkout main && git pull`, then branch `fix/<topic>` or `feature/<topic>` (in a
   worktree session, work on its branch and name the PR the same way).
2. Implement on both sides as needed (app, `server/`, migrations, CI). Follow the issue's
   proposal. When the issue leaves a choice open and one option is clearly the house style or
   what Android/Material recommends, take it and say so in the PR. Ask the developer
   (`AskUserQuestion`) only for decisions that are really theirs: product behavior the issue
   doesn't settle, anything that costs money, anything that touches production data or settings.
3. Test what the issue's **Done when** asks for:
   - `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` in the background;
   - server: the affected tests locally (`:server:test --tests "*Name*"`); the full suite needs
     Postgres 18 + pgvector, so Server CI runs it on the PR if local Docker isn't up;
   - UI: the emulator (`Pixel_9_Pro_XL`, `emulator-5554`) in every color theme, light and dark,
     portrait and landscape, plus a translated language, and offline when the feature uses the
     network. Restore everything you changed on it.
   - If a check can't be done (needs the developer's account, a real sleep on Render), say so
     in the PR's "Still to check" list. Don't pretend it passed.
4. File any other bug you find as its own issue (CLAUDE.md style) instead of fixing it on the side.

## 3. Merge and clean up

1. Commit, push, `gh pr create` with `Closes #N`, the free plan's limits, tests run and what's
   still to check. Then `mcp__ccd_pr__get_status` (bind the PR if it isn't shown).
2. Wait for CI **in the foreground** where you can:
   `gh run watch <id> --exit-status --interval 30` with a 10-minute Bash timeout, repeated if
   needed. This keeps steps 3–4 in the same turn (see "Starting the next session" below).
   `main` has no branch protection, so **never enable auto-merge**: GitHub would merge at once,
   without waiting for CI.
3. If CI fails, fix it, push, and wait again. Merge only on green:
   `gh pr merge N --merge --delete-branch` (a merge commit, never squash).
4. House cleaning: `git checkout main && git pull`, delete the merged branch locally and
   remotely, `git remote prune origin`, stop the emulator if you started it
   (`adb -s emulator-5554 emu kill`). Leave other sessions' worktrees and branches alone.
5. Server changes deploy from Server CI on `main`: wait for its `deploy` job. If the developer
   is signed in to Render in the browser pane, read the service's log
   (`dashboard.render.com` → TreasuredWord → treasuredword → Logs, use its search) to confirm the
   change behaves. Only read; never change settings there. Check the issue closed.
6. Recap in chat what changed, why, the PR link, the branch and the merge hash.

## 4. Start the next session

Call `mcp__ccd_session__start_session` with:

- `initiation`: `"user_asked"` (the developer asked for the loop), `context`: `"fresh"`,
  `use_worktree`: `true`, a `title` naming the next issue if you know it;
- `prompt`: "Run the `issue-loop` skill (Skill tool, skill `issue-loop`). The previous session
  finished #N (PR #P, merge `abc1234`). Arguments: <the same arguments, minus what's done>."
- `background`: one or two sentences on the loop, and anything the next session must know
  (an issue to skip, a decision the developer made).

The app refuses to start a session from a turn that a background task, CI event or another
session's message started. That's why step 3 waits in the foreground. If it's refused anyway,
propose the same session with `initiation: "own_initiative"` and tell the developer in one line
that the next issue waits for their click on that card.

Then end with `mcp__ccd_turn__wrap_up` (recap only, unless something waits on the developer).
