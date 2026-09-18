# Deploying the server (Phase 5)

Stack: [Fly.io](https://fly.io) runs the Ktor app as a Docker container; [Neon](https://neon.tech)
is the production Postgres. Both have free tiers; see the root `Dockerfile`/`fly.toml` for the
actual build/run config.

## One-time setup

1. **Install flyctl** — already done on this machine via `winget install --id Fly-io.flyctl -e`.
   Restart your terminal once so the `flyctl` command is on `PATH`.
2. **Create a Fly.io account and add a payment method** — even the free allowance requires a card
   on file now. Do this at [fly.io](https://fly.io); Claude can't create accounts or enter
   payment details for you.
3. **Authenticate the CLI**:
   ```bash
   flyctl auth login
   ```
   This opens a browser for you to log in/sign up, then returns control to the terminal.
4. **Reserve the app name and generate the real fly.toml**, from the repo root:
   ```bash
   flyctl launch --no-deploy
   ```
   It detects the `Dockerfile` automatically. Say **no** when it offers to create a Postgres
   database for you — we're using Neon, not Fly's own Postgres. It'll either reuse the
   `fly.toml` already in the repo (rename the `app` inside it to whatever name it reserved) or
   offer to regenerate one — if it regenerates, re-apply the `[http_service]` health check and
   `JAVA_TOOL_OPTIONS` block from the committed `fly.toml` by hand, since those aren't things
   `fly launch` knows to add on its own.

## Every deploy

1. **Set secrets once per app** (not committed to git — `fly secrets set` stores these encrypted
   on Fly's side and injects them as env vars at runtime, same names `application.conf` already
   reads):
   ```bash
   flyctl secrets set \
     DATABASE_URL="jdbc:postgresql://<neon-host>/<db>?sslmode=require" \
     DATABASE_USER="<neon-user>" \
     DATABASE_PASSWORD="<neon-password>" \
     JWT_SECRET="$(openssl rand -base64 48)"
   ```
   Use Neon's **non-pooled** connection host (no `-pooler` in the hostname) for `DATABASE_URL` —
   confirmed during Phase 5 testing that this avoids PgBouncer-related quirks with Flyway running
   DDL on every startup. `JWT_SECRET` should be a real random value here, never the
   `application.conf` dev placeholder.

2. **Deploy**:
   ```bash
   flyctl deploy
   ```
   Fly builds the `Dockerfile` on its own remote builder (nothing needs to be pushed manually) and
   rolls out the new machine. Flyway runs automatically on startup — this is where any new
   `V{n}__description.sql` migration actually gets applied.

3. **Verify**:
   ```bash
   curl https://<your-app-name>.fly.dev/health
   flyctl logs
   ```

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

## Once deployed: point the Android app at it

`app/src/main/java/com/application/bibleapp/data/remote/HttpClientProvider.kt`'s `BASE_URL` is
still hardcoded to `http://10.0.2.2:8080/api/v1` (the emulator's alias for your own machine).
Once there's a real `https://<app-name>.fly.dev` URL, that constant needs to change, and the
`10.0.2.2` cleartext exception in `app/src/debug/res/xml/network_security_config.xml` can go too
(HTTPS doesn't need it). Not done yet — do this once the first real deploy is live and you have
the actual URL.
