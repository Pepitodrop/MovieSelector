package de.luisbenedikt.movieselector.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

private enum class Screen { FILTERS, LOADING, CARD, REJECT_ALL, RESULT, ERROR }

private object State {
    var screen: Screen = Screen.FILTERS
    val providers = mutableSetOf("NETFLIX", "DISNEY_PLUS", "PRIME_VIDEO")
    var runtime = "ANY"
    var sessionId: String? = null
    var remaining: Int = 0
    var currentMovie: MovieJson? = null
    var resultMovie: MovieJson? = null
    var errorMessage: String = ""
}

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun providerLabel(code: String): String = when (code) {
    "NETFLIX" -> "Netflix"
    "DISNEY_PLUS" -> "Disney+"
    "PRIME_VIDEO" -> "Prime Video"
    else -> code
}

fun main() {
    injectStyles()
    render()
    window.addEventListener("keydown", { event ->
        if (State.screen != Screen.CARD) return@addEventListener
        when ((event as KeyboardEvent).key) {
            "ArrowLeft" -> reject()
            "ArrowRight" -> accept()
        }
    })
}

private fun goToError(message: String) {
    State.errorMessage = message
    State.screen = Screen.ERROR
    render()
}

private fun startGame() {
    State.screen = Screen.LOADING
    render()
    startSession(State.providers.toList(), State.runtime, onSuccess = { session ->
        State.sessionId = session.sessionId
        State.remaining = session.remaining
        State.currentMovie = session.currentMovie
        State.screen = if (session.isExhausted) Screen.REJECT_ALL else Screen.CARD
        render()
    }, onError = ::goToError)
}

private fun applySession(session: SessionStateJson) {
    State.remaining = session.remaining
    State.currentMovie = session.currentMovie
    State.screen = if (session.isExhausted) Screen.REJECT_ALL else Screen.CARD
    render()
}

private fun reject() {
    val id = State.sessionId ?: return
    sessionAction(id, "reject", ::applySession, ::goToError)
}

private fun accept() {
    val id = State.sessionId ?: return
    acceptSession(id, onSuccess = { accepted ->
        State.resultMovie = accepted.movie
        State.screen = Screen.RESULT
        render()
    }, onError = ::goToError)
}

private fun undo() {
    val id = State.sessionId ?: return
    sessionAction(id, "undo", ::applySession, ::goToError)
}

private fun reshuffleAll() {
    val id = State.sessionId ?: return
    sessionAction(id, "reshuffle", ::applySession, ::goToError)
}

private fun bringBackLast5() {
    val id = State.sessionId ?: return
    sessionAction(id, "bring-back-5", ::applySession, ::goToError)
}

private fun restart() {
    State.sessionId = null
    State.resultMovie = null
    State.screen = Screen.FILTERS
    render()
}

private fun providerBadges(movie: MovieJson): String =
    movie.providers.joinToString(" ") { """<span class="badge">${escapeHtml(providerLabel(it))}</span>""" }

private fun movieCardHtml(movie: MovieJson): String = """
    <div class="poster" style="${movie.posterUrl?.let { "background-image:url('${escapeHtml(it)}')" } ?: ""}">
      ${if (movie.posterUrl == null) """<div class="poster-fallback">🎬</div>""" else ""}
    </div>
    <div class="card-info">
      <h2>${escapeHtml(movie.title)}</h2>
      <p class="meta">
        ${movie.year?.let { "<span>$it</span>" } ?: ""}
        ${movie.runtimeMinutes?.let { "<span>${it} min</span>" } ?: ""}
        ${movie.rating?.let { "<span>★ ${it}</span>" } ?: ""}
      </p>
      <div class="badges">${providerBadges(movie)}</div>
    </div>
""".trimIndent()

private fun render() {
    val root = document.getElementById("app") ?: return
    root.innerHTML = when (State.screen) {
        Screen.FILTERS -> filtersHtml()
        Screen.LOADING -> """<div class="center"><div class="spinner"></div><p>Loading your watchlist…</p></div>"""
        Screen.CARD -> cardScreenHtml()
        Screen.REJECT_ALL -> rejectAllHtml()
        Screen.RESULT -> resultHtml()
        Screen.ERROR -> errorHtml()
    }
    attachHandlers()
}

private fun filtersHtml(): String = """
    <div class="screen filters">
      <h1>🍿 Movie Selector</h1>
      <p class="subtitle">Swipe through your Wencke watchlist until something sticks.</p>
      <h3>Providers</h3>
      <div class="filter-row" id="provider-row">
        ${listOf("NETFLIX", "DISNEY_PLUS", "PRIME_VIDEO").joinToString("") { code ->
            """<label class="chip"><input type="checkbox" value="$code" ${if (code in State.providers) "checked" else ""}> ${providerLabel(code)}</label>"""
        }}
      </div>
      <h3>Runtime</h3>
      <div class="filter-row" id="runtime-row">
        ${listOf("ANY" to "Any", "UP_TO_90" to "≤ 90 min", "UP_TO_120" to "≤ 120 min", "UP_TO_150" to "≤ 150 min").joinToString("") { (value, label) ->
            """<label class="chip"><input type="radio" name="runtime" value="$value" ${if (State.runtime == value) "checked" else ""}> $label</label>"""
        }}
      </div>
      <button id="start-button" class="primary">Start</button>
    </div>
""".trimIndent()

private fun cardScreenHtml(): String {
    val movie = State.currentMovie ?: return rejectAllHtml()
    return """
        <div class="screen game">
          <div class="topbar">
            <span class="remaining">${State.remaining} left</span>
            <button id="undo-button" class="ghost">Undo</button>
          </div>
          <div class="card" id="movie-card" tabindex="0">
            ${movieCardHtml(movie)}
          </div>
          <div class="actions">
            <button id="reject-button" class="reject" aria-label="Reject">✕</button>
            <button id="accept-button" class="accept" aria-label="Select">🍿</button>
          </div>
          <p class="hint">Drag the card, use the buttons, or ← reject / → select.</p>
        </div>
    """.trimIndent()
}

private fun rejectAllHtml(): String = """
    <div class="screen center">
      <h1>YOU REJECTED EVERYTHING 😅</h1>
      <div class="actions column">
        <button id="reshuffle-button" class="primary">Reshuffle all movies</button>
        <button id="bring-back-button" class="ghost">Bring back last 5</button>
      </div>
    </div>
""".trimIndent()

private fun resultHtml(): String {
    val movie = State.resultMovie ?: return filtersHtml()
    return """
        <div class="screen center result">
          <h1>🍿 MOVIE TIME!</h1>
          <div class="card static">${movieCardHtml(movie)}</div>
          <button id="restart-button" class="primary">Play again</button>
        </div>
    """.trimIndent()
}

private fun errorHtml(): String = """
    <div class="screen center error">
      <h1>Something went wrong</h1>
      <p>${escapeHtml(State.errorMessage)}</p>
      <button id="retry-button" class="primary">Try again</button>
    </div>
""".trimIndent()

private fun attachHandlers() {
    document.getElementById("start-button")?.addEventListener("click", {
        val checkboxes = document.querySelectorAll("#provider-row input[type=checkbox]")
        for (i in 0 until checkboxes.length) {
            val input = checkboxes.item(i) as org.w3c.dom.HTMLInputElement
            if (input.checked) State.providers.add(input.value) else State.providers.remove(input.value)
        }
        (document.querySelector("#runtime-row input[type=radio]:checked") as? org.w3c.dom.HTMLInputElement)?.let {
            State.runtime = it.value
        }
        startGame()
    })
    document.getElementById("reject-button")?.addEventListener("click", { reject() })
    document.getElementById("accept-button")?.addEventListener("click", { accept() })
    document.getElementById("undo-button")?.addEventListener("click", { undo() })
    document.getElementById("reshuffle-button")?.addEventListener("click", { reshuffleAll() })
    document.getElementById("bring-back-button")?.addEventListener("click", { bringBackLast5() })
    document.getElementById("restart-button")?.addEventListener("click", { restart() })
    document.getElementById("retry-button")?.addEventListener("click", { restart() })
    attachDrag(document.getElementById("movie-card") as? HTMLElement)
}

private const val SWIPE_THRESHOLD_PX = 120.0

private fun attachDrag(card: HTMLElement?) {
    if (card == null) return
    var startX = 0.0
    var dx = 0.0
    var dragging = false

    fun setTransform(x: Double) {
        card.style.transform = "translateX(${x}px) rotate(${x / 20}deg)"
        card.style.opacity = (1 - kotlin.math.abs(x) / 400).coerceIn(0.4, 1.0).toString()
    }

    card.addEventListener("pointerdown", { event ->
        dragging = true
        val dynEvent = event.asDynamic()
        startX = dynEvent.clientX as Double
        card.asDynamic().setPointerCapture(dynEvent.pointerId)
    })
    card.addEventListener("pointermove", { event ->
        if (!dragging) return@addEventListener
        dx = (event.asDynamic().clientX as Double) - startX
        setTransform(dx)
    })
    val release = { _: Event ->
        if (dragging) {
            dragging = false
            when {
                dx <= -SWIPE_THRESHOLD_PX -> reject()
                dx >= SWIPE_THRESHOLD_PX -> accept()
                else -> setTransform(0.0)
            }
            dx = 0.0
        }
    }
    card.addEventListener("pointerup", release)
    card.addEventListener("pointercancel", release)
}

private fun injectStyles() {
    val style = document.createElement("style")
    style.textContent = CSS
    document.head?.appendChild(style)
}
