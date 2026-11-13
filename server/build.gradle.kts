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

// The Bible Helper's evaluation (#126): every question in docs/ai_helper_eval.md through the live
// instructions and AI, into build/ai-eval/. Spends real free-plan neurons, so it's never part of
// `test`, `check` or CI. Needs AI_BASE_URL, AI_API_TOKEN and AI_MODEL; -Pquestions=CORE-01,LANG-02
// runs only those.
tasks.register<JavaExec>("aiEval") {
    group = "verification"
    description = "Asks the Bible Helper every evaluation question and writes a report to build/ai-eval."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.application.bibleapp.server.ai.eval.AiEvalKt")
    args(
        rootProject.file("docs/ai_helper_eval.md").path,
        layout.buildDirectory.dir("ai-eval").get().asFile.path,
        providers.gradleProperty("questions").getOrElse("")
    )
}

// Only the search-by-meaning evaluation reads SQLite (the app's bible_default.db), so the driver
// sits in its own configuration and never reaches server-all.jar (#287).
val semanticSearchEvalRuntime by configurations.creating

// Search by meaning's evaluation (#287, phase 1): embeds the Bible and every query in
// docs/semantic_search_eval.md with Cloudflare Workers AI and reports how many queries find an
// expected verse in their top 10, into build/semantic-search-eval/. Spends real free-plan neurons
// (~1,200 the first time, then the vectors come from a cache under build/), so it's never part of
// `test`, `check` or CI. Needs AI_BASE_URL and AI_API_TOKEN. -PembeddingModel picks the model,
// -Pqueries=TOPIC-01,LANG-KO-01 runs only those, -Pbible=<file.tsv> embeds another translation.
tasks.register<JavaExec>("semanticSearchEval") {
    group = "verification"
    description = "Measures search by meaning against docs/semantic_search_eval.md and writes a report to build/semantic-search-eval."
    classpath = sourceSets["main"].runtimeClasspath + semanticSearchEvalRuntime
    mainClass.set("com.application.bibleapp.server.search.eval.SemanticSearchEvalKt")
    // 31,102 vectors of 1,024 floats are ~130 MB in memory.
    maxHeapSize = "1g"
    args(
        rootProject.file("docs/semantic_search_eval.md").path,
        layout.buildDirectory.dir("semantic-search-eval").get().asFile.path,
        rootProject.file("app/src/main/assets/bible_default.db").path,
        providers.gradleProperty("embeddingModel").getOrElse("@cf/baai/bge-m3"),
        providers.gradleProperty("queries").getOrElse(""),
        providers.gradleProperty("bible").map { rootProject.file(it).path }.getOrElse("")
    )
}

dependencies {
    semanticSearchEvalRuntime(libs.sqlite.jdbc)
    // The tests read the real bible_default.db to check every expected verse exists (#287).
    testRuntimeOnly(libs.sqlite.jdbc)

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

    // The HTTP client the server calls the AI provider with (#123). Content negotiation must be
    // implementation, not testImplementation: test-only libraries never reach server-all.jar.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

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
    testImplementation(libs.ktor.client.mock)
    testImplementation(kotlin("test"))
}
