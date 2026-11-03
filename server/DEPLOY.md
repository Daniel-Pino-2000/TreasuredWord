# Deploying the server (Phase 5)

Stack: [Render](https://render.com) runs the Ktor app as a Docker container (free web service
tier, no payment method required at all — this is why it was picked over Fly.io, which now
requires a card on file even for its free allowance); [Neon](https://neon.tech) is the production
Postgres. See the root `Dockerfile`/`render.yaml` for the actual build/run config.

Tradeoff worth knowing up front: Render's free tier spins the service down after 15 minutes with
no traffic. The first request after that takes ~30–50s to wake it back up; everything after is
normal speed. That's the cost of "genuinely cannot be billed."

## One-time setup

1. **Create a Render account** at [render.com](https://render.com) — GitHub login is fastest, and
   no payment method is asked for on the free plan. Claude can't create this account for you.
2. **Connect your GitHub account/repo to Render** — during account creation (or later, under
   Account Settings → GitHub), authorize Render's GitHub App and give it access to this repo
   (either all repos, or just this one — your choice).
3. **Create the service from the committed Blueprint**:
   - In the Render dashboard: **New +** → **Blueprint**.
   - Pick this repo. Render will detect `render.yaml` at the repo root automatically.
   - It shows the one service (`treasuredword`) the blueprint defines and asks you to fill in
     the env vars marked `sync: false` — `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`,
     `JWT_SECRET`. See "Every deploy" below for what values to use; you can also leave them blank
     here and fill them in afterward under the service's **Environment** tab.
   - Click **Apply**. Render clones the repo, builds `Dockerfile`, and deploys.

## Every deploy

Deploys start from GitHub Actions, not from Render itself: when a push to `main` touches the
server, `.github/workflows/server-ci.yml` runs the server tests and, only if they pass, its
`deploy` job calls Render's deploy hook for that exact commit. Render's own auto-deploy is
**Off**. It was on, but no push to `main` ever triggered it between 21 September and
27 October 2026, although the GitHub App had access to the repo (#116). Deploying from CI also
means a commit whose tests fail never reaches production. Everything below is either one-time or
only needed when a secret changes.

1. **Set the secrets**, in the Render dashboard under the service → **Environment**:
   - `DATABASE_URL` — `jdbc:postgresql://<neon-host>/<db>?sslmode=require`. Use Neon's
     **non-pooled** host (no `-pooler` in the hostname) — confirmed during Phase 5 testing that
     this avoids PgBouncer-related quirks with Flyway running DDL on every startup.
   - `DATABASE_USER` / `DATABASE_PASSWORD` — from the same Neon connection string.
   - `JWT_SECRET` — a real random value (e.g. generate one locally with
     `openssl rand -base64 48`), never the `application.conf` dev placeholder.
   - `GOOGLE_CLIENT_ID` — the Google Cloud **web** OAuth client ID for "Continue with Google"
     (#112), the same value as the app's `res/values/google_sign_in.xml`. Not a secret. Without
     it the server still runs, and `/auth/google` answers `GOOGLE_SIGN_IN_UNAVAILABLE`.
   - `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`, `GMAIL_REFRESH_TOKEN` — how the server sends
     email (verification and password-reset codes, #154) through the Gmail API, from
     TreasuredWord's own Gmail account. Free. The secret and the refresh token are secrets: only
     ever here, never in the repo, an issue or chat. How to get them: **Email: the Gmail
     sender** below.
   - `EMAIL_FROM` — that Gmail account with a name, `TreasuredWord <name@gmail.com>`. Not a
     secret. Without it or any of the three above, the server still runs and the endpoints that
     send email answer `EMAIL_UNAVAILABLE`.
   - `AI_BASE_URL`, `AI_API_TOKEN`, `AI_MODEL` — the Bible Helper's AI (#124), Cloudflare Workers
     AI on the free plan. `AI_BASE_URL` is
     `https://api.cloudflare.com/client/v4/accounts/<account id>/ai/v1`, with the account ID
     from the Cloudflare dashboard; `AI_API_TOKEN` is the Workers AI token made in #122, a
     secret; `AI_MODEL` is `@cf/google/gemma-4-26b-a4b-it`. Without any of them the server still
     runs and `/ai/ask` answers `AI_UNAVAILABLE`. The free plan's 10,000 neurons a day (about
     320 answers) can't bill: past them Cloudflare refuses with error 3036 and `/ai/ask` answers
     `AI_RESTING` until 00:00 UTC. Each answer's neurons are in the log (`AI provider: HTTP 200
     in … ms, … neurons`), never the question or answer.

   Saving env var changes triggers a redeploy automatically.

2. **Connect CI to Render** (one-time, or again after regenerating the hook):
   - In Render: the service → **Settings** → **Deploy Hook**, copy the URL. It's a secret:
     anyone who has it can deploy, so never put it in code, issues or chat.
   - Store it as the repo's Actions secret `RENDER_DEPLOY_HOOK_URL`: run
     `gh secret set RENDER_DEPLOY_HOOK_URL` and paste the URL when asked, or use GitHub → repo
     **Settings** → **Secrets and variables** → **Actions**.
   - In Render: **Settings** → **Auto-Deploy** → **Off**, so a commit isn't deployed twice if
     Render's own trigger ever starts working again.

3. **Deploy** happens by itself when a server change is merged to `main` and its tests pass;
   the `deploy` job in the **Server CI** run shows it. To redeploy without a new commit (after
   changing an env var, or if a deploy failed), use **Actions** → **Server CI** →
   **Run workflow** on `main`, or the Render dashboard's **Manual Deploy** button.

4. **Verify**:
   ```bash
   curl https://treasuredword.onrender.com/health
   ```
   Logs are under the service's **Logs** tab in the dashboard (or `render logs` via the CLI).
   When a release adds a migration, check the log shows Flyway's "Successfully applied" line
   for it before calling the deploy done.

## Email: the Gmail sender (#154)

The server sends codes through the Gmail API, from a Gmail account made just for TreasuredWord.
It's free: Resend, the first choice, delivers to other people only from a domain of our own, and
a domain costs money. SMTP isn't an option either, since Render's free tier blocks SMTP ports.
One-time setup, all in a browser:

1. **Create a Gmail account for the app**, e.g. `treasuredword.app@gmail.com`, not your personal
   one: the codes come from it, and its Sent folder keeps a copy of each. Turn on 2-Step
   Verification for it.
2. **Signed in as that account**, open [Google Cloud Console](https://console.cloud.google.com)
   and create a project, e.g. "TreasuredWord email". No billing account is needed. It's separate
   from the Google sign-in project (#112), so that project's consent screen stays as it is.
3. **APIs & Services → Library → Gmail API → Enable.**
4. **Google Auth Platform** (the OAuth consent screen): type *External*, app name
   "TreasuredWord email", support email the app's Gmail. Then **Audience → Publish app**, so it
   says *In production*. In *Testing*, the refresh token stops working after 7 days. No Google
   review is needed: only this one account ever authorizes it.
5. **Clients → Create client → Web application**, named e.g. "Gmail sender", with the
   authorized redirect URI `https://developers.google.com/oauthplayground`. Keep the client ID
   and secret for step 7.
6. **Get the refresh token** in [OAuth 2.0 Playground](https://developers.google.com/oauthplayground):
   the gear icon → *Use your own OAuth credentials* → paste the client ID and secret. In Step 1,
   type `https://www.googleapis.com/auth/gmail.send` in the box and **Authorize APIs**; sign in as
   the app's Gmail account. Google warns that the app isn't verified: **Advanced → Go to
   TreasuredWord email**, then **Continue**. In Step 2, **Exchange authorization code for
   tokens** and copy the **Refresh token**.
7. **Render → treasuredword → Environment**: set `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`,
   `GMAIL_REFRESH_TOKEN` and `EMAIL_FROM` (`TreasuredWord <treasuredword.app@gmail.com>`).
   Saving redeploys the server.
8. **Check it**: in the app, **Forgot password?** with your own account's email. The code should
   arrive from the app's Gmail account within a minute (check spam the first time).

**Limits** (free Gmail): about 500 emails a day. The server allows 5 codes an hour per account and
10 emails an hour per IP, far below that. If Gmail refuses one anyway, the endpoint answers
`EMAIL_UNAVAILABLE` and the app says it couldn't send the email.

**If emails stop**, the Render log says `Couldn't get a Gmail access token ... invalid_grant`: the
refresh token stopped working, because the Gmail account's password changed, its access was
removed under *Google Account → Security → Third-party connections*, or it sent nothing for six
months. Repeat step 6 and update `GMAIL_REFRESH_TOKEN`.

## Reading reported Bible Helper answers (#125)

Every answer in the app has a report button. Reports land in the `ai_reports` table without the
reporter's ID and are deleted after 90 days. Read them in Neon: the project → **SQL Editor**:

```sql
SELECT created_at, reason, language, prompt_version, comment, question, answer
FROM ai_reports
ORDER BY created_at DESC
LIMIT 50;
```

A count by reason over the last week shows whether something changed after an instructions or
model update:

```sql
SELECT reason, count(*) FROM ai_reports
WHERE created_at > now() - interval '7 days'
GROUP BY reason ORDER BY count(*) DESC;
```

Once a report has been read and acted on (an instructions change, an evaluation question added
to `docs/ai_helper_eval.md`), it can be deleted early:
`DELETE FROM ai_reports WHERE id = '<id>';`

## What was verified locally before writing this doc (Phase 5)

- `docker build .` from the repo root succeeds without the `:app` (Android) module present in the
  build context at all — confirmed by testing a copy of the repo with `app/` removed entirely.
- The built image, run against a real Neon database, passes `/health`, registers a user, and
  correctly applies a brand-new `V2` migration to a database that was already at `V1` — not just
  a fresh empty database.
- Rate limiting on `/auth/register`, `/auth/login`, `/auth/refresh`: the 11th request within a
  minute gets `429`, the first 10 get through. That test came from one machine, so it couldn't
  show that the limit was shared by every caller; since #162 it's per IP (`clientIp()` in
  `plugins/RateLimiting.kt`, from Cloudflare's `True-Client-IP` header on Render).
- **Real bug found and fixed**: `flyway-core` and `flyway-database-postgresql` both ship a
  `META-INF/services/org.flywaydb.core.extensibility.Plugin` file at the same path. Without
  `mergeServiceFiles()` on the `shadowJar` task (see `server/build.gradle.kts`), the packaged fat
  jar silently kept only one of them, dropping `flyway-core`'s own resource/resolver
  registrations — migrations would silently never apply in the *deployed* jar, while
  `./gradlew :server:test` (which runs against unpacked classes, never a merged jar) looked
  completely fine. Worth remembering if a future dependency upgrade reintroduces something
  similar: check `jar tf server/build/libs/server-all.jar | grep META-INF/services` after adding
  any new dependency that might also register Java services.
- Render specifically wasn't dockerfile-build-tested end-to-end the way the local `docker build`
  was (that needs an actual Render account/deploy), but Render's documented Docker deploy path is
  "point it at a Dockerfile and build," the same thing already verified locally — no
  Render-specific adaptation was needed to the Dockerfile itself.

## Once deployed: point the Android app at it

`app/src/main/java/com/application/bibleapp/data/remote/HttpClientProvider.kt`'s `BASE_URL` is
still hardcoded to `http://10.0.2.2:8080/api/v1` (the emulator's alias for your own machine).
Once there's a real `https://treasuredword.onrender.com` URL, that constant needs to change, and
the `10.0.2.2` cleartext exception in `app/src/debug/res/xml/network_security_config.xml` can go
too (HTTPS doesn't need it). Not done yet — do this once the first real deploy is live and you
have the actual URL.
