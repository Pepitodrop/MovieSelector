package com.luisbenedikt.movieselector

import com.luisbenedikt.movieselector.data.AcceptedDto
import com.luisbenedikt.movieselector.data.ApiResult
import com.luisbenedikt.movieselector.data.MovieApi
import com.luisbenedikt.movieselector.data.MovieDto
import com.luisbenedikt.movieselector.data.SessionStateDto
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun movie(id: String) = MovieDto(id, "Movie $id", 2020, 100, 7.0, null, listOf("NETFLIX"))

private class FakeApi(
    private val startResult: ApiResult<SessionStateDto> = ApiResult.Success(SessionStateDto("s1", 2, false, movie("1"))),
) : MovieApi {
    var lastAction: String? = null
    var actionResult: ApiResult<SessionStateDto> = ApiResult.Success(SessionStateDto("s1", 1, false, movie("2")))
    var acceptResult: ApiResult<AcceptedDto> = ApiResult.Success(AcceptedDto(movie("1")))

    override suspend fun startSession(providers: List<String>, runtime: String) = startResult
    override suspend fun action(sessionId: String, action: String): ApiResult<SessionStateDto> {
        lastAction = action
        return actionResult
    }
    override suspend fun accept(sessionId: String) = acceptResult
}

class GameStateTest {
    @Test fun `starting a game moves from FILTERS to CARD on success`() = runTest(UnconfinedTestDispatcher()) {
        val state = GameState(FakeApi())
        state.startGame(this)
        assertEquals(Screen.CARD, state.screen)
        assertEquals(2, state.remaining)
        assertEquals("1", state.currentMovie?.id)
    }

    @Test fun `a failed start moves to ERROR with the server message`() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(startResult = ApiResult.Failure("Wencke is unavailable"))
        val state = GameState(api)
        state.startGame(this)
        assertEquals(Screen.ERROR, state.screen)
        assertEquals("Wencke is unavailable", state.errorMessage)
    }

    @Test fun `starting with isExhausted true goes straight to REJECT_ALL`() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(startResult = ApiResult.Success(SessionStateDto("s1", 0, true, null)))
        val state = GameState(api)
        state.startGame(this)
        assertEquals(Screen.REJECT_ALL, state.screen)
    }

    @Test fun `reject calls the reject action and applies the new state`() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi()
        val state = GameState(api)
        state.startGame(this)
        state.reject(this)
        assertEquals("reject", api.lastAction)
        assertEquals(1, state.remaining)
        assertEquals("2", state.currentMovie?.id)
    }

    @Test fun `undo calls the undo action`() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi()
        val state = GameState(api)
        state.startGame(this)
        state.undo(this)
        assertEquals("undo", api.lastAction)
    }

    @Test fun `accept moves to RESULT with the selected movie`() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi()
        val state = GameState(api)
        state.startGame(this)
        state.accept(this)
        assertEquals(Screen.RESULT, state.screen)
        assertEquals("1", state.resultMovie?.id)
    }

    @Test fun `restart clears result and returns to FILTERS`() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi()
        val state = GameState(api)
        state.startGame(this)
        state.accept(this)
        state.restart()
        assertEquals(Screen.FILTERS, state.screen)
        assertEquals(null, state.resultMovie)
    }

    @Test fun `back from CARD restarts and is consumed`() = runTest(UnconfinedTestDispatcher()) {
        val state = GameState(FakeApi())
        state.startGame(this)
        assertTrue(state.handleBack())
        assertEquals(Screen.FILTERS, state.screen)
    }

    @Test fun `back from FILTERS is not consumed, letting the system handle it`() {
        val state = GameState(FakeApi())
        assertFalse(state.handleBack())
    }

    @Test fun `default providers are all three services`() {
        val state = GameState(FakeApi())
        assertEquals(setOf("NETFLIX", "DISNEY_PLUS", "PRIME_VIDEO"), state.selectedProviders)
    }
}
