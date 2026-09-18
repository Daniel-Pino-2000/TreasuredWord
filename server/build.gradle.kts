plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.ktor)
}

group = "com.application.bibleapp.server"
version = "0.0.1"

application {
    mainClass.set("com.application.bibleapp.server.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}

// Root cause of a real bug (Phase 5): flyway-core and flyway-database-postgresql both ship a
// META-INF/services/org.flywaydb.core.extensibility.Plugin file at the identical path. Without
// mergeServiceFiles(), the shaded jar keeps only ONE of them (whichever the packaging happened
// to process last) instead of combining them — which meant flyway-core's own registrations
// (CoreResourceTypeProvider, CoreMigrationTypeResolver — the classes that let Flyway recognize
// "V1__init.sql" as a valid migration at all) were silently dropped, leaving only
// flyway-database-postgresql's 3 entries. Symptom: migrate() logged "no migration could be
// resolved in the configured locations" and silently no-opped forever, in the *packaged* jar
// only — never in `./gradlew :server:test`, which runs against unpacked classes/resources and
// never merges anything. Confirmed via `jar tf`/`unzip -p` on both the merged fat jar and each
// source dependency jar before landing on this fix.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    mergeServiceFiles()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.postgresql)

    implementation(libs.logback.classic)

    implementation(libs.password.hashing)

    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(kotlin("test"))
}
