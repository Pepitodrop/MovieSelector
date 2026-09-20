package com.luisbenedikt.movieselector

import com.luisbenedikt.movieselector.data.ApiResult
import com.luisbenedikt.movieselector.data.MovieSelectorApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val BASE = "https://game.luisbenedikt.de/play/movie-selector/api"
private val JSON = headersOf(HttpHeaders.ContentType, "application/json")
private const val SESSION =
    """{"sessionId":"abc","remaining":25,"isExhausted":false,"currentMovie":{"id":"m1","title":"T","year":2001,"runtimeMinutes":110,"rating":7.4,"posterUrl":"https://image.tmdb.org/t/p/w780/x.jpg","providers":["NETFLIX"],"extra":"ignored"}}"""

class MovieSelectorApiTest {
    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        MovieSelectorApi(BASE, HttpClient(MockEngine { handler(it) }))

    @Test fun `session creation posts filters to the prefixed endpoint and parses the session`() = runBlocking<Unit> {
        var method: HttpMethod? = null; var url = ""; var body = ""
        val result = api { r ->
            method = r.method; url = r.url.toString(); body = String(r.body.toByteArray())
            respond(SESSION, HttpStatusCode.OK, JSON)
        }.startSession(listOf("NETFLIX", "DISNEY_PLUS"), "UP_TO_120")
        assertEquals(HttpMethod.Post, method)
        assertEquals("$BASE/session", url)
        assertTrue("\"providers\":[\"NETFLIX\",\"DISNEY_PLUS\"]" in body && "\"runtime\":\"UP_TO_120\"" in body, body)
        val s = assertIs<ApiResult.Success<*>>(result).value as com.luisbenedikt.movieselector.data.SessionStateDto
        assertEquals("abc", s.sessionId); assertEquals(25, s.remaining)
        assertEquals("https://image.tmdb.org/t/p/w780/x.jpg", s.currentMovie?.posterUrl)
    }

    @Test fun `every game action posts under the prefixed session path`() = runBlocking<Unit> {
        val seen = mutableListOf<String>()
        val a = api { r -> seen += "${r.method.value} ${r.url.encodedPath}"; respond(SESSION, HttpStatusCode.OK, JSON) }
        for (action in listOf("reject", "undo", "reshuffle", "bring-back-5")) a.action("abc", action)
        assertEquals(
            listOf("reject", "undo", "reshuffle", "bring-back-5").map { "POST /play/movie-selector/api/session/abc/$it" },
            seen,
        )
    }

    @Test fun `accept posts to accept and parses the movie`() = runBlocking<Unit> {
        var path = ""
        val r = api { req -> path = req.url.encodedPath; respond("""{"movie":{"id":"m1","title":"T"}}""", HttpStatusCode.OK, JSON) }.accept("abc")
        assertEquals("/play/movie-selector/api/session/abc/accept", path)
        assertEquals("m1", (assertIs<ApiResult.Success<*>>(r).value as com.luisbenedikt.movieselector.data.AcceptedDto).movie.id)
    }

    @Test fun `server error json is surfaced`() = runBlocking<Unit> {
        val r = api { respond("""{"error":"Wencke is unavailable"}""", HttpStatusCode.BadGateway, JSON) }.startSession(listOf("NETFLIX"), "ANY")
        assertEquals("Wencke is unavailable", assertIs<ApiResult.Failure>(r).message)
    }

    @Test fun `non-json error reports the HTTP status`() = runBlocking<Unit> {
        val r = api { respond("<html>nope</html>", HttpStatusCode.NotFound) }.startSession(listOf("NETFLIX"), "ANY")
        assertEquals("Request failed (HTTP 404)", assertIs<ApiResult.Failure>(r).message)
    }

    @Test fun `malformed success body is an error, not a crash`() = runBlocking<Unit> {
        val r = api { respond("not json", HttpStatusCode.OK, JSON) }.startSession(listOf("NETFLIX"), "ANY")
        assertIs<ApiResult.Failure>(r)
    }

    @Test fun `network failure is reported as unreachable`() = runBlocking<Unit> {
        val r = MovieSelectorApi(BASE, HttpClient(MockEngine { throw java.io.IOException("down") })).startSession(listOf("NETFLIX"), "ANY")
        assertTrue(assertIs<ApiResult.Failure>(r).message.startsWith("Could not reach the server"))
    }
}
