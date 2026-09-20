package de.luisbenedikt.movieselector.backend

import de.luisbenedikt.movieselector.game.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val FIXED_NOW = Instant.parse("2026-09-20T00:00:00Z")

private fun record(
    id: String = "id-1",
    title: String = "A Movie",
    status: String = "published",
    archivedAt: String? = null,
    watched: Boolean = false,
    netflix: Boolean = false,
    disneyPlus: Boolean = false,
    primeVideo: Boolean = false,
    streamingCheckedAt: String? = FIXED_NOW.toString(),
    runtimeMinutes: Int? = 100,
): String = """
    {
      "id": "$id",
      "title": "$title",
      "status": "$status",
      "archivedAt": ${archivedAt?.let { "\"$it\"" } ?: "null"},
      "data": {
        "watched": $watched,
        "netflix": $netflix,
        "disneyPlus": $disneyPlus,
        "primeVideo": $primeVideo,
        "streamingCheckedAt": ${streamingCheckedAt?.let { "\"$it\"" } ?: "null"},
        "runtimeMinutes": ${runtimeMinutes ?: "null"},
        "imageUrl": "https://example.test/poster.jpg"
      }
    }
""".trimIndent()

private fun parseAll(vararg records: String): JsonArray = Json.parseToJsonElement("[${records.joinToString(",")}]") as JsonArray

private val mapper = MovieMapper(staleAfter = Duration.ofHours(48), now = { FIXED_NOW })

class MovieMapperTest {
    @Test fun `watched movies are excluded`() {
        val movies = mapper.mapEligible(parseAll(record(watched = true, netflix = true)))
        assertTrue(movies.isEmpty())
    }

    @Test fun `netflix flatrate movies are included`() {
        val movies = mapper.mapEligible(parseAll(record(netflix = true)))
        assertEquals(setOf(Provider.NETFLIX), movies.single().providers)
    }

    @Test fun `disney plus flatrate movies are included`() {
        val movies = mapper.mapEligible(parseAll(record(disneyPlus = true)))
        assertEquals(setOf(Provider.DISNEY_PLUS), movies.single().providers)
    }

    @Test fun `prime video flatrate movies are included`() {
        val movies = mapper.mapEligible(parseAll(record(primeVideo = true)))
        assertEquals(setOf(Provider.PRIME_VIDEO), movies.single().providers)
    }

    @Test fun `a movie with no flatrate provider at all is excluded (covers rental-only titles)`() {
        // Wencke's own TMDB pipeline only ever sets these three booleans from "flatrate" offers
        // (see TmdbMovieProvider.ExtractSubscriptionProviders); a rental/purchase-only title
        // never has any of them true, so it's indistinguishable from -- and excluded exactly
        // like -- "not currently streamable at all".
        val movies = mapper.mapEligible(parseAll(record(netflix = false, disneyPlus = false, primeVideo = false)))
        assertTrue(movies.isEmpty())
    }

    @Test fun `missing streaming metadata (never checked) is excluded`() {
        val movies = mapper.mapEligible(parseAll(record(netflix = true, streamingCheckedAt = null)))
        assertTrue(movies.isEmpty())
    }

    @Test fun `stale streaming availability is excluded`() {
        val staleTimestamp = FIXED_NOW.minus(Duration.ofHours(49)).toString()
        val movies = mapper.mapEligible(parseAll(record(netflix = true, streamingCheckedAt = staleTimestamp)))
        assertTrue(movies.isEmpty())
    }

    @Test fun `just-under-the-staleness-threshold is still included`() {
        val freshTimestamp = FIXED_NOW.minus(Duration.ofHours(47)).toString()
        val movies = mapper.mapEligible(parseAll(record(netflix = true, streamingCheckedAt = freshTimestamp)))
        assertEquals(1, movies.size)
    }

    @Test fun `archived movies are excluded`() {
        val movies = mapper.mapEligible(parseAll(record(netflix = true, archivedAt = FIXED_NOW.toString())))
        assertTrue(movies.isEmpty())
    }

    @Test fun `non-published status is excluded`() {
        val movies = mapper.mapEligible(parseAll(record(netflix = true, status = "draft")))
        assertTrue(movies.isEmpty())
    }

    @Test fun `a movie eligible on multiple providers keeps all of them`() {
        val movies = mapper.mapEligible(parseAll(record(netflix = true, primeVideo = true)))
        assertEquals(setOf(Provider.NETFLIX, Provider.PRIME_VIDEO), movies.single().providers)
    }

    @Test fun `unparseable streamingCheckedAt is treated as missing, not crashing`() {
        val movies = mapper.mapEligible(parseAll(record(netflix = true, streamingCheckedAt = "not-a-date")))
        assertTrue(movies.isEmpty())
    }

    @Test fun `a non-object array entry is skipped rather than crashing the whole batch`() {
        val json = Json.parseToJsonElement("[${record(netflix = true)}, 42, \"oops\"]") as JsonArray
        val movies = mapper.mapEligible(json)
        assertEquals(1, movies.size)
    }
}
