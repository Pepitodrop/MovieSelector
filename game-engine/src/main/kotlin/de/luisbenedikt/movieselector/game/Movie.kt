package de.luisbenedikt.movieselector.game

/** A German subscription/flatrate streaming service the Movie Selector cares about. */
enum class Provider { NETFLIX, DISNEY_PLUS, PRIME_VIDEO }

/**
 * A single eligible movie: already filtered by the caller (Wencke BFF) down to titles that
 * exist on the watchlist, are not watched, and have at least one non-stale flatrate provider.
 */
data class Movie(
    val id: String,
    val title: String,
    val year: Int?,
    val runtimeMinutes: Int?,
    val rating: Double?,
    val posterUrl: String?,
    val providers: Set<Provider>,
)

enum class RuntimeFilter(val maxMinutes: Int?) {
    ANY(null),
    UP_TO_90(90),
    UP_TO_120(120),
    UP_TO_150(150),
}

data class Filters(
    val providers: Set<Provider> = setOf(Provider.NETFLIX, Provider.DISNEY_PLUS, Provider.PRIME_VIDEO),
    val runtime: RuntimeFilter = RuntimeFilter.ANY,
) {
    fun matches(movie: Movie): Boolean {
        if (movie.providers.none { it in providers }) return false
        val max = runtime.maxMinutes ?: return true
        return movie.runtimeMinutes != null && movie.runtimeMinutes <= max
    }
}
