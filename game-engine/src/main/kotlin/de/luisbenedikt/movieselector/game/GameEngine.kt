package de.luisbenedikt.movieselector.game

/**
 * A session's state: `order` is a fixed shuffled sequence of indices into the caller's eligible
 * movie list, and `position` is how far through it the player has advanced by rejecting.
 * `order[position - 1]` (if any) is always the most recently rejected movie, and
 * `order[position]` (if any) is the current card -- so undo and "bring back last 5" are pure
 * position adjustments; nothing needs a separate history stack.
 */
data class SessionState(val order: List<Int>, val position: Int) {
    val remaining: Int get() = order.size - position
    val isExhausted: Boolean get() = position >= order.size
    fun currentIndex(): Int? = order.getOrNull(position)
}

sealed interface RejectResult {
    data class NextCard(val state: SessionState) : RejectResult
    data class RejectedAll(val state: SessionState) : RejectResult
}

class MovieSelectorEngine(private val eligible: List<Movie>) {
    init {
        require(eligible.isNotEmpty()) { "at least one eligible movie is required to start a session" }
    }

    /** Starts a fresh session: a Piet-computed seeded shuffle over every eligible movie. */
    fun start(seed: Long): SessionState {
        val order = PietShuffle.shuffledOrder(eligible.size, seed)
        return SessionState(order, 0)
    }

    fun currentMovie(state: SessionState): Movie? = state.currentIndex()?.let { eligible[it] }

    /** Rejects the current card for this session only. Advances position via the Piet reject program. */
    fun reject(state: SessionState): RejectResult {
        check(!state.isExhausted) { "no current card to reject" }
        val outcome = PietReject.reject(state.remaining)
        val next = state.copy(position = state.position + 1)
        check(outcome.newRemaining == next.remaining) { "Piet reject program disagreed with session bookkeeping" }
        return if (outcome.exhausted) RejectResult.RejectedAll(next) else RejectResult.NextCard(next)
    }

    /** Immediately selects the current card; the session ends here. */
    fun accept(state: SessionState): Movie {
        val index = checkNotNull(state.currentIndex()) { "no current card to accept" }
        val outcome = PietAccept.accept(index)
        check(outcome.remainingAfter == 0) { "Piet accept program did not confirm session end" }
        return eligible[outcome.selectedIndex]
    }

    /** Un-rejects the most recent card, if any. A no-op (per Piet's own NOT(NOT(0)) arithmetic) at position 0. */
    fun undo(state: SessionState): SessionState {
        val outcome = PietUndo.undo(state.position, historyCount = state.position)
        return state.copy(position = outcome.newPosition)
    }

    /** Reshuffles every eligible movie with a new seed, starting the session over. */
    fun reshuffleAll(seed: Long): SessionState = start(seed)

    /** From the reject-all screen: brings back the last 5 rejected (or fewer, if under 5 total). */
    fun bringBackLast5(state: SessionState): SessionState {
        check(state.isExhausted) { "bring-back-last-5 is only valid once every card has been rejected" }
        return state.copy(position = maxOf(0, state.order.size - 5))
    }
}

/** Filters the full movie pool down to what's eligible for a session; empty means nothing to play. */
fun eligibleMovies(pool: List<Movie>, filters: Filters): List<Movie> = pool.filter(filters::matches)
