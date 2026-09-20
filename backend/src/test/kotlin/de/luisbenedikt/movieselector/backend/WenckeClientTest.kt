package de.luisbenedikt.movieselector.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val BASE_URL = "https://wencke.test"

class WenckeClientTest {
    @Test fun `authenticated success fetches the watchlist using the unlock session cookie`() = runBlocking {
        var sawCookieOnFetch = false
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/api/v1/site-access/unlock" ->
                    respond("{\"authenticated\":true}", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "wencke_session=abc123; Path=/; HttpOnly"))
                request.url.encodedPath == "/api/v1/movie-watchlist" -> {
                    sawCookieOnFetch = request.headers[HttpHeaders.Cookie] == "wencke_session=abc123"
                    respond("[]", HttpStatusCode.OK)
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val client = WenckeClient(HttpClient(engine), BASE_URL, "correct-password")
        val result = client.fetchMovieWatchlist()
        assertEquals(JsonArray(emptyList()), result)
        assertTrue(sawCookieOnFetch, "the movie-watchlist request must carry the session cookie from unlock")
    }

    @Test fun `unauthenticated site password is reported as an auth failure`() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/site-access/unlock" -> respond("{}", HttpStatusCode.Unauthorized)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val client = WenckeClient(HttpClient(engine), BASE_URL, "wrong-password")
        assertFailsWith<WenckeAuthException> { client.fetchMovieWatchlist() }
    }

    @Test fun `an expired session is transparently re-authenticated and retried once`() = runBlocking {
        var unlockCalls = 0
        var fetchCalls = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/site-access/unlock" -> {
                    unlockCalls++
                    respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "wencke_session=session$unlockCalls; Path=/"))
                }
                "/api/v1/movie-watchlist" -> {
                    fetchCalls++
                    // First attempt looks expired (401); the retry (with the fresh cookie) succeeds.
                    if (fetchCalls == 1) respond("{}", HttpStatusCode.Unauthorized) else respond("[]", HttpStatusCode.OK)
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val client = WenckeClient(HttpClient(engine), BASE_URL, "correct-password")
        val result = client.fetchMovieWatchlist()
        assertEquals(JsonArray(emptyList()), result)
        assertEquals(2, unlockCalls, "expiry must trigger exactly one re-login")
        assertEquals(2, fetchCalls)
    }

    @Test fun `wencke unavailable surfaces as WenckeUnavailableException, not a crash`() = runBlocking {
        val engine = MockEngine { throw java.io.IOException("connection refused") }
        val client = WenckeClient(HttpClient(engine), BASE_URL, "correct-password")
        assertFailsWith<WenckeUnavailableException> { client.fetchMovieWatchlist() }
    }

    @Test fun `wencke timeout surfaces as WenckeUnavailableException`() = runBlocking {
        val engine = MockEngine { throw java.util.concurrent.TimeoutException("timed out") }
        val client = WenckeClient(HttpClient(engine), BASE_URL, "correct-password")
        assertFailsWith<WenckeUnavailableException> { client.fetchMovieWatchlist() }
    }

    @Test fun `malformed (non-JSON) watchlist response is rejected, not silently emptied`() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/site-access/unlock" -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "wencke_session=abc; Path=/"))
                "/api/v1/movie-watchlist" -> respond("<html>not json</html>", HttpStatusCode.OK)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val client = WenckeClient(HttpClient(engine), BASE_URL, "correct-password")
        assertFailsWith<WenckeUnavailableException> { client.fetchMovieWatchlist() }
    }

    @Test fun `a watchlist response that is valid JSON but not an array is rejected`() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/site-access/unlock" -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "wencke_session=abc; Path=/"))
                "/api/v1/movie-watchlist" -> respond("{\"not\":\"an array\"}", HttpStatusCode.OK)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val client = WenckeClient(HttpClient(engine), BASE_URL, "correct-password")
        assertFailsWith<WenckeUnavailableException> { client.fetchMovieWatchlist() }
    }
}
