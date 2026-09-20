package de.luisbenedikt.movieselector.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Required environment variables (see the top-level deployment doc for exact production values):
 * - WENCKE_BASE_URL: e.g. https://wencke.love -- never a value the browser can influence (no SSRF surface).
 * - WENCKE_SITE_PASSWORD: the real Wencke site-access password. Server-side only, never logged, never returned.
 * - PUBLIC_ORIGIN: the exact origin allowed to call this API (game.luisbenedikt.de in production).
 */
fun main() {
    val wenckeBaseUrl = requireEnv("WENCKE_BASE_URL")
    val wenckeSitePassword = requireEnv("WENCKE_SITE_PASSWORD")
    val publicOrigin = requireEnv("PUBLIC_ORIGIN")
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
        }
        // We parse the Wencke response ourselves (see MovieMapper) so a schema drift fails
        // loudly with our own error type, rather than a generic deserialization crash.
    }
    val wenckeClient = WenckeClient(httpClient, wenckeBaseUrl, wenckeSitePassword)
    val pool = MoviePoolCache(wenckeClient, MovieMapper())
    val store = SessionStore()
    val siteDirectory = System.getenv("SITE_DIRECTORY")

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        configure(publicOrigin, pool, store, siteDirectory)
    }.start(wait = true)
}

/**
 * [siteDirectory], if set, serves the web client's static build (index.html + JS bundle) at "/"
 * from the same container/process as the API -- one deployable unit per game, matching the
 * other GamePage games. Leave it null for tests, which only care about the /api routes.
 */
fun Application.configure(publicOrigin: String, pool: MoviePool, store: SessionStore, siteDirectory: String? = null) {
    install(CallLogging)
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(CORS) {
        allowHost(publicOrigin.removePrefix("https://").removePrefix("http://"), schemes = listOf("https", "http"))
        allowHeader(HttpHeaders.ContentType)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Get)
        // No allowCredentials(): the session id travels in the response/request body, not a
        // cookie, so this API carries no ambient authority and needs no CSRF protection either.
    }
    Routes(pool, store).install(this)
    if (siteDirectory != null) {
        routing {
            staticFiles("/", File(siteDirectory)) {
                default("index.html")
            }
        }
    }
}

private fun requireEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("required environment variable $name is not set")
