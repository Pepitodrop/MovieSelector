package de.luisbenedikt.movieselector.game

import de.luisbenedikt.movieselector.piet.CodelGrid
import de.luisbenedikt.movieselector.piet.Hue
import de.luisbenedikt.movieselector.piet.Lightness
import de.luisbenedikt.movieselector.piet.PietColor
import de.luisbenedikt.movieselector.piet.PietGridBuilder
import de.luisbenedikt.movieselector.piet.PietInterpreter
import de.luisbenedikt.movieselector.piet.PietOp
import de.luisbenedikt.movieselector.piet.PietResult
import de.luisbenedikt.movieselector.piet.PietTerminationReason
import de.luisbenedikt.movieselector.piet.QueueInput

/**
 * Real Piet programs implementing the Movie Selector's session arithmetic. None of these use
 * `pointer`/`fork` branching: see [de.luisbenedikt.movieselector.piet.PietGridBuilder]'s doc
 * comment for why a shared branch codel is unsafe to chain, and NOT-based boolean arithmetic
 * (`NOT(NOT(x))` as "x != 0", used to zero out an update instead of skipping it) gives the same
 * decisions without it. Every program here is a single straight-line track, so a run either
 * produces its answer and then harmlessly cycles/blocks (see piet-core's README), or -- for
 * malformed/failed interpretation -- is reported as such via [SessionProgramException].
 */
class SessionProgramException(message: String, val reason: PietTerminationReason) : Exception(message)

private val interpreter = PietInterpreter()
private val startColor = PietColor.of(Hue.RED, Lightness.LIGHT)

/** Reads the first [count] output numbers, per the documented "read only what you expect" protocol. */
internal fun PietResult.firstOutputs(count: Int): List<Long> {
    if (terminationReason == PietTerminationReason.INPUT_EXHAUSTED) {
        throw SessionProgramException("Piet program ran out of input", terminationReason)
    }
    if (outputNumbers.size < count) {
        throw SessionProgramException(
            "Piet program produced ${outputNumbers.size} output value(s), needed $count", terminationReason
        )
    }
    return outputNumbers.subList(0, count)
}

object PietShuffle {
    /**
     * Builds a program that reads one seed and prints, for each original index `i` in
     * `0 until n`, the shuffled position `(i * mult + seed) mod n` -- a linear-congruential
     * bijection on `0 until n` (multiplying by a value coprime to `n` is always a permutation).
     * `mult` is picked fresh per `n` as the smallest prime greater than `n`, so it is coprime to
     * every possible `n` without needing per-call primality bookkeeping at runtime.
     */
    fun buildProgram(n: Int): CodelGrid {
        require(n >= 1) { "n must be at least 1" }
        val mult = smallestPrimeAbove(n)
        val track = PietGridBuilder().start(startColor)
        track.instr(PietOp.IN_NUMBER)
        for (i in 0 until n) {
            // Duplicate the seed every round (including the first) so one persistent copy
            // survives under each round's disposable working copy for the next iteration.
            track.instr(PietOp.DUPLICATE)
            val base = ((i.toLong() * mult) % n) + 1 // shifted by +1 so it's always pushable (>=1)
            track.push(base)
            track.instr(PietOp.ADD)
            track.push(1)
            track.instr(PietOp.SUBTRACT) // seed + (i*mult mod n), unshifted
            track.push(n.toLong())
            track.instr(PietOp.MOD)
            track.instr(PietOp.OUT_NUMBER)
        }
        return track.end()
    }

    /** Runs the shuffle program and returns `shuffledOrder`, where `shuffledOrder[k]` is the original index at position `k`. */
    fun shuffledOrder(n: Int, seed: Long): List<Int> {
        if (n == 0) return emptyList()
        val grid = buildProgram(n)
        val result = interpreter.run(grid, QueueInput(seed))
        val positions = result.firstOutputs(n)
        val order = IntArray(n)
        val seen = BooleanArray(n)
        for (i in 0 until n) {
            val pos = positions[i].toInt()
            if (pos !in 0 until n || seen[pos]) {
                throw SessionProgramException("Piet shuffle produced an invalid permutation", result.terminationReason)
            }
            seen[pos] = true
            order[pos] = i
        }
        return order.toList()
    }

    private fun smallestPrimeAbove(n: Int): Long {
        var candidate = (n + 1).toLong().coerceAtLeast(2)
        while (!isPrime(candidate)) candidate++
        return candidate
    }

    private fun isPrime(value: Long): Boolean {
        if (value < 2) return false
        if (value % 2 == 0L) return value == 2L
        var i = 3L
        while (i * i <= value) {
            if (value % i == 0L) return false
            i += 2
        }
        return true
    }
}

data class RejectOutcome(val newRemaining: Int, val exhausted: Boolean)

object PietReject {
    /** `remainingBefore` includes the movie being rejected. Prints (exhaustedFlag, newRemaining). */
    fun buildProgram(): CodelGrid = PietGridBuilder().start(startColor).apply {
        instr(PietOp.IN_NUMBER)
        push(1)
        instr(PietOp.SUBTRACT) // newRemaining = remainingBefore - 1
        instr(PietOp.DUPLICATE)
        instr(PietOp.NOT) // exhaustedFlag = (newRemaining == 0) ? 1 : 0
        instr(PietOp.OUT_NUMBER) // prints exhaustedFlag
        instr(PietOp.OUT_NUMBER) // prints newRemaining
    }.end()

    private val program = buildProgram()

    fun reject(remainingBefore: Int): RejectOutcome {
        require(remainingBefore >= 1)
        val result = interpreter.run(program, QueueInput(remainingBefore.toLong()))
        val (exhaustedFlag, newRemaining) = result.firstOutputs(2)
        return RejectOutcome(newRemaining.toInt(), exhaustedFlag == 1L)
    }
}

data class AcceptOutcome(val selectedIndex: Int, val remainingAfter: Int)

object PietAccept {
    /** Echoes `selectedIndex` back through Piet and confirms the session ends at 0 remaining. */
    fun buildProgram(): CodelGrid = PietGridBuilder().start(startColor).apply {
        instr(PietOp.IN_NUMBER)
        instr(PietOp.OUT_NUMBER) // echoes selectedIndex
        push(1)
        instr(PietOp.NOT) // 0
        instr(PietOp.OUT_NUMBER) // remainingAfter is always 0: the session is over
    }.end()

    private val program = buildProgram()

    fun accept(selectedIndex: Int): AcceptOutcome {
        require(selectedIndex >= 0)
        val result = interpreter.run(program, QueueInput(selectedIndex.toLong()))
        val (echoedIndex, remainingAfter) = result.firstOutputs(2)
        return AcceptOutcome(echoedIndex.toInt(), remainingAfter.toInt())
    }
}

data class UndoOutcome(val newPosition: Int, val didUndo: Boolean)

object PietUndo {
    /** `canUndo = historyCount != 0`, `newPosition = currentPosition - canUndo`. Prints (newPosition, canUndoFlag). */
    fun buildProgram(): CodelGrid = PietGridBuilder().start(startColor).apply {
        instr(PietOp.IN_NUMBER) // currentPosition
        instr(PietOp.IN_NUMBER) // historyCount
        instr(PietOp.NOT)
        instr(PietOp.NOT) // canUndo = historyCount != 0
        instr(PietOp.DUPLICATE)
        push(3)
        push(1)
        instr(PietOp.ROLL) // -> [canUndo, currentPosition, canUndoCopy]
        instr(PietOp.SUBTRACT) // newPosition = currentPosition - canUndo
        instr(PietOp.OUT_NUMBER) // newPosition
        instr(PietOp.OUT_NUMBER) // canUndoCopy
    }.end()

    private val program = buildProgram()

    fun undo(currentPosition: Int, historyCount: Int): UndoOutcome {
        require(currentPosition >= 0 && historyCount >= 0)
        val result = interpreter.run(program, QueueInput(currentPosition.toLong(), historyCount.toLong()))
        val (newPosition, canUndoFlag) = result.firstOutputs(2)
        return UndoOutcome(newPosition.toInt(), canUndoFlag == 1L)
    }
}
