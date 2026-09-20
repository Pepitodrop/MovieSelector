package com.luisbenedikt.movieselector

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.luisbenedikt.movieselector.data.ApiResult
import com.luisbenedikt.movieselector.data.MovieDto
import com.luisbenedikt.movieselector.data.MovieApi
import com.luisbenedikt.movieselector.data.SessionStateDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Screen { FILTERS, LOADING, CARD, REJECT_ALL, RESULT, ERROR }

val ALL_PROVIDERS = listOf("NETFLIX" to "Netflix", "DISNEY_PLUS" to "Disney+", "PRIME_VIDEO" to "Prime Video")
val RUNTIME_OPTIONS = listOf("ANY" to "Any", "UP_TO_90" to "≤ 90 min", "UP_TO_120" to "≤ 120 min", "UP_TO_150" to "≤ 150 min")

/** Holds the whole app's state. The Activity keeps this across rotation via `configChanges`. */
class GameState(private val api: MovieApi) {
    var screen by mutableStateOf(Screen.FILTERS)
        private set
    var selectedProviders = mutableSetOf("NETFLIX", "DISNEY_PLUS", "PRIME_VIDEO")
    var runtime by mutableStateOf("ANY")
    var remaining by mutableStateOf(0)
    var currentMovie by mutableStateOf<MovieDto?>(null)
    var resultMovie by mutableStateOf<MovieDto?>(null)
    var errorMessage by mutableStateOf("")
    private var sessionId: String? = null

    fun startGame(scope: CoroutineScope) {
        screen = Screen.LOADING
        scope.launch {
            when (val result = api.startSession(selectedProviders.toList(), runtime)) {
                is ApiResult.Success -> applySession(result.value)
                is ApiResult.Failure -> fail(result.message)
            }
        }
    }

    private fun applySession(session: SessionStateDto) {
        sessionId = session.sessionId
        remaining = session.remaining
        currentMovie = session.currentMovie
        screen = if (session.isExhausted) Screen.REJECT_ALL else Screen.CARD
    }

    private fun fail(message: String) {
        errorMessage = message
        screen = Screen.ERROR
    }

    fun reject(scope: CoroutineScope) = runAction(scope, "reject")
    fun undo(scope: CoroutineScope) = runAction(scope, "undo")
    fun reshuffleAll(scope: CoroutineScope) = runAction(scope, "reshuffle")
    fun bringBackLast5(scope: CoroutineScope) = runAction(scope, "bring-back-5")

    private fun runAction(scope: CoroutineScope, action: String) {
        val id = sessionId ?: return
        scope.launch {
            when (val result = api.action(id, action)) {
                is ApiResult.Success -> applySession(result.value)
                is ApiResult.Failure -> fail(result.message)
            }
        }
    }

    fun accept(scope: CoroutineScope) {
        val id = sessionId ?: return
        scope.launch {
            when (val result = api.accept(id)) {
                is ApiResult.Success -> {
                    resultMovie = result.value.movie
                    screen = Screen.RESULT
                }
                is ApiResult.Failure -> fail(result.message)
            }
        }
    }

    fun restart() {
        sessionId = null
        resultMovie = null
        errorMessage = ""
        screen = Screen.FILTERS
    }

    /** Android back behavior: from the card screen, back returns to filters (ending the session) instead of exiting the app. */
    fun handleBack(): Boolean = when (screen) {
        Screen.CARD, Screen.REJECT_ALL, Screen.RESULT, Screen.ERROR -> { restart(); true }
        Screen.LOADING -> true // swallow back while a request is in flight
        Screen.FILTERS -> false // let the system handle it (exit/previous activity)
    }
}
