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
        rootProject.file(providers.gradleProperty("questionsFile").getOrElse("docs/ai_helper_eval.md")).path,
        layout.buildDirectory.dir("ai-eval").get().asFile.path,
        providers.gradleProperty("questions").getOrElse("")
    )
}

// Blind A/B grading of two aiEval reports (#321), local files only (no network, no neurons):
// aiEvalBlind writes a sheet with each question's answers shuffled as A and B, its key and a
// grades template to build/ai-eval (-Pfirst, -Psecond, -Psheet=blind1, -Pseed); after grading,
// aiEvalBlindScore -Psheets=blind1,blind2 lists each question's change and each report's totals.
tasks.register<JavaExec>("aiEvalBlind") {
    group = "verification"
    description = "Turns two aiEval reports (-Pfirst, -Psecond) into a blind A/B grading sheet, a key and a grades template."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.application.bibleapp.server.ai.eval.BlindGradingKt")
    args(
        "sheet",
        rootProject.projectDir.path,
        layout.buildDirectory.dir("ai-eval").get().asFile.path,
        providers.gradleProperty("first").getOrElse(""),
        providers.gradleProperty("second").getOrElse(""),
        providers.gradleProperty("sheet").getOrElse("blind"),
        providers.gradleProperty("seed").getOrElse("")
    )
}

tasks.register<JavaExec>("aiEvalBlindScore") {
    group = "verification"
    description = "Reads graded blind sheets (-Psheets=blind1,blind2) with their keys and prints each question's change and each report's totals."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.application.bibleapp.server.ai.eval.BlindGradingKt")
    args(
        "score",
        layout.buildDirectory.dir("ai-eval").get().asFile.path,
        providers.gradleProperty("sheets").getOrElse("blind")
    )
}

// Topic guides (#319): the bge-m3 vector of every "Asks" phrase in resources/ai/topics.md, into
// resources/ai/topic_vectors.tsv, which the server reads so it never embeds them. Run it after
// changing a guide's Asks line (TopicVectorsTest fails until then) and commit the file; phrases
// already in the file keep their vectors, so it embeds only new ones (~0.01 neurons for all 189).
// Needs AI_BASE_URL and AI_API_TOKEN; never part of `test` or CI.
tasks.register<JavaExec>("embedTopicGuides") {
    group = "build"
    description = "Embeds the topic guides' phrases into resources/ai/topic_vectors.tsv."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.application.bibleapp.server.ai.eval.EmbedTopicGuidesKt")
    args(
        file("src/main/resources/ai/topics.md").path,
        file("src/main/resources/ai/topic_vectors.tsv").path
    )
}

// Topic guides: which guide each evaluation question and search query matches, at several
// thresholds, and what the guides add to search's hits. Embeds the ~120 questions and queries
// (~1 neuron, once; the phrases' vectors come from topic_vectors.tsv) and reads the verse tables in
// DATABASE_URL; never part of `test` or CI.
tasks.register<JavaExec>("topicRoutingEval") {
    group = "verification"
    description = "Matches the evaluation questions and search queries to topic guides and reports the routing."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.application.bibleapp.server.ai.eval.TopicRoutingEvalKt")
    args(
        rootProject.file("docs/ai_helper_eval.md").path,
        rootProject.file("docs/ai_helper_eval_heldout.md").path,
        rootProject.file("docs/semantic_search_eval.md").path,
        layout.buildDirectory.dir("ai-eval").get().asFile.path,
        // -Pprobes=<file>: extra questions, "expected guide (or none) | question" a line, several
        // guides as "rest/anxiety"; docs/topic_routing_probes.txt holds #408's. Questions' vectors
        // are cached under build/ai-eval/cache/, so a rerun with no new question needs no token.
        providers.gradleProperty("probes").map { rootProject.file(it).path }.getOrElse("")
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
// -Pqueries=TOPIC-01,LANG-KO-01 runs only those, -Pbible=<file.tsv> embeds another translation,
// -PqueriesFile=docs/semantic_search_eval_heldout.md runs the held-out set instead (#393).
val searchQueriesFile = providers.gradleProperty("queriesFile").map { rootProject.file(it).path }
    .orElse(rootProject.file("docs/semantic_search_eval.md").path)

tasks.register<JavaExec>("semanticSearchEval") {
    group = "verification"
    description = "Measures search by meaning against docs/semantic_search_eval.md and writes a report to build/semantic-search-eval."
    classpath = sourceSets["main"].runtimeClasspath + semanticSearchEvalRuntime
    mainClass.set("com.application.bibleapp.server.search.eval.SemanticSearchEvalKt")
    // 31,102 vectors of 1,024 floats are ~130 MB in memory, and the ranking variants hold a few
    // copies at once (blended, centred, a second translation).
    maxHeapSize = "3g"
    args(
        searchQueriesFile.get(),
        layout.buildDirectory.dir("semantic-search-eval").get().asFile.path,
        rootProject.file("app/src/main/assets/bible_default.db").path,
        providers.gradleProperty("embeddingModel").getOrElse("@cf/baai/bge-m3"),
        providers.gradleProperty("queries").getOrElse(""),
        providers.gradleProperty("bible").map { rootProject.file(it).path }.getOrElse(""),
        // -Pnaves=<NavesTopicalDictionary.csv>: also try Nave's Topical Bible as a topical index (#394).
        providers.gradleProperty("naves").map { rootProject.file(it).path }.getOrElse("")
    )
}

// Search by meaning (#287, phase 2): fills search_verses and verse_embeddings (migration V9) in the
// database DATABASE_URL points at (default: the local Docker Postgres). Vectors come from the
// evaluation's cache under build/semantic-search-eval/cache/ first, so verses it already embedded
// cost no neurons; only missing ones are embedded (needs AI_BASE_URL and AI_API_TOKEN then).
// -Pbible=<file.tsv> is required; -Plimit=100 loads only the first 100 verses to try it.
// Never part of `test`, `check` or CI: against Neon it writes production data.
tasks.register<JavaExec>("semanticSearchEvalDb") {
    group = "verification"
    description = "Runs the search evaluation's queries through the search endpoint's SQL in DATABASE_URL (#287, phase 3); reads only, no neurons."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.application.bibleapp.server.search.eval.DatabaseSearchEvalKt")
    args(
        searchQueriesFile.get(),
        layout.buildDirectory.dir("semantic-search-eval/cache").get().asFile.path,
        providers.gradleProperty("embeddingModel").getOrElse("@cf/baai/bge-m3")
    )
}

tasks.register<JavaExec>("loadVerseSearch") {
    group = "application"
    description = "Loads the search-by-meaning verses and vectors into the database (#287)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.application.bibleapp.server.search.VerseSearchLoaderKt")
    // The cache's 31,086 vectors of 1,024 floats are ~130 MB in memory.
    maxHeapSize = "1g"
    args(
        providers.gradleProperty("bible").map { rootProject.file(it).path }.getOrElse(""),
        layout.buildDirectory.dir("semantic-search-eval/cache").get().asFile.path,
        providers.gradleProperty("embeddingModel").getOrElse("@cf/baai/bge-m3"),
        providers.gradleProperty("limit").getOrElse("")
    )
}

// Search by meaning's topical index (#394, migration V11): Nave's Topical Bible's entries and their
// verses into topic_entries and topic_entry_verses of DATABASE_URL (default: the local Docker
// Postgres), replacing what's there. Vectors come from the evaluation's cache (0 neurons after
// `semanticSearchEval -Pnaves=`). -Pnaves=<NavesTopicalDictionary.csv> is required. Never part of
// `test`, `check` or CI: against Neon it writes production data.
tasks.register<JavaExec>("loadTopicSearch") {
    group = "application"
    description = "Loads Nave's Topical Bible into the search-by-meaning topical index (#394)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.application.bibleapp.server.search.TopicSearchLoaderKt")
    // ~22,000 cached vectors of 1,024 floats are ~90 MB in memory.
    maxHeapSize = "1g"
    args(
        providers.gradleProperty("naves").map { rootProject.file(it).path }.getOrElse(""),
        layout.buildDirectory.dir("semantic-search-eval/cache").get().asFile.path,
        providers.gradleProperty("embeddingModel").getOrElse("@cf/baai/bge-m3")
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
