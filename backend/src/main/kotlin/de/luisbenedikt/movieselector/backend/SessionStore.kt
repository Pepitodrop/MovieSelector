package de.luisbenedikt.movieselector.backend

import de.luisbenedikt.movieselector.game.Movie
import de.luisbenedikt.movieselector.game.MovieSelectorEngine
import de.luisbenedikt.movieselector.game.SessionState
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GameSession(val engine: MovieSelectorEngine, @Volatile var state: SessionState, @Volatile var lastAccess: Instant)

/**
 * Server-side session store, keyed by an opaque token handed to the browser. This token carries
 * no Wencke identity or secret -- it only looks up which movies and shuffle order this particular
 * player is mid-session with. Sessions idle for longer than [ttl] are evicted on the next sweep.
 */
class SessionStore(private val ttl: java.time.Duration = java.time.Duration.ofHours(6)) {
    private val sessions = ConcurrentHashMap<String, GameSession>()

    fun create(eligible: List<Movie>, seed: Long): Pair<String, GameSession> {
        sweep()
        val engine = MovieSelectorEngine(eligible)
        val session = GameSession(engine, engine.start(seed), Instant.now())
        val token = UUID.randomUUID().toString()
        sessions[token] = session
        return token to session
    }

    fun get(token: String): GameSession? = sessions[token]?.also { it.lastAccess = Instant.now() }

    fun remove(token: String) {
        sessions.remove(token)
    }

    private fun sweep() {
        val cutoff = Instant.now().minus(ttl)
        sessions.entries.removeIf { it.value.lastAccess.isBefore(cutoff) }
    }
}
