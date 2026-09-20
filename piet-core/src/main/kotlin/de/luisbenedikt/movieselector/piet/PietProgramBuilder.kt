package de.luisbenedikt.movieselector.piet

/**
 * Assembles a real, standard-instruction Piet program (a [CodelGrid]) from a straight-line
 * sequence of instructions, using the well-known "solid block of size N, then step to a color
 * that yields the PUSH transition" idiom for numeric literals, and a white-codel jump to start
 * an independent, freely-colored branch after a `pointer` instruction.
 *
 * Deliberately supports only straight-line code plus one 2-way fork per [Track.fork] call: both
 * arms of a fork run to their own natural end (falling off the edge of the drawn program, which
 * the interpreter treats as a normal, successful [PietTerminationReason.BLOCKED] halt) rather than
 * reconverging. That's all the control flow the Movie Selector session programs need, and it keeps
 * the grid geometry trivial to reason about and to test.
 *
 * Output protocol: because both fork arms share one pointer codel, the real Piet 8-consecutive-
 * failures rule means that after the taken arm exhausts its own instructions, execution can
 * (correctly, per spec) eventually bounce back through that shared codel and fall into the
 * *other* arm too, appending its output as well. Every program built with this DSL therefore
 * prints its real answer first; callers must read only the first N values they expect from
 * [PietResult.outputNumbers] and ignore anything that follows, rather than relying on the
 * interpreter to halt at exactly the "right" moment. [de.luisbenedikt.movieselector.game]
 * follows this contract for every session operation.
 */
class PietGridBuilder {
    private val cells = HashMap<Coord, PietColor>()
    private var maxCol = 0
    private var maxRow = 0

    internal fun place(coord: Coord, color: PietColor) {
        require(coord.col >= 0 && coord.row >= 0) { "grid coordinates must be non-negative" }
        cells[coord] = color
        maxCol = maxOf(maxCol, coord.col)
        maxRow = maxOf(maxRow, coord.row)
    }

    fun build(): CodelGrid = CodelGrid.build(maxCol + 1, maxRow + 1) { c, r -> cells[Coord(c, r)] ?: PietColor.White }

    /** Starts the program's first track at (0, 0), moving right, in an arbitrary starting color. */
    fun start(initialColor: PietColor.Chromatic = PietColor.of(Hue.RED, Lightness.LIGHT)): Track {
        val origin = Coord(0, 0)
        place(origin, initialColor)
        return Track(this, origin, Direction.RIGHT, initialColor)
    }
}

class Track internal constructor(
    private val grid: PietGridBuilder,
    private var head: Coord,
    private val direction: Direction,
    private var color: PietColor.Chromatic,
) {
    private fun step(): Coord {
        head = Coord(head.col + direction.dx, head.row + direction.dy)
        return head
    }

    /** Extends the block this track is currently standing in by [extraCells] more codels of its color. */
    fun grow(extraCells: Int): Track {
        require(extraCells >= 0)
        repeat(extraCells) { grid.place(step(), color) }
        return this
    }

    /** Pushes the literal [n] (n >= 1) by sizing the current block to n codels before the PUSH transition. */
    fun push(n: Long): Track {
        require(n >= 1) { "Piet can only push positive block sizes; got $n" }
        grow((n - 1).toInt())
        return transitionTo(PietOp.PUSH)
    }

    /** Emits any non-PUSH instruction as a single-codel transition from the current color. */
    fun instr(op: PietOp): Track {
        require(op != PietOp.PUSH) { "use push(n) to control the literal value" }
        return transitionTo(op)
    }

    private fun transitionTo(op: PietOp): Track {
        color = PietOp.apply(color, op)
        grid.place(step(), color)
        return this
    }

    /**
     * Emits `pointer`, then returns the other arm of the fork: a fresh track that starts one
     * step further in [otherDirection], past a one-codel white buffer (so its first real
     * instruction can use any color, independent of this track's current color). This track
     * itself becomes the "rotation = 0" arm and keeps going in its original direction.
     */
    fun fork(otherDirection: Direction, otherStartColor: PietColor.Chromatic): Track {
        instr(PietOp.POINTER)
        val pointerCell = head
        val whiteCell = Coord(pointerCell.col + otherDirection.dx, pointerCell.row + otherDirection.dy)
        grid.place(whiteCell, PietColor.White)
        val otherStart = Coord(whiteCell.col + otherDirection.dx, whiteCell.row + otherDirection.dy)
        grid.place(otherStart, otherStartColor)
        return Track(grid, otherStart, otherDirection, otherStartColor)
    }

    fun end(): CodelGrid = grid.build()
}
