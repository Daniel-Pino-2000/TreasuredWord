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
   - It shows the one service (`bibleapp-server`) the blueprint defines and asks you to fill in
     the env vars marked `sync: false` — `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`,
     `JWT_SECRET`. See "Every deploy" below for what values to use; you can also leave them blank
     here and fill them in afterward under the service's **Environment** tab.
   - Click **Apply**. Render clones the repo, builds `Dockerfile`, and deploys.

## Every deploy

Unlike Fly's CLI-driven `flyctl deploy`, Render auto-deploys on every push to the connected
branch by default — there's no separate deploy command to run day-to-day. Everything below is
either one-time or only needed when a secret changes.

1. **Set the secrets**, in the Render dashboard under the service → **Environment**:
   - `DATABASE_URL` — `jdbc:postgresql://<neon-host>/<db>?sslmode=require`. Use Neon's
     **non-pooled** host (no `-pooler` in the hostname) — confirmed during Phase 5 testing that
     this avoids PgBouncer-related quirks with Flyway running DDL on every startup.
   - `DATABASE_USER` / `DATABASE_PASSWORD` — from the same Neon connection string.
   - `JWT_SECRET` — a real random value (e.g. generate one locally with
     `openssl rand -base64 48`), never the `application.conf` dev placeholder.

   Saving env var changes triggers a redeploy automatically.

2. **Deploy** happens on `git push` to the branch Render is watching (set this under the
   service's **Settings** → **Build & Deploy** if you need to change which branch). To trigger one
   manually without a new commit, use the dashboard's **Manual Deploy** button, or the Render CLI
   (`render deploy`) if you install it.

3. **Verify**:
   ```bash
   curl https://<your-service-name>.onrender.com/health
   ```
   Logs are under the service's **Logs** tab in the dashboard (or `render logs` via the CLI).

## What was verified locally before writing this doc (Phase 5)

- `docker build .` from the repo root succeeds without the `:app` (Android) module present in the
  build context at all — confirmed by testing a copy of the repo with `app/` removed entirely.
- The built image, run against a real Neon database, passes `/health`, registers a user, and
  correctly applies a brand-new `V2` migration to a database that was already at `V1` — not just
  a fresh empty database.
- Rate limiting on `/auth/register`, `/auth/login`, `/auth/refresh`: the 11th request within a
  minute gets `429`, the first 10 get through.
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
Once there's a real `https://<service-name>.onrender.com` URL, that constant needs to change, and
the `10.0.2.2` cleartext exception in `app/src/debug/res/xml/network_security_config.xml` can go
too (HTTPS doesn't need it). Not done yet — do this once the first real deploy is live and you
have the actual URL.
