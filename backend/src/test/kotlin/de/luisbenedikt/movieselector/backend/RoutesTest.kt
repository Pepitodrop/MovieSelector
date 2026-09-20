package de.luisbenedikt.movieselector.backend

import de.luisbenedikt.movieselector.game.Movie
import de.luisbenedikt.movieselector.game.Provider
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun testMovie(id: String) = Movie(id, "Movie $id", 2020, 100, 7.0, null, setOf(Provider.NETFLIX))

private class FakePool(private val movies: List<Movie>) : MoviePool {
    override suspend fun get(): List<Movie> = movies
}

class RoutesTest {
    @Test fun `health check is public`() = testApplication {
        application { configure("game.test", FakePool(listOf(testMovie("1"))), SessionStore()) }
        val client = configuredClient()
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test fun `starting a session returns a session id and a current movie`() = testApplication {
        application { configure("game.test", FakePool(listOf(testMovie("1"), testMovie("2"))), SessionStore()) }
        val client = configuredClient()
        val response = client.post("/api/session") { contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.OK, response.status)
        val dto = response.body<SessionStateDto>()
        assertEquals(2, dto.remaining)
        assertNotNull(dto.currentMovie)
    }

    @Test fun `starting a session with no eligible movies is a clear error, not a broken session`() = testApplication {
        application { configure("game.test", FakePool(emptyList()), SessionStore()) }
        val client = configuredClient()
        val response = client.post("/api/session") { contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test fun `reject advances the session and accept ends it with the current movie`() = testApplication {
        application { configure("game.test", FakePool(listOf(testMovie("1"), testMovie("2"))), SessionStore()) }
        val client = configuredClient()
        val start = client.post("/api/session") { contentType(ContentType.Application.Json); setBody("{}") }.body<SessionStateDto>()

        val rejected = client.post("/api/session/${start.sessionId}/reject").body<SessionStateDto>()
        assertEquals(1, rejected.remaining)

        val accepted = client.post("/api/session/${start.sessionId}/accept")
        assertEquals(HttpStatusCode.OK, accepted.status)
        val acceptedBody = accepted.body<AcceptedDto>()
        assertEquals(rejected.currentMovie!!.id, acceptedBody.movie.id)
    }

    @Test fun `rejecting every movie reaches exhaustion and bring-back-5 restores cards`() = testApplication {
        application { configure("game.test", FakePool((1..3).map { testMovie("$it") }), SessionStore()) }
        val client = configuredClient()
        var state = client.post("/api/session") { contentType(ContentType.Application.Json); setBody("{}") }.body<SessionStateDto>()
        repeat(3) { state = client.post("/api/session/${state.sessionId}/reject").body<SessionStateDto>() }
        assertTrue(state.isExhausted)

        val restored = client.post("/api/session/${state.sessionId}/bring-back-5").body<SessionStateDto>()
        assertEquals(3, restored.remaining)
        assertTrue(!restored.isExhausted)
    }

    @Test fun `undo brings back the just-rejected movie`() = testApplication {
        application { configure("game.test", FakePool((1..2).map { testMovie("$it") }), SessionStore()) }
        val client = configuredClient()
        val start = client.post("/api/session") { contentType(ContentType.Application.Json); setBody("{}") }.body<SessionStateDto>()
        client.post("/api/session/${start.sessionId}/reject")
        val undone = client.post("/api/session/${start.sessionId}/undo").body<SessionStateDto>()
        assertEquals(start.currentMovie, undone.currentMovie)
    }

    @Test fun `an unknown session id is a 404, not a crash`() = testApplication {
        application { configure("game.test", FakePool(listOf(testMovie("1"))), SessionStore()) }
        val client = configuredClient()
        val response = client.post("/api/session/does-not-exist/reject")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test fun `accepting twice fails cleanly the second time`() = testApplication {
        application { configure("game.test", FakePool(listOf(testMovie("1"))), SessionStore()) }
        val client = configuredClient()
        val start = client.post("/api/session") { contentType(ContentType.Application.Json); setBody("{}") }.body<SessionStateDto>()
        client.post("/api/session/${start.sessionId}/accept")
        val second = client.post("/api/session/${start.sessionId}/accept")
        assertEquals(HttpStatusCode.NotFound, second.status) // the session was removed after the first accept
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.configuredClient() = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
}
