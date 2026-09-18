package com.application.bibleapp.server.plugins

import io.ktor.server.application.Application
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

/**
 * Schema is now versioned via Flyway (Phase 5) instead of inferred at startup —
 * db/migration/V1__init.sql is the baseline (what SchemaUtils.create used to generate for
 * Users/RefreshTokens/Highlights/Notes/ReadingProgress); every schema change from here on is a
 * new V{n}__description.sql file, never an edit to an already-applied one. migrate() itself
 * proves the connection works (it runs a real query), so a separate connectivity check would be
 * redundant.
 *
 * Resolves migrations from the classpath (Flyway's default) in every environment, including the
 * packaged fat jar — that only works because server/build.gradle.kts's shadowJar task has
 * mergeServiceFiles() set. Without it, flyway-core and flyway-database-postgresql's identically
 * named META-INF/services/org.flywaydb.core.extensibility.Plugin files silently overwrite one
 * another during shading, dropping flyway-core's own resource/resolver registrations and making
 * every .sql migration invisible — confirmed as the actual cause after ruling out jar classpath
 * scanning, resource location strategy, and classloader selection individually (Phase 5).
 */
fun Application.configureDatabases() {
    val storageConfig = environment.config.config("storage")
    val jdbcUrl = storageConfig.property("jdbcUrl").getString()
    val user = storageConfig.property("user").getString()
    val password = storageConfig.property("password").getString()

    Flyway.configure()
        .dataSource(jdbcUrl, user, password)
        .load()
        .migrate()

    Database.connect(
        url = jdbcUrl,
        driver = storageConfig.property("driverClassName").getString(),
        user = user,
        password = password
    )
}
