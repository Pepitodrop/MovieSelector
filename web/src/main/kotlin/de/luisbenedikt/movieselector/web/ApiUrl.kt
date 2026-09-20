package de.luisbenedikt.movieselector.web

/**
 * Builds the backend URL for [path] (e.g. "/session").
 *
 * The API always lives at `api/` next to the page, so the app works under any deployment prefix
 * (`/play/movie-selector/` in production, `/` when served directly) without hardcoding either.
 * [pagePath] is `window.location.pathname`; a trailing file name such as `index.html` is dropped.
 * A non-blank [override] (`window.MOVIE_SELECTOR_API_BASE`) replaces the derived base entirely.
 */
fun buildApiUrl(pagePath: String, override: String?, path: String): String {
    val base = if (!override.isNullOrBlank()) {
        override.trimEnd('/')
    } else {
        pagePath.substringBeforeLast('/') + "/api"
    }
    return base + "/" + path.trimStart('/')
}

/** Prefers the backend's own `{"error": ...}` text; otherwise reports only the HTTP status. */
fun errorMessage(serverError: String?, httpStatus: Int): String =
    if (!serverError.isNullOrBlank()) serverError else "Request failed (HTTP $httpStatus)"
