package com.application.bibleapp.server.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.application.bibleapp.server.ai.SystemPrompt
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.db.tables.AiStarterAnswers
import com.application.bibleapp.server.models.AiAskRequest
import com.application.bibleapp.server.models.AiAskResponse
import com.application.bibleapp.server.models.AiMessageDto
import com.application.bibleapp.server.models.AiPassage
import com.application.bibleapp.server.search.FakeEmbeddingProvider
import com.application.bibleapp.server.search.VerseSearchLoader
import com.application.bibleapp.server.search.eval.VectorCache
import com.application.bibleapp.server.search.eval.Verse
import com.application.bibleapp.server.search.eval.VerseRef
import com.application.bibleapp.server.serverModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Bible Helper with the verses search finds (#287, phase 5): sent with the question when
 * AI_HELPER_VERSES is on, and never needed for an answer. Verses go in under a translation of
 * their own, as in VerseSearchTest, so a dev database's BSB rows are never touched.
 */
class AiHelperVersesTest {

    private val url = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5433/bibleapp"
    private val user = System.getenv("DATABASE_USER") ?: "postgres"
    private val password = System.getenv("DATABASE_PASSWORD") ?: "postgres"
    private val translation = "test-${UUID.randomUUID().toString().take(8)}"
    private val folder = Files.createTempDirectory("helper-verses").toFile()
    private val embedding = FakeEmbeddingProvider(maxBatch = 4, dimensions = 1024)

    private val verses = listOf(
        Verse(VerseRef(40, 6, 33), "But seek first the kingdom of God and His righteousness, and all these things will be added unto you."),
        Verse(VerseRef(40, 6, 34), "Therefore do not worry about tomorrow, for tomorrow will worry about itself."),
        Verse(VerseRef(50, 4, 6), "Be anxious for nothing, but in everything, by prayer and petition, present your requests to God."),
        Verse(VerseRef(43, 11, 35), "Jesus wept.")
    )
    private val question = "Why do I worry about tomorrow so much?"

    @BeforeTest
    fun loadVerses() {
        Flyway.configure().dataSource(url, user, password).load().migrate()
        DriverManager.getConnection(url, user, password).use { connection ->
            VerseSearchLoader(connection, embedding.model, translation, VectorCache(folder.resolve("cache.vec"), embedding.model, translation), { embedding })
                .load(verses)
        }
        embedding.calls.clear()
    }

    @AfterTest
    fun cleanUp() {
        DriverManager.getConnection(url, user, password).use { connection ->
            for (table in listOf("verse_embeddings", "search_verses")) {
                connection.prepareStatement("DELETE FROM $table WHERE translation = ?").use { it.setString(1, translation); it.executeUpdate() }
            }
        }
        folder.deleteRecursively()
    }

    private fun ApplicationTestBuilder.start(ai: FakeAiProvider, helperVerses: Boolean = true): HttpClient {
        environment {
            config = ApplicationConfig("application.conf")
                .mergeWith(MapApplicationConfig().apply { put("ktor.application.modules", emptyList()) })
        }
        application {
            serverModule(
                emailSender = FakeEmailSender(),
                aiProvider = ai,
                embeddingProvider = embedding,
                helperVerses = helperVerses,
                searchTranslation = translation
            )
        }
        return createClient { install(ContentNegotiation) { json() } }
    }

    private suspend fun HttpClient.ask(user: TestUser, request: AiAskRequest): HttpResponse =
        post("/api/v1/ai/ask") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(request)
        }

    private fun ask(text: String = question, passage: AiPassage? = null) =
        AiAskRequest(listOf(AiMessageDto("user", text)), language = "en", passage = passage)

    private suspend fun HttpResponse.answer() = Json.decodeFromString<AiAskResponse>(bodyAsText())

    @Test
    fun aQuestionGetsTheVersesSearchFindsAndSaysV15() = testApplication {
        val ai = FakeAiProvider()
        val client = start(ai)
        val signedIn = client.registerTestUser()

        val response = client.ask(signedIn, ask())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(SystemPrompt.VERSES_PROMPT_VERSION, response.answer().promptVersion)
        assertEquals(listOf(question), embedding.calls.single().texts, "one search, of the latest question")
        val instructions = ai.calls.single().messages.first().content
        assertTrue(instructions.startsWith(SystemPrompt.forLanguage("en")), "v5's instructions come first, unchanged")
        assertTrue("# 9. Verses that may help" in instructions)
        // Neighbouring verses are one passage, with both texts.
        assertTrue("MAT 6:33-34 — But seek first the kingdom" in instructions)
        assertTrue("for tomorrow will worry about itself." in instructions)
        assertFalse("<!--" in instructions)
        assertFalse(Regex("\\{[a-zA-Z]+\\}").containsMatchIn(instructions))
    }

    @Test
    fun whenSearchFailsTheAnswerIsV5sExactly() = testApplication {
        val ai = FakeAiProvider()
        val client = start(ai)
        val signedIn = client.registerTestUser()
        embedding.failures += Unavailable("Workers AI is down")

        val failed = client.ask(signedIn, ask())
        assertEquals(HttpStatusCode.OK, failed.status)
        assertEquals(SystemPrompt.PROMPT_VERSION, failed.answer().promptVersion)
        assertEquals(SystemPrompt.forLanguage("en"), ai.calls.last().messages.first().content)

        // Out of neurons is the same: an answer without verses.
        embedding.quotaAfterCalls = 0
        val exhausted = client.ask(signedIn, ask())
        assertEquals(HttpStatusCode.OK, exhausted.status)
        assertEquals(SystemPrompt.PROMPT_VERSION, exhausted.answer().promptVersion)
        assertEquals(SystemPrompt.forLanguage("en"), ai.calls.last().messages.first().content)
        assertEquals(13, exhausted.answer().remainingToday, "both answers counted as usual")
    }

    @Test
    fun aQuestionWithNoMatchingVersesIsV5() = testApplication {
        val ai = FakeAiProvider()
        val client = start(ai)
        val signedIn = client.registerTestUser()
        // The test translation's 4 verses are all vector candidates, so empty its table first.
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.prepareStatement("DELETE FROM verse_embeddings WHERE translation = ?").use { it.setString(1, translation); it.executeUpdate() }
        }

        val response = client.ask(signedIn, ask("Zzz qqq?"))

        assertEquals(SystemPrompt.PROMPT_VERSION, response.answer().promptVersion)
        assertEquals(SystemPrompt.forLanguage("en"), ai.calls.single().messages.first().content)
    }

    @Test
    fun aQuestionAboutASelectedPassageIsntSearched() = testApplication {
        val ai = FakeAiProvider()
        val client = start(ai)
        val signedIn = client.registerTestUser()

        val response = client.ask(signedIn, ask("What does this mean?", AiPassage("JHN 11:35", "Jesus wept.")))

        assertEquals(SystemPrompt.PROMPT_VERSION, response.answer().promptVersion)
        assertTrue(embedding.calls.isEmpty())
        assertFalse("Verses that may help" in ai.calls.single().messages.first().content)
    }

    @Test
    fun offByDefaultNothingIsSearched() = testApplication {
        val ai = FakeAiProvider()
        val client = start(ai, helperVerses = false)
        val signedIn = client.registerTestUser()

        val response = client.ask(signedIn, ask())

        assertEquals(SystemPrompt.PROMPT_VERSION, response.answer().promptVersion)
        assertEquals(SystemPrompt.forLanguage("en"), ai.calls.single().messages.first().content)
        assertTrue(embedding.calls.isEmpty())
    }

    @Test
    fun aStartersReadyAnswerIsKeptPerVersion() = testApplication {
        transaction { AiStarterAnswers.deleteAll() }
        val ai = FakeAiProvider()
        val client = start(ai)
        val first = client.registerTestUser()
        val second = client.registerTestUser()
        transaction {
            // v5's ready answer isn't the one a v15 server gives.
            AiStarterAnswers.insert {
                it[AiStarterAnswers.starter] = "prayer"
                it[language] = "en"
                it[promptVersion] = SystemPrompt.PROMPT_VERSION
                it[answer] = "v5's answer"
                it[createdAt] = java.time.Instant.now()
            }
        }
        val starter = AiAskRequest(listOf(AiMessageDto("user", "Anything")), language = "en", starter = "prayer")

        val fresh = client.ask(first, starter)
        assertEquals(ai.answer, fresh.answer().answer)
        assertEquals(SystemPrompt.VERSES_PROMPT_VERSION, fresh.answer().promptVersion)
        assertEquals(listOf("How should I pray?"), embedding.calls.single().texts, "searched in the server's own words")
        val ready = client.ask(second, starter)
        assertEquals(ai.answer, ready.answer().answer)
        assertEquals(SystemPrompt.VERSES_PROMPT_VERSION, ready.answer().promptVersion)
        assertEquals(1, ai.calls.size, "the second is the stored v15 answer")
        transaction { AiStarterAnswers.deleteAll() }
    }

    @Test
    fun theQuestionAndVersesAreNeverLogged() = testApplication {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val logged = ListAppender<ILoggingEvent>().apply { start(); root.addAppender(this) }
        try {
            val client = start(FakeAiProvider())
            client.ask(client.registerTestUser(), ask())
        } finally {
            root.detachAppender(logged)
        }
        val messages = logged.list.map { it.formattedMessage }
        assertTrue(messages.any { it.startsWith("Search:") }, "the search itself is logged, by count and time")
        assertFalse(messages.any { "worry" in it.lowercase() || "tomorrow" in it.lowercase() })
    }
}
