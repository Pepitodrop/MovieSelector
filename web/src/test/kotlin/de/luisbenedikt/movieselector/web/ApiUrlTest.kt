package de.luisbenedikt.movieselector.web

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiUrlTest {
    @Test fun productionPrefixKeepsApiUnderThePrefix() {
        assertEquals("/play/movie-selector/api/session", buildApiUrl("/play/movie-selector/", null, "/session"))
    }

    @Test fun indexHtmlIsDropped() {
        assertEquals("/play/movie-selector/api/session", buildApiUrl("/play/movie-selector/index.html", null, "/session"))
    }

    @Test fun rootDeployment() {
        assertEquals("/api/session", buildApiUrl("/", null, "/session"))
        assertEquals("/api/session", buildApiUrl("/index.html", null, "/session"))
    }

    @Test fun overrideWinsAndTrailingSlashesAreNormalised() {
        assertEquals("https://x.test/api/session", buildApiUrl("/play/movie-selector/", "https://x.test/api", "/session"))
        assertEquals("https://x.test/api/session", buildApiUrl("/", "https://x.test/api///", "/session"))
        assertEquals("https://x.test/api/session", buildApiUrl("/", "https://x.test/api/", "session"))
    }

    @Test fun blankOverrideIsIgnored() {
        assertEquals("/api/session", buildApiUrl("/", "", "/session"))
        assertEquals("/api/session", buildApiUrl("/", null, "/session"))
    }

    @Test fun everyGameActionStaysUnderThePrefix() {
        val page = "/play/movie-selector/"
        val paths = listOf("/session", "/session/abc/reject", "/session/abc/undo", "/session/abc/reshuffle",
            "/session/abc/bring-back-5", "/session/abc/accept")
        for (p in paths) {
            val url = buildApiUrl(page, null, p)
            assertEquals("/play/movie-selector/api$p", url)
            assertEquals(1, Regex("/play/movie-selector").findAll(url).count(), "prefix must not be duplicated: $url")
        }
    }

    @Test fun errorMessageUsesServerTextElseHttpStatus() {
        assertEquals("Wencke is unavailable", errorMessage("Wencke is unavailable", 502))
        assertEquals("Request failed (HTTP 404)", errorMessage(null, 404))
        assertEquals("Request failed (HTTP 404)", errorMessage("", 404))
    }
}
