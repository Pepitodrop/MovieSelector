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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    suspend fun fetchMovieWatchlist(): JsonArray {
        val body = requestMovieWatchlist()
        return try {
            Json.parseToJsonElement(body) as? JsonArray
                ?: throw WenckeUnavailableException("Wencke movie-watchlist response was not a JSON array")
        } catch (e: Exception) {
            if (e is WenckeUnavailableException) throw e
            throw WenckeUnavailableException("Wencke movie-watchlist response was not valid JSON", e)
        }
    }

    private suspend fun requestMovieWatchlist(): String {
        ensureAuthenticated()
        val response = try {
            httpClient.get("$baseUrl/api/v1/movie-watchlist") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
        } catch (e: Exception) {
            throw WenckeUnavailableException("could not reach Wencke", e)
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            authMutex.withLock { sessionCookie = null }
            ensureAuthenticated()
            val retry = try {
                httpClient.get("$baseUrl/api/v1/movie-watchlist") { header(HttpHeaders.Cookie, sessionCookie) }
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
                        kotlinx.serialization.json.JsonObject.serializer(),
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
            val setCookie = response.headers[HttpHeaders.SetCookie]
                ?: throw WenckeUnavailableException("Wencke unlock succeeded but returned no session cookie")
            sessionCookie = setCookie.substringBefore(';')
        }
    }
}
