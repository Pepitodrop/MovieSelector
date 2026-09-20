package de.luisbenedikt.movieselector.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val BASE_URL = "https://wencke.test"
private const val EMPTY_PAGE = """{"items":[],"page":1,"pageSize":100,"total":0}"""

private fun pageJson(from: Int, count: Int, total: Int, page: Int) =
    """{"items":[${(from until from + count).joinToString(",") { """{"id":"$it"}""" }}],"page":$page,"pageSize":100,"total":$total}"""

class WenckeClientTest {
    @Test fun `authenticated success fetches the watchlist using the unlock session cookie`() = runBlocking {
        var sawCookieOnFetch = false
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/api/v1/site-access/unlock" ->
                    respond("{\"authenticated\":true}", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "wencke_session=abc123; Path=/; HttpOnly"))
                request.url.encodedPath == "/api/v1/movie-watchlist" -> {
                    sawCookieOnFetch = request.headers[HttpHeaders.Cookie] == "wencke_session=abc123"
                    respond(EMPTY_PAGE, HttpStatusCode.OK)
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val client = WenckeClient(HttpClient(engine), BASE_URL, "correct-password")
        val result = client.fetchMovieWatchlist()
        assertEquals(0, result.size)
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
                    if (fetchCalls == 1) respond("{}", HttpStatusCode.Unauthorized) else respond(EMPTY_PAGE, HttpStatusCode.OK)
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val client = WenckeClient(HttpClient(engine), BASE_URL, "correct-password")
        val result = client.fetchMovieWatchlist()
        assertEquals(0, result.size)
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

    @Test fun `paginated response items are extracted and page 1 requests pageSize 100`() = runBlocking {
        var query = ""
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/site-access/unlock" -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "wencke_session=abc; Path=/"))
                "/api/v1/movie-watchlist" -> {
                    query = request.url.encodedQuery
                    respond(pageJson(0, 2, 2, 1), HttpStatusCode.OK)
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val result = WenckeClient(HttpClient(engine), BASE_URL, "pw").fetchMovieWatchlist()
        assertEquals(2, result.size)
        assertEquals("page=1&pageSize=100", query)
    }

    @Test fun `empty items yields an empty list`() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/site-access/unlock" -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "wencke_session=abc; Path=/"))
                else -> respond(EMPTY_PAGE, HttpStatusCode.OK)
            }
        }
        assertEquals(0, WenckeClient(HttpClient(engine), BASE_URL, "pw").fetchMovieWatchlist().size)
    }

    @Test fun `multiple pages are followed until total is collected`() = runBlocking {
        val pages = mutableListOf<String>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/site-access/unlock" -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "wencke_session=abc; Path=/"))
                else -> {
                    val page = request.url.parameters["page"]!!.toInt()
                    pages += page.toString()
                    respond(if (page == 1) pageJson(0, 100, 130, 1) else pageJson(100, 30, 130, 2), HttpStatusCode.OK)
                }
            }
        }
        assertEquals(130, WenckeClient(HttpClient(engine), BASE_URL, "pw").fetchMovieWatchlist().size)
        assertEquals(listOf("1", "2"), pages)
    }

    @Test fun `a server that never reaches total is bounded, not looped forever`() = runBlocking {
        var calls = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/site-access/unlock" -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "wencke_session=abc; Path=/"))
                else -> { calls++; respond(pageJson(0, 1, 1_000_000, calls), HttpStatusCode.OK) }
            }
        }
        assertFailsWith<WenckeUnavailableException> { WenckeClient(HttpClient(engine), BASE_URL, "pw").fetchMovieWatchlist() }
        assertEquals(10, calls)
    }

    @Test fun `a bare JSON array (old assumed contract) is rejected`() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/site-access/unlock" -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "wencke_session=abc; Path=/"))
                else -> respond("[]", HttpStatusCode.OK)
            }
        }
        assertFailsWith<WenckeUnavailableException> { WenckeClient(HttpClient(engine), BASE_URL, "pw").fetchMovieWatchlist() }
    }

    @Test fun `a watchlist response that is is a JSON object without items is rejected`() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/site-access/unlock" -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "wencke_session=abc; Path=/"))
                "/api/v1/movie-watchlist" -> respond("{\"not\":\"a page\"}", HttpStatusCode.OK)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val client = WenckeClient(HttpClient(engine), BASE_URL, "correct-password")
        assertFailsWith<WenckeUnavailableException> { client.fetchMovieWatchlist() }
    }

    // --- Read-only contract -------------------------------------------------------------------
    // Wencke is the source of truth; Movie Selector must never mutate it. These tests fail loudly
    // if a future change adds a write path against Wencke's watchlist.

    @Test fun `a full fetch, including the re-auth retry path, never sends a write method to Wencke`() = runBlocking {
        val seenRequests = mutableListOf<Pair<String, String>>() // method to path
        val engine = MockEngine { request ->
            seenRequests += request.method.value to request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/v1/site-access/unlock" -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "wencke_session=abc; Path=/"))
                "/api/v1/movie-watchlist" -> respond(EMPTY_PAGE, HttpStatusCode.OK)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val client = WenckeClient(HttpClient(engine), BASE_URL, "correct-password")
        client.fetchMovieWatchlist()

        assertTrue(seenRequests.isNotEmpty(), "the mock engine recorded no requests")
        val writeMethods = setOf("POST", "PUT", "PATCH", "DELETE")
        for ((method, path) in seenRequests) {
            if (path == "/api/v1/site-access/unlock") {
                assertEquals("POST", method, "only the login/unlock call may be non-GET")
            } else {
                assertTrue(
                    method !in writeMethods,
                    "$method $path is a write against Wencke; Movie Selector must be read-only after login",
                )
                assertEquals("GET", method, "every Wencke call besides login/unlock must be GET, got $method $path")
            }
        }
    }

    @Test fun `WenckeClient's source never references a write HTTP method`() {
        val source = java.io.File("src/main/kotlin/de/luisbenedikt/movieselector/backend/WenckeClient.kt").readText()
        for (writeCall in listOf("httpClient.put(", "httpClient.patch(", "httpClient.delete(")) {
            assertTrue(writeCall !in source, "WenckeClient.kt must never call $writeCall against Wencke")
        }
        // Exactly one POST is allowed: the site-access/unlock login. Any second POST would be a
        // write path against Wencke and must not exist.
        val postCount = Regex("""httpClient\.post\(""").findAll(source).count()
        assertEquals(1, postCount, "WenckeClient.kt must issue exactly one POST (the unlock login)")
    }
}
