package de.luisbenedikt.movieselector.backend

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private const val PAGE_SIZE = 100
private const val MAX_PAGES = 10
private const val SITE_ACCESS_COOKIE = "wencke-site-access"

class WenckeAuthException(message: String) : Exception(message)
class WenckeUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Talks to the real Wencke API as a single trusted server-side client, keeping Wencke's own
 * site-access session cookie in this process only. The browser never receives the Wencke site
 * password, the session cookie, or any other Wencke credential -- it only ever talks to our own
 * BFF (see [Routes]), which returns an opaque, unrelated session id.
 *
 * On a 401 (expired/rotated session) the cookie is dropped and one automatic re-login + retry is
 * attempted, mirroring how a real browser session would recover.
 */
class WenckeClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val sitePassword: String,
) {
    @Volatile private var sessionCookie: String? = null
    private val authMutex = Mutex()

    /**
     * Wencke returns the standard paginated content-list object `{items, page, pageSize, total}`
     * (pageSize is clamped to 100 server-side, which is also the watchlist cap). Pages are read
     * until `total` items are collected; [MAX_PAGES] bounds the loop against a misbehaving server.
     */
    suspend fun fetchMovieWatchlist(): JsonArray {
        val all = mutableListOf<JsonElement>()
        for (page in 1..MAX_PAGES) {
            val root = parseObject(requestMovieWatchlist(page))
            val items = root["items"] as? JsonArray
                ?: throw WenckeUnavailableException("Wencke movie-watchlist response had no \"items\" array")
            all.addAll(items)
            val total = (root["total"] as? JsonPrimitive)?.intOrNull
            if (items.isEmpty() || total == null || all.size >= total) return JsonArray(all)
        }
        throw WenckeUnavailableException("Wencke movie-watchlist exceeded $MAX_PAGES pages")
    }

    private fun parseObject(body: String): JsonObject {
        val element = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw WenckeUnavailableException("Wencke movie-watchlist response was not valid JSON", e)
        }
        return element as? JsonObject
            ?: throw WenckeUnavailableException("Wencke movie-watchlist response was not a JSON object")
    }

    private suspend fun requestMovieWatchlist(page: Int): String {
        val url = "$baseUrl/api/v1/movie-watchlist?page=$page&pageSize=$PAGE_SIZE"
        ensureAuthenticated()
        val response = try {
            httpClient.get(url) { header(HttpHeaders.Cookie, sessionCookie) }
        } catch (e: Exception) {
            throw WenckeUnavailableException("could not reach Wencke", e)
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            authMutex.withLock { sessionCookie = null }
            ensureAuthenticated()
            val retry = try {
                httpClient.get(url) { header(HttpHeaders.Cookie, sessionCookie) }
            } catch (e: Exception) {
                throw WenckeUnavailableException("could not reach Wencke on retry", e)
            }
            if (!retry.status.isSuccess()) {
                throw WenckeUnavailableException("Wencke movie-watchlist request failed after re-auth: ${retry.status}")
            }
            return retry.body()
        }
        if (!response.status.isSuccess()) {
            throw WenckeUnavailableException("Wencke movie-watchlist request failed: ${response.status}")
        }
        return response.body()
    }

    private suspend fun ensureAuthenticated() {
        if (sessionCookie != null) return
        authMutex.withLock {
            if (sessionCookie != null) return
            val response = try {
                httpClient.post("$baseUrl/api/v1/site-access/unlock") {
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject { put("password", JsonPrimitive(sitePassword)) },
                    ))
                }
            } catch (e: Exception) {
                throw WenckeUnavailableException("could not reach Wencke to authenticate", e)
            }
            if (response.status == HttpStatusCode.Unauthorized) {
                throw WenckeAuthException("Wencke rejected the configured site-access password")
            }
            if (!response.status.isSuccess()) {
                throw WenckeUnavailableException("Wencke site-access unlock failed: ${response.status}")
            }
            // Unlock sets several cookies (e.g. wencke.contributor first); only the site-access
            // cookie authenticates. Send that one alone and never expose it to the browser.
            sessionCookie = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                .map { it.substringBefore(';').trim() }
                .firstOrNull { it.startsWith("$SITE_ACCESS_COOKIE=") }
                ?: throw WenckeUnavailableException("Wencke unlock succeeded but returned no $SITE_ACCESS_COOKIE cookie")
        }
    }
}
