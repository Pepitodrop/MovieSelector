package de.luisbenedikt.movieselector.web

/**
 * Builds the backend URL for [path] (e.g. `session/abc/undo`).
 *
 * The API always lives at `api/` next to the page, so the app works under any deployment prefix
 * (`/play/movie-selector/` in production, `/` when served directly) without hardcoding either.
 * [pagePath] is `window.location.pathname`; a trailing file name such as `index.html` is dropped.
 *
 * GamePage's gateway rewrites every quoted string starting with `/` in served JS (prefixing it
 * with `/play/<game>`). Keep string literals in this module free of a leading slash, or the
 * gateway will corrupt the URL (see WebGatewaySafetyTest in :backend).
 *
 * A non-blank [override] (`window.MOVIE_SELECTOR_API_BASE`) replaces the derived base entirely.
 */
fun buildApiUrl(pagePath: String, override: String?, path: String): String {
    val base = if (!override.isNullOrBlank()) {
        override.trimEnd('/')
    } else {
        pagePath.substringBeforeLast('/') + '/' + "api"
    }
    return base + '/' + path.trimStart('/')
}

/** Joins URL segments with a slash Char, never a slash-leading string literal (see [buildApiUrl]). */
fun joinPath(vararg segments: String): String =
    segments.fold("") { acc, seg -> if (acc.isEmpty()) seg else acc + '/' + seg }

/** Prefers the backend's own `{"error": ...}` text; otherwise reports only the HTTP status. */
fun errorMessage(serverError: String?, httpStatus: Int): String =
    if (!serverError.isNullOrBlank()) serverError else "Request failed (HTTP $httpStatus)"
