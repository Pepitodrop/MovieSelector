package de.luisbenedikt.movieselector.web

import kotlinx.browser.window
import org.w3c.fetch.Response

/** The backend is same-origin under the GamePage launcher (`/play/movie-selector/api/...`). */
val API_BASE: String = js("(window.MOVIE_SELECTOR_API_BASE || '/api')") as String

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
    window.fetch("$API_BASE$path", init).then { response: Response ->
        response.json().then { json ->
            if (response.ok) onSuccess(json)
            else onError((json.asDynamic().error as? String) ?: "Request failed (HTTP ${response.status})")
            null
        }.catch { onError("The server sent back something unexpected.") }
    }.catch { onError("Could not reach the server. Check your connection and try again.") }
}

fun startSession(providers: List<String>, runtime: String, onSuccess: (SessionStateJson) -> Unit, onError: (String) -> Unit) {
    val body = js("({})")
    body.providers = providers.toTypedArray()
    body.runtime = runtime
    apiRequest("/session", "POST", body, { onSuccess(it.unsafeCast<SessionStateJson>()) }, onError)
}

fun sessionAction(sessionId: String, action: String, onSuccess: (SessionStateJson) -> Unit, onError: (String) -> Unit) {
    apiRequest("/session/$sessionId/$action", "POST", null, { onSuccess(it.unsafeCast<SessionStateJson>()) }, onError)
}

fun acceptSession(sessionId: String, onSuccess: (AcceptedJson) -> Unit, onError: (String) -> Unit) {
    apiRequest("/session/$sessionId/accept", "POST", null, { onSuccess(it.unsafeCast<AcceptedJson>()) }, onError)
}
