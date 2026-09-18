# Builds and runs only the :server module (Ktor/Exposed/Postgres) — the repo also has an :app
# module (Android/Compose) that this image never touches. See .dockerignore for what's excluded
# from the build context, and server/DEPLOY.md for the full deploy story (Fly.io + Neon, Phase 5).

# ---- Build stage ----
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# Copied separately from the rest so Docker's layer cache is reused across builds that only
# change application code, not the wrapper/build scripts themselves.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew

COPY server server

# --configure-on-demand skips evaluating :app's build script (it needs the Android SDK, which
# isn't in this image, and isn't in the build context at all — see .dockerignore) — :server has
# no dependency on :app, so nothing about it is actually needed to build the fat jar. Verified
# locally against a copy of this repo with app/ removed entirely before writing this Dockerfile.
RUN ./gradlew :server:buildFatJar --configure-on-demand --no-daemon --console=plain

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

COPY --from=build /workspace/server/build/libs/server-all.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
