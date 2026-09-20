package de.luisbenedikt.movieselector.backend

import de.luisbenedikt.movieselector.game.Movie
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

interface MoviePool {
    suspend fun get(): List<Movie>
}

/**
 * Caches the eligible movie pool for a short time so every filter change / new session doesn't
 * re-fetch and re-authenticate against Wencke. The browser never sees this cache directly -- it
 * only ever gets back the already-filtered [Movie] list for its own session (see [Routes]).
 */
class MoviePoolCache(
    private val wenckeClient: WenckeClient,
    private val mapper: MovieMapper,
    private val ttl: Duration = Duration.ofMinutes(5),
    private val now: () -> Instant = Instant::now,
) : MoviePool {
    private val mutex = Mutex()
    private var cached: List<Movie>? = null
    private var fetchedAt: Instant? = null

    override suspend fun get(): List<Movie> {
        cached?.let { movies -> fetchedAt?.let { if (Duration.between(it, now()) <= ttl) return movies } }
        mutex.withLock {
            cached?.let { movies -> fetchedAt?.let { if (Duration.between(it, now()) <= ttl) return movies } }
            val records = wenckeClient.fetchMovieWatchlist()
            val movies = mapper.mapEligible(records)
            cached = movies
            fetchedAt = now()
            return movies
        }
    }
}
