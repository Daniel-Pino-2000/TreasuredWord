package com.application.bibleapp.server.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.application.bibleapp.server.ai.DoctrineRouter
import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.SystemPrompt
import com.application.bibleapp.server.ai.TopicGuides
import com.application.bibleapp.server.ai.TopicRouter
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.db.tables.AiDailyUsage
import com.application.bibleapp.server.db.tables.AiStarterAnswers
import com.application.bibleapp.server.models.AiAskRequest
import com.application.bibleapp.server.models.AiAskResponse
import com.application.bibleapp.server.models.AiMessageDto
import com.application.bibleapp.server.models.AiPassage
import com.application.bibleapp.server.search.FakeEmbeddingProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Bible Helper with topic guides on (#319): [FakeEmbeddingProvider] in place of bge-m3, so a
 * question matches a guide when it shares the words of one of its phrases.
 */
class AiTopicGuidesRoutesTest {

    private val guides = TopicGuides.parse(
        """
        ## alcohol
        - **Title:** Alcohol
        - **Asks:** Is drinking alcohol a sin? | Can a Christian drink wine or beer?
        - **Points:**
          - Drunkenness is always sin.
        - **Passages:**
          - EPH 5:18: do not get drunk on wine

        ## salvation
        - **Title:** How to be saved
        - **Asks:** How can I be saved? | How do I become a Christian?
        - **Points:**
          - Saved by grace through faith.
        - **Passages:**
          - EPH 2:8-9: saved by grace through faith, not by works
        """.trimIndent()
    )

    /** The same router the server would have, with the fake's vectors of the test guides' phrases. */
    private fun router(): TopicRouter = runBlocking { TopicRouter.embedding(guides, FakeEmbeddingProvider()) }

    private fun section(id: String): String =
        TopicGuides.section(listOf(com.application.bibleapp.server.ai.RoutedGuide(guides.single { it.id == id }, 1.0)))!!

    private fun ask(text: String, language: String = "en") = AiAskRequest(listOf(AiMessageDto("user", text)), language = language)

    private suspend fun HttpClient.ask(user: TestUser, request: AiAskRequest, path: String = "/api/v1/ai/ask"): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(request)
        }

    private suspend fun HttpResponse.answer() = Json.decodeFromString<AiAskResponse>(bodyAsText())

    private fun FakeAiProvider.instructions(): String = calls.last().messages.first().content

    @Test
    fun aMatchingQuestionGetsItsGuideAfterV5sInstructionsAndReportsVersion16() = testApplication {
        val ai = FakeAiProvider()
        val embeddings = FakeEmbeddingProvider()
        val client = startWithFakeAi(ai, embeddings = embeddings, topicRouter = router())

        val response = client.ask(client.registerTestUser(), ask("Is drinking alcohol a sin?"))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(SystemPrompt.GUIDES_VERSION, response.answer().promptVersion)
        assertEquals(SystemPrompt.forLanguage("en") + "\n\n" + section("alcohol"), ai.instructions())
        // One embedding, of the question itself.
        assertEquals(listOf(listOf("Is drinking alcohol a sin?")), embeddings.calls.map { it.texts })
    }

    @Test
    fun aQuestionWithNoGuideGetsV5sInstructionsExactly() = testApplication {
        val ai = FakeAiProvider()
        val embeddings = FakeEmbeddingProvider()
        val client = startWithFakeAi(ai, embeddings = embeddings, topicRouter = router())

        val response = client.ask(client.registerTestUser(), ask("Who exactly was Melchizedek?", "es"))

        assertEquals(SystemPrompt.PROMPT_VERSION, response.answer().promptVersion)
        assertEquals(SystemPrompt.forLanguage("es"), ai.instructions())
        assertEquals(1, embeddings.calls.size)
    }

    @Test
    fun aFailingEmbeddingOrNoNeuronsStillAnswersWithV5() = testApplication {
        val ai = FakeAiProvider()
        val embeddings = FakeEmbeddingProvider().apply {
            failures += Unavailable("Workers AI answered HTTP 500")
            failures += QuotaExhausted("The AI's free daily allowance is used up")
        }
        val client = startWithFakeAi(ai, embeddings = embeddings, topicRouter = router())
        val user = client.registerTestUser()

        for (attempt in 1..2) {
            val response = client.ask(user, ask("Is drinking alcohol a sin?"))
            assertEquals(HttpStatusCode.OK, response.status, "attempt $attempt")
            assertEquals(SystemPrompt.PROMPT_VERSION, response.answer().promptVersion)
            assertEquals(SystemPrompt.forLanguage("en"), ai.instructions())
        }
        // Once Workers AI answers again, so do the guides.
        assertEquals(SystemPrompt.GUIDES_VERSION, client.ask(user, ask("Is drinking alcohol a sin?")).answer().promptVersion)
    }

    @Test
    fun aQuestionAboutASelectedPassageIsntMatched() = testApplication {
        val ai = FakeAiProvider()
        val embeddings = FakeEmbeddingProvider()
        val client = startWithFakeAi(ai, embeddings = embeddings, topicRouter = router())
        val request = AiAskRequest(
            listOf(AiMessageDto("user", "Is drinking alcohol a sin?")), "en",
            AiPassage("EPH 5:18", "18 Do not get drunk on wine, which leads to reckless indiscretion.")
        )

        val response = client.ask(client.registerTestUser(), request)

        assertEquals(SystemPrompt.PROMPT_VERSION, response.answer().promptVersion)
        assertEquals(SystemPrompt.forLanguage("en", request.passage!!.reference, request.passage!!.text), ai.instructions())
        assertTrue(embeddings.calls.isEmpty())
    }

    @Test
    fun withTheSettingOffNothingIsEmbeddedAndTheInstructionsAreV5s() = testApplication {
        val ai = FakeAiProvider()
        val embeddings = FakeEmbeddingProvider()
        // Search by meaning is set up, but AI_TOPIC_GUIDES is off: no router.
        val client = startWithFakeAi(ai, embeddings = embeddings, topicRouter = null)

        val response = client.ask(client.registerTestUser(), ask("Is drinking alcohol a sin?"))

        assertEquals(SystemPrompt.PROMPT_VERSION, response.answer().promptVersion)
        assertEquals(SystemPrompt.forLanguage("en"), ai.instructions())
        assertTrue(embeddings.calls.isEmpty())
    }

    @Test
    fun withoutSearchSetUpTheGuidesStayOff() = testApplication {
        val ai = FakeAiProvider()
        val client = startWithFakeAi(ai, embeddings = null, topicRouter = router())

        val response = client.ask(client.registerTestUser(), ask("Is drinking alcohol a sin?"))

        assertEquals(SystemPrompt.PROMPT_VERSION, response.answer().promptVersion)
        assertEquals(SystemPrompt.forLanguage("en"), ai.instructions())
    }

    @Test
    fun onlyTheLatestQuestionIsMatched() = testApplication {
        val ai = FakeAiProvider()
        val embeddings = FakeEmbeddingProvider()
        val client = startWithFakeAi(ai, embeddings = embeddings, topicRouter = router())
        val conversation = AiAskRequest(
            listOf(
                AiMessageDto("user", "Is drinking alcohol a sin?"),
                AiMessageDto("assistant", "Drunkenness is always sin. [[EPH 5:18]]"),
                AiMessageDto("user", "Who exactly was Melchizedek?")
            ),
            "en"
        )

        val response = client.ask(client.registerTestUser(), conversation)

        assertEquals(SystemPrompt.PROMPT_VERSION, response.answer().promptVersion)
        assertEquals(listOf(listOf("Who exactly was Melchizedek?")), embeddings.calls.map { it.texts })
    }

    @Test
    fun aQuestionRefusedByTheDailyLimitIsNeverAnswered() = testApplication {
        // The guides are matched while the daily count is taken (#347), so a refused question may
        // have started its embedding (~0.01 neurons); the match is cancelled and nothing is asked.
        val clock = MutableClock()
        val embeddings = FakeEmbeddingProvider()
        val ai = FakeAiProvider()
        val client = startWithFakeAi(ai, clock, embeddings, router())
        val user = client.registerTestUser()
        transaction {
            AiDailyUsage.insert {
                it[userId] = UUID.fromString(user.auth.userId)
                it[day] = LocalDate.ofInstant(clock.now, ZoneOffset.UTC)
                it[count] = AI_DAILY_LIMIT
            }
        }

        assertEquals(HttpStatusCode.TooManyRequests, client.ask(user, ask("Is drinking alcohol a sin?")).status)
        assertTrue(ai.calls.isEmpty())
        assertTrue(embeddings.calls.size <= 1)
    }

    @Test
    fun aStreamedAnswerReportsTheVersionToo() = testApplication {
        val ai = FakeAiProvider().apply { pieces = listOf("Drunkenness ", "is sin. [[EPH 5:18]]") }
        val client = startWithFakeAi(ai, embeddings = FakeEmbeddingProvider(), topicRouter = router())

        val body = client.ask(client.registerTestUser(), ask("Is drinking alcohol a sin?"), "/api/v1/ai/ask/stream").bodyAsText()

        val done = body.split("\n\n").single { it.startsWith("event: done") }.substringAfter("data: ")
        assertEquals(SystemPrompt.GUIDES_VERSION, Json.decodeFromString<AiAskResponse>(done).promptVersion)
        assertTrue(section("alcohol") in ai.instructions())
    }

    @Test
    fun aGuidedStarterAnswerIsStoredUnderItsOwnVersion() = testApplication {
        val ai = FakeAiProvider()
        val embeddings = FakeEmbeddingProvider()
        val client = startWithFakeAi(ai, embeddings = embeddings, topicRouter = router())
        transaction {
            AiStarterAnswers.deleteAll()
            // An answer v5 wrote before the guides were turned on: not served once they are.
            AiStarterAnswers.insert {
                it[starter] = "saved"
                it[language] = "en"
                it[promptVersion] = SystemPrompt.PROMPT_VERSION
                it[answer] = "v5's answer"
                it[createdAt] = Instant.now()
            }
        }
        val starter = AiAskRequest(listOf(AiMessageDto("user", "anything")), language = "en", starter = "saved")

        val fresh = client.ask(client.registerTestUser(), starter).answer()
        assertEquals(SystemPrompt.GUIDES_VERSION, fresh.promptVersion)
        assertEquals(ai.answer, fresh.answer)
        assertTrue(section("salvation") in ai.instructions())

        val ready = client.ask(client.registerTestUser(), starter).answer()
        assertEquals(ai.answer, ready.answer)
        assertEquals(SystemPrompt.GUIDES_VERSION, ready.promptVersion)
        // One answer and one embedding: the starter's match is remembered.
        assertEquals(1, ai.calls.size)
        assertEquals(1, embeddings.calls.size)
        val stored = transaction {
            AiStarterAnswers.selectAll()
                .where { (AiStarterAnswers.starter eq "saved") and (AiStarterAnswers.language eq "en") }
                .associate { it[AiStarterAnswers.promptVersion] to it[AiStarterAnswers.answer] }
        }
        assertEquals(mapOf(SystemPrompt.PROMPT_VERSION to "v5's answer", SystemPrompt.GUIDES_VERSION to ai.answer), stored)
        transaction { AiStarterAnswers.deleteAll() }
    }

    /** The doctrine items on (#297): the same fake vectors as the guides, so a match is predictable. */
    private fun doctrineRouter(): DoctrineRouter = runBlocking { DoctrineRouter.embedding(SystemPrompt.doctrine, FakeEmbeddingProvider()) }

    @Test
    fun withTheDoctrineItemsOnAQuestionGetsOnlyItsItemsAndReportsVersion17() = testApplication {
        val ai = FakeAiProvider()
        val embeddings = FakeEmbeddingProvider()
        val doctrine = doctrineRouter()
        val client = startWithFakeAi(ai, embeddings = embeddings, topicRouter = router(), doctrineRouter = doctrine)

        val question = "Is drinking alcohol a sin?"
        val response = client.ask(client.registerTestUser(), ask(question))

        assertEquals(SystemPrompt.DOCTRINE_VERSION, response.answer().promptVersion)
        val items = doctrine.pick(embeddings.vector(question))
        assertEquals(SystemPrompt.forLanguage("en", doctrineItems = items) + "\n\n" + section("alcohol"), ai.instructions())
        // Still the one embedding.
        assertEquals(1, embeddings.calls.size)
    }

    @Test
    fun withTheDoctrineItemsOnAFailedMatchOrAPassageGetsTheWholeDoctrine() = testApplication {
        val ai = FakeAiProvider()
        val embeddings = FakeEmbeddingProvider().apply { failures += Unavailable("Workers AI answered HTTP 500") }
        val client = startWithFakeAi(ai, embeddings = embeddings, topicRouter = router(), doctrineRouter = doctrineRouter())
        val user = client.registerTestUser()

        assertEquals(SystemPrompt.PROMPT_VERSION, client.ask(user, ask("Is drinking alcohol a sin?")).answer().promptVersion)
        assertEquals(SystemPrompt.forLanguage("en"), ai.instructions())

        val passage = AiPassage("EPH 5:18", "18 Do not get drunk on wine, which leads to reckless indiscretion.")
        val request = AiAskRequest(listOf(AiMessageDto("user", "What does this mean?")), "en", passage)
        assertEquals(SystemPrompt.PROMPT_VERSION, client.ask(user, request).answer().promptVersion)
        assertEquals(SystemPrompt.forLanguage("en", passage.reference, passage.text), ai.instructions())
    }

    @Test
    fun neitherTheQuestionNorItsGuideIsLogged() = testApplication {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val logged = ListAppender<ILoggingEvent>().apply { start(); root.addAppender(this) }
        try {
            val client = startWithFakeAi(FakeAiProvider(), embeddings = FakeEmbeddingProvider(), topicRouter = router())
            client.ask(client.registerTestUser(), ask("Is drinking alcohol a sin?"))

            val text = logged.list.joinToString("\n") { it.formattedMessage }
            assertTrue("Topic guides: 1" in text, "the outcome is logged")
            assertFalse("alcohol" in text.lowercase())
            assertFalse("Alcohol" in text)
        } finally {
            root.detachAppender(logged)
        }
    }
}
