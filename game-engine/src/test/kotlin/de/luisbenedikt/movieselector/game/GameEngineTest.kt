package de.luisbenedikt.movieselector.game

import de.luisbenedikt.movieselector.piet.PietTerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun movie(id: String, providers: Set<Provider> = setOf(Provider.NETFLIX), runtime: Int? = 100) =
    Movie(id = id, title = id, year = 2020, runtimeMinutes = runtime, rating = 7.5, posterUrl = null, providers = providers)

class PietShuffleTest {
    @Test fun `same seed always produces the same order`() {
        assertEquals(PietShuffle.shuffledOrder(12, 42L), PietShuffle.shuffledOrder(12, 42L))
    }

    @Test fun `different seeds usually produce different orders`() {
        val a = PietShuffle.shuffledOrder(12, 1L)
        val b = PietShuffle.shuffledOrder(12, 2L)
        assertFalse(a == b, "two different seeds produced the exact same order; check the LCG mixing")
    }

    @Test fun `output is always a valid permutation, for many sizes and seeds`() {
        for (n in 1..25) for (seed in listOf(0L, 1L, 42L, 1000L, 999999L)) {
            val order = PietShuffle.shuffledOrder(n, seed)
            assertEquals((0 until n).toList(), order.sorted(), "n=$n seed=$seed was not a permutation")
        }
    }

    @Test fun `size zero shuffles to an empty order`() {
        assertEquals(emptyList(), PietShuffle.shuffledOrder(0, 42L))
    }
}

class PietRejectAcceptUndoTest {
    @Test fun `reject decrements remaining and is not exhausted until it hits zero`() {
        val outcome = PietReject.reject(remainingBefore = 5)
        assertEquals(4, outcome.newRemaining)
        assertFalse(outcome.exhausted)
    }

    @Test fun `reject the last remaining movie reports exhausted`() {
        val outcome = PietReject.reject(remainingBefore = 1)
        assertEquals(0, outcome.newRemaining)
        assertTrue(outcome.exhausted)
    }

    @Test fun `accept echoes the selected index through Piet and ends the session`() {
        val outcome = PietAccept.accept(selectedIndex = 7)
        assertEquals(7, outcome.selectedIndex)
        assertEquals(0, outcome.remainingAfter)
    }

    @Test fun `undo steps back one position when history exists`() {
        val outcome = PietUndo.undo(currentPosition = 3, historyCount = 3)
        assertEquals(2, outcome.newPosition)
        assertTrue(outcome.didUndo)
    }

    @Test fun `undo at the very start is a no-op`() {
        val outcome = PietUndo.undo(currentPosition = 0, historyCount = 0)
        assertEquals(0, outcome.newPosition)
        assertFalse(outcome.didUndo)
    }
}

class MovieSelectorEngineTest {
    private val movies = (1..10).map { movie("m$it") }
    private val engine = MovieSelectorEngine(movies)

    @Test fun `start produces a full, deterministic order`() {
        val state = engine.start(seed = 1L)
        assertEquals(10, state.order.size)
        assertEquals(10, state.remaining)
        assertEquals(0, state.position)
        assertFalse(state.isExhausted)
    }

    @Test fun `reject advances one card and never mutates the movie catalogue`() {
        val state = engine.start(seed = 1L)
        val firstMovie = engine.currentMovie(state)
        val result = engine.reject(state) as RejectResult.NextCard
        assertEquals(9, result.state.remaining)
        assertTrue(movies.contains(firstMovie))
        // Rejecting never deletes or marks anything -- the catalogue passed in is untouched.
        assertEquals(10, movies.size)
    }

    @Test fun `accepting immediately returns the current movie with no further prompts`() {
        val state = engine.start(seed = 1L)
        val current = engine.currentMovie(state)
        val selected = engine.accept(state)
        assertEquals(current, selected)
    }

    @Test fun `undo brings back the just-rejected card as current again`() {
        var state = engine.start(seed = 1L)
        val firstMovie = engine.currentMovie(state)
        state = (engine.reject(state) as RejectResult.NextCard).state
        state = engine.undo(state)
        assertEquals(firstMovie, engine.currentMovie(state))
        assertEquals(10, state.remaining)
    }

    @Test fun `rejecting every movie reaches the reject-all state`() {
        var state = engine.start(seed = 1L)
        var lastResult: RejectResult? = null
        repeat(10) { lastResult = engine.reject(state).also { state = it.stateOf() } }
        assertTrue(lastResult is RejectResult.RejectedAll)
        assertTrue(state.isExhausted)
        assertEquals(0, state.remaining)
        assertEquals(null, engine.currentMovie(state))
    }

    @Test fun `accept fails once every movie has been rejected`() {
        var state = engine.start(seed = 1L)
        repeat(10) { state = engine.reject(state).stateOf() }
        assertFailsWith<IllegalStateException> { engine.accept(state) }
    }

    @Test fun `reshuffle all restarts with a fresh full-size order`() {
        var state = engine.start(seed = 1L)
        repeat(10) { state = engine.reject(state).stateOf() }
        val reshuffled = engine.reshuffleAll(seed = 99L)
        assertEquals(10, reshuffled.remaining)
        assertFalse(reshuffled.isExhausted)
    }

    @Test fun `bring back last 5 restores exactly 5 cards from the reject-all screen`() {
        var state = engine.start(seed = 1L)
        repeat(10) { state = engine.reject(state).stateOf() }
        val restored = engine.bringBackLast5(state)
        assertEquals(5, restored.remaining)
        assertFalse(restored.isExhausted)
    }

    @Test fun `bring back last 5 with fewer than 5 total restores all of them`() {
        val smallEngine = MovieSelectorEngine((1..3).map { movie("s$it") })
        var state = smallEngine.start(seed = 1L)
        repeat(3) { state = smallEngine.reject(state).stateOf() }
        val restored = smallEngine.bringBackLast5(state)
        assertEquals(3, restored.remaining)
    }

    @Test fun `bring back last 5 before exhaustion is rejected`() {
        val state = engine.start(seed = 1L)
        assertFailsWith<IllegalStateException> { engine.bringBackLast5(state) }
    }

    private fun RejectResult.stateOf(): SessionState = when (this) {
        is RejectResult.NextCard -> state
        is RejectResult.RejectedAll -> state
    }
}

class FiltersTest {
    @Test fun `default filters include all three providers and any runtime`() {
        val filters = Filters()
        assertTrue(filters.matches(movie("a", setOf(Provider.NETFLIX))))
        assertTrue(filters.matches(movie("b", setOf(Provider.DISNEY_PLUS))))
        assertTrue(filters.matches(movie("c", setOf(Provider.PRIME_VIDEO))))
    }

    @Test fun `provider filter excludes movies on unselected services`() {
        val filters = Filters(providers = setOf(Provider.NETFLIX))
        assertTrue(filters.matches(movie("a", setOf(Provider.NETFLIX))))
        assertFalse(filters.matches(movie("b", setOf(Provider.DISNEY_PLUS))))
    }

    @Test fun `a movie on multiple providers matches if any is selected`() {
        val filters = Filters(providers = setOf(Provider.PRIME_VIDEO))
        assertTrue(filters.matches(movie("a", setOf(Provider.NETFLIX, Provider.PRIME_VIDEO))))
    }

    @Test fun `runtime filter excludes movies over the cap`() {
        val filters = Filters(runtime = RuntimeFilter.UP_TO_90)
        assertTrue(filters.matches(movie("a", runtime = 89)))
        assertTrue(filters.matches(movie("b", runtime = 90)))
        assertFalse(filters.matches(movie("c", runtime = 91)))
    }

    @Test fun `runtime filter excludes movies with unknown runtime, except ANY`() {
        val unknownRuntime = movie("a", runtime = null)
        assertTrue(Filters(runtime = RuntimeFilter.ANY).matches(unknownRuntime))
        assertFalse(Filters(runtime = RuntimeFilter.UP_TO_150).matches(unknownRuntime))
    }

    @Test fun `eligibleMovies applies filters to a full pool`() {
        val pool = listOf(
            movie("nf", setOf(Provider.NETFLIX), runtime = 80),
            movie("dp", setOf(Provider.DISNEY_PLUS), runtime = 200),
            movie("pv", setOf(Provider.PRIME_VIDEO), runtime = 60),
        )
        val result = eligibleMovies(pool, Filters(providers = setOf(Provider.NETFLIX, Provider.PRIME_VIDEO), runtime = RuntimeFilter.UP_TO_90))
        assertEquals(listOf("nf", "pv"), result.map { it.id })
    }
}

class MalformedPietOutputTest {
    @Test fun `starting a session with zero eligible movies is rejected before touching Piet`() {
        assertFailsWith<IllegalArgumentException> { MovieSelectorEngine(emptyList()) }
    }

    @Test fun `a reject program starved of input fails loudly, not silently`() {
        // Real interpreter failure handling: a program that never gets the input it asks for
        // must surface as INPUT_EXHAUSTED, not silently return zero/empty as a fake answer.
        val grid = PietReject.buildProgram()
        val result = de.luisbenedikt.movieselector.piet.PietInterpreter()
            .run(grid, de.luisbenedikt.movieselector.piet.QueueInput(mutableListOf()))
        assertEquals(PietTerminationReason.INPUT_EXHAUSTED, result.terminationReason)
        assertTrue(result.output.isEmpty())
    }

    @Test fun `firstOutputs rejects a program that produced too few values`() {
        // Simulate a malformed/truncated Piet response: a program that only ever prints one
        // value when the caller expects two must be rejected, not silently zero-padded.
        val truncated = de.luisbenedikt.movieselector.piet.PietGridBuilder().start()
            .push(5).instr(de.luisbenedikt.movieselector.piet.PietOp.OUT_NUMBER).end()
        val result = de.luisbenedikt.movieselector.piet.PietInterpreter().run(truncated)
        assertFailsWith<SessionProgramException> { result.firstOutputs(2) }
    }
}
