package de.luisbenedikt.movieselector.backend

import de.luisbenedikt.movieselector.game.Movie
import de.luisbenedikt.movieselector.game.Provider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.boolOrFalse(): Boolean = (this as? JsonPrimitive)?.booleanOrNull == true
private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull
private fun JsonElement?.doubleOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull

/**
 * Maps Wencke's `movie-watchlist` content records onto the game engine's [Movie], applying the
 * three eligibility rules the spec requires: not watched, has at least one Netflix/Disney+/Prime
 * *subscription* stream (Wencke's own TMDB pipeline already excludes rental/purchase-only offers
 * -- see `TmdbMovieProvider.ExtractSubscriptionProviders` in the Wencke repo), and that streaming
 * availability was actually checked recently.
 *
 * "Stale" is defined here as older than [staleAfter] since Wencke's own `streamingCheckedAt`
 * timestamp (written by its 24h background refresh job). We use 48h, double that refresh
 * interval, so a single delayed refresh cycle doesn't spuriously exclude a title; anything
 * missing the timestamp entirely (never checked) is always excluded.
 *
 * Field names assume Wencke's default System.Text.Json camelCase serialization of its
 * `content_records` shape (`data` as a nested object). Verify against a real Wencke response
 * before production use -- see the deployment doc's Movie Selector data/auth verification step.
 */
class MovieMapper(private val staleAfter: Duration = Duration.ofHours(48), private val now: () -> Instant = Instant::now) {

    fun mapEligible(records: JsonArray): List<Movie> = records.mapNotNull { element ->
        (element as? JsonObject)?.let(::toEligibleMovie)
    }

    private fun toEligibleMovie(record: JsonObject): Movie? {
        val status = record["status"].stringOrNull()
        if (status != null && status != "published") return null
        if (record["archivedAt"].stringOrNull() != null) return null

        val data = record["data"] as? JsonObject ?: JsonObject(emptyMap())
        if (data["watched"].boolOrFalse()) return null

        val providers = buildSet {
            if (data["netflix"].boolOrFalse()) add(Provider.NETFLIX)
            if (data["disneyPlus"].boolOrFalse()) add(Provider.DISNEY_PLUS)
            if (data["primeVideo"].boolOrFalse()) add(Provider.PRIME_VIDEO)
        }
        if (providers.isEmpty()) return null

        val checkedAt = data["streamingCheckedAt"].stringOrNull()?.let(::parseInstantOrNull) ?: return null
        if (Duration.between(checkedAt, now()) > staleAfter) return null

        val id = record["id"].stringOrNull() ?: return null
        val title = record["title"].stringOrNull() ?: return null

        return Movie(
            id = id,
            title = title,
            year = record["eventDate"].stringOrNull()?.let(::parseInstantOrNull)
                ?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC).year },
            runtimeMinutes = data["runtimeMinutes"].intOrNull(),
            rating = data["tmdbScore"].doubleOrNull() ?: record["imdbScore"].doubleOrNull(),
            posterUrl = data["imageUrl"].stringOrNull(),
            providers = providers,
        )
    }

    private fun parseInstantOrNull(text: String): Instant? = try {
        Instant.parse(text)
    } catch (e: DateTimeParseException) {
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (e2: DateTimeParseException) {
            null
        }
    }
}
