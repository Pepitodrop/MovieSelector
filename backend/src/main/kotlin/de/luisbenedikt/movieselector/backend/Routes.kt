package de.luisbenedikt.movieselector.backend

import de.luisbenedikt.movieselector.game.Filters
import de.luisbenedikt.movieselector.game.Movie
import de.luisbenedikt.movieselector.game.Provider
import de.luisbenedikt.movieselector.game.RejectResult
import de.luisbenedikt.movieselector.game.RuntimeFilter
import de.luisbenedikt.movieselector.game.eligibleMovies
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class MovieDto(
    val id: String,
    val title: String,
    val year: Int?,
    val runtimeMinutes: Int?,
    val rating: Double?,
    val posterUrl: String?,
    val providers: List<String>,
)

private fun Movie.toDto() = MovieDto(id, title, year, runtimeMinutes, rating, posterUrl, providers.map { it.name })

@Serializable
data class FiltersDto(
    val providers: List<String> = listOf("NETFLIX", "DISNEY_PLUS", "PRIME_VIDEO"),
    val runtime: String = "ANY",
) {
    fun toFilters() = Filters(
        providers = providers.mapNotNull { runCatching { Provider.valueOf(it) }.getOrNull() }.toSet(),
        runtime = runCatching { RuntimeFilter.valueOf(runtime) }.getOrDefault(RuntimeFilter.ANY),
    )
}

@Serializable
data class SessionStateDto(
    val sessionId: String,
    val remaining: Int,
    val isExhausted: Boolean,
    val currentMovie: MovieDto?,
)

@Serializable
data class AcceptedDto(val movie: MovieDto)

@Serializable
data class ErrorDto(val error: String)

/**
 * The browser-facing API. Every response here is either a fully public health check or scoped to
 * an opaque [SessionStore] token the browser was handed -- never anything from Wencke directly
 * (no Wencke cookies, no raw unfiltered watchlist, no site password).
 */
class Routes(private val pool: MoviePool, private val store: SessionStore) {

    fun install(app: Application) {
        app.routing {
            route("/api") {
                get("/health") { call.respond(mapOf("status" to "ok")) }

                post("/session") {
                    val filters = runCatching { call.receive<FiltersDto>() }.getOrDefault(FiltersDto()).toFilters()
                    val allMovies = try {
                        pool.get()
                    } catch (e: WenckeAuthException) {
                        return@post call.respond(HttpStatusCode.BadGateway, ErrorDto("Wencke authentication failed"))
                    } catch (e: WenckeUnavailableException) {
                        return@post call.respond(HttpStatusCode.BadGateway, ErrorDto("Wencke is unavailable"))
                    }
                    val eligible = eligibleMovies(allMovies, filters)
                    if (eligible.isEmpty()) {
                        return@post call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("no eligible movies match these filters"))
                    }
                    val (token, session) = store.create(eligible, Random.nextLong())
                    call.respond(session.toDto(token))
                }

                route("/session/{id}") {
                    post("/reject") { handleAction { session ->
                        session.state = when (val result = session.engine.reject(session.state)) {
                            is RejectResult.NextCard -> result.state
                            is RejectResult.RejectedAll -> result.state
                        }
                    } }
                    post("/undo") { handleAction { session -> session.state = session.engine.undo(session.state) } }
                    post("/reshuffle") { handleAction { session -> session.state = session.engine.reshuffleAll(Random.nextLong()) } }
                    post("/bring-back-5") { handleAction { session -> session.state = session.engine.bringBackLast5(session.state) } }
                    post("/accept") {
                        val id = call.parameters["id"]!!
                        val session = store.get(id)
                            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorDto("unknown or expired session"))
                        val movie = try {
                            session.engine.accept(session.state)
                        } catch (e: IllegalStateException) {
                            return@post call.respond(HttpStatusCode.Conflict, ErrorDto(e.message ?: "cannot accept"))
                        }
                        store.remove(id)
                        call.respond(AcceptedDto(movie.toDto()))
                    }
                }
            }
        }
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.handleAction(mutate: (GameSession) -> Unit) {
        val id = call.parameters["id"]!!
        val session = store.get(id)
            ?: return call.respond(HttpStatusCode.NotFound, ErrorDto("unknown or expired session"))
        try {
            mutate(session)
        } catch (e: IllegalStateException) {
            return call.respond(HttpStatusCode.Conflict, ErrorDto(e.message ?: "invalid action for this session state"))
        }
        call.respond(session.toDto(id))
    }

    private fun GameSession.toDto(token: String) = SessionStateDto(
        sessionId = token,
        remaining = state.remaining,
        isExhausted = state.isExhausted,
        currentMovie = engine.currentMovie(state)?.toDto(),
    )
}
