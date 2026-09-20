package de.luisbenedikt.movieselector.web

import kotlinx.browser.window
import org.w3c.fetch.Response

/** The backend is same-origin under the GamePage launcher (`/play/movie-selector/api/...`); see [buildApiUrl]. */
private fun apiUrl(path: String): String =
    buildApiUrl(window.location.pathname, js("window.MOVIE_SELECTOR_API_BASE") as? String, path)

external interface MovieJson {
    val id: String
    val title: String
    val year: Int?
    val runtimeMinutes: Int?
    val rating: Double?
    val posterUrl: String?
    val providers: Array<String>
}

external interface SessionStateJson {
    val sessionId: String
    val remaining: Int
    val isExhausted: Boolean
    val currentMovie: MovieJson?
}

external interface AcceptedJson {
    val movie: MovieJson
}

private fun jsonHeaders(): dynamic {
    val headers = js("({})")
    headers["Content-Type"] = "application/json"
    return headers
}

fun apiRequest(path: String, method: String, body: dynamic, onSuccess: (dynamic) -> Unit, onError: (String) -> Unit) {
    val init = js("({})")
    init.method = method
    init.headers = jsonHeaders()
    if (body != null) init.body = JSON.stringify(body)
    window.fetch(apiUrl(path), init).then { response: Response ->
        response.text().then { text ->
            val json = try { JSON.parse<dynamic>(text) } catch (e: Throwable) { null }
            if (response.ok && json != null) onSuccess(json)
            else onError(errorMessage(json?.error as? String, response.status.toInt()))
            null
        }.catch { onError("Could not read the server response (HTTP ${response.status}).") }
    }.catch { onError("Could not reach the server. Check your connection and try again.") }
}

fun startSession(providers: List<String>, runtime: String, onSuccess: (SessionStateJson) -> Unit, onError: (String) -> Unit) {
    val body = js("({})")
    body.providers = providers.toTypedArray()
    body.runtime = runtime
    apiRequest("session", "POST", body, { onSuccess(it.unsafeCast<SessionStateJson>()) }, onError)
}

fun sessionAction(sessionId: String, action: String, onSuccess: (SessionStateJson) -> Unit, onError: (String) -> Unit) {
    apiRequest(joinPath("session", sessionId, action), "POST", null, { onSuccess(it.unsafeCast<SessionStateJson>()) }, onError)
}

fun acceptSession(sessionId: String, onSuccess: (AcceptedJson) -> Unit, onError: (String) -> Unit) {
    apiRequest(joinPath("session", sessionId, "accept"), "POST", null, { onSuccess(it.unsafeCast<AcceptedJson>()) }, onError)
}
