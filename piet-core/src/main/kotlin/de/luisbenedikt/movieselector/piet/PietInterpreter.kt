package de.luisbenedikt.movieselector.piet

/** Feeds numbers/characters to `in(number)`/`in(char)`. Return null when no input is available. */
interface PietInput {
    fun readNumber(): Long?
    fun readChar(): Int?
}

/** A fixed queue of numbers used as a program's stdin; matches the interpreter's input protocol. */
class QueueInput(private val numbers: MutableList<Long>) : PietInput {
    constructor(vararg numbers: Long) : this(numbers.toMutableList())

    override fun readNumber(): Long? = if (numbers.isEmpty()) null else numbers.removeAt(0)
    override fun readChar(): Int? = readNumber()?.toInt()
}

/** Collects `out(number)`/`out(char)` writes exactly as the Piet spec defines them (no extra formatting). */
class RecordingOutput : PietOutput {
    private val builder = StringBuilder()
    val numbers = mutableListOf<Long>()

    override fun writeNumber(value: Long) {
        builder.append(value)
        numbers.add(value)
    }

    override fun writeChar(codePoint: Int) {
        builder.appendCodePoint(codePoint)
    }

    val text: String get() = builder.toString()
}

interface PietOutput {
    fun writeNumber(value: Long)
    fun writeChar(codePoint: Int)
}

enum class PietTerminationReason {
    /** Ran off every edge of the program 8 times in a row: normal, successful termination. */
    BLOCKED,
    /** Defensive limit hit; the program (or a malformed/generator bug) never halts on its own. */
    STEP_LIMIT_EXCEEDED,
    /** An input instruction ran with no input available. Ends the program, per the Piet spec. */
    INPUT_EXHAUSTED,
}

data class PietResult(
    val output: String,
    val outputNumbers: List<Long>,
    val finalStack: List<Long>,
    val stepsExecuted: Int,
    val terminationReason: PietTerminationReason,
)

class PietInterpreter(private val maxSteps: Int = 200_000) {

    fun run(grid: CodelGrid, input: PietInput = QueueInput(mutableListOf())): PietResult {
        val output = RecordingOutput()
        val stack = ArrayDeque<Long>()
        var pos = firstNonBlackCodel(grid) ?: return PietResult("", emptyList(), emptyList(), 0, PietTerminationReason.BLOCKED)
        var dp = Direction.RIGHT
        var cc = CodelChooser.LEFT
        var consecutiveFailures = 0
        var steps = 0
        // A plain, unwalled program can run off its own drawn end, fail 4 times, and find a
        // valid neighbor by rotating DP all the way back into a codel it already visited going
        // the other way (Piet's 8-consecutive-failures halt only fires when EVERY direction is
        // genuinely blocked, which a linear layout with real cells on both sides never satisfies).
        // If the full machine state (position, DP, CC, stack) ever repeats exactly, the program
        // is provably in a permanent cycle and will never again do anything new, so that is also
        // a legitimate halt -- and it strictly subsumes the spec's 8-failure rule.
        val visited = HashSet<List<Any>>()

        while (steps < maxSteps) {
            val stateKey = listOf(pos, dp, cc, stack.toList())
            if (!visited.add(stateKey)) return finish(output, stack, steps, PietTerminationReason.BLOCKED)

            val currentColor = grid.colorAt(pos.col, pos.row)
            if (currentColor is PietColor.Black || currentColor == null) {
                // Shouldn't happen: we only ever move onto non-black in-bounds codels.
                return finish(output, stack, steps, PietTerminationReason.BLOCKED)
            }

            val block = floodFillBlock(grid, pos, currentColor)
            val exit = chooseExit(grid, block, dp, cc)
            val target = exit?.let { grid.colorAt(it.col, it.row) }

            if (exit == null || target == null || target is PietColor.Black) {
                consecutiveFailures++
                if (consecutiveFailures >= 8) return finish(output, stack, steps, PietTerminationReason.BLOCKED)
                if (consecutiveFailures % 2 == 1) cc = cc.toggle() else dp = dp.rotateClockwise()
                steps++
                continue
            }

            if (target is PietColor.White) {
                val slid = slideThroughWhite(grid, exit, dp)
                if (slid == null) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 8) return finish(output, stack, steps, PietTerminationReason.BLOCKED)
                    if (consecutiveFailures % 2 == 1) cc = cc.toggle() else dp = dp.rotateClockwise()
                    steps++
                    continue
                }
                pos = slid
                consecutiveFailures = 0
                steps++
                continue
            }

            check(target is PietColor.Chromatic && currentColor is PietColor.Chromatic)
            val op = PietOp.forTransition(currentColor, target)
            val outcome = execute(op, block.size.toLong(), stack, input, output)
            if (outcome.inputExhausted) return finish(output, stack, steps, PietTerminationReason.INPUT_EXHAUSTED)
            if (outcome.dpRotation != 0) dp = dp.rotateClockwise(outcome.dpRotation)
            if (outcome.ccToggle != 0) cc = cc.toggle(outcome.ccToggle)

            pos = exit
            consecutiveFailures = 0
            steps++
        }
        return finish(output, stack, steps, PietTerminationReason.STEP_LIMIT_EXCEEDED)
    }

    private fun finish(output: RecordingOutput, stack: ArrayDeque<Long>, steps: Int, reason: PietTerminationReason) =
        PietResult(output.text, output.numbers, stack.toList(), steps, reason)

    private data class Outcome(val inputExhausted: Boolean = false, val dpRotation: Int = 0, val ccToggle: Int = 0)

    private fun execute(op: PietOp, blockSize: Long, stack: ArrayDeque<Long>, input: PietInput, output: PietOutput): Outcome {
        when (op) {
            PietOp.NOOP -> {}
            PietOp.PUSH -> stack.addLast(blockSize)
            PietOp.POP -> stack.removeLastOrNull()
            PietOp.ADD -> binary(stack) { a, b -> a + b }
            PietOp.SUBTRACT -> binary(stack) { a, b -> a - b }
            PietOp.MULTIPLY -> binary(stack) { a, b -> a * b }
            PietOp.DIVIDE -> {
                if (stack.size >= 2 && stack[stack.size - 1] != 0L) binary(stack) { a, b -> a / b }
                // Divide by zero (or too few operands): whole instruction is a no-op, per spec.
            }
            PietOp.MOD -> {
                if (stack.size >= 2 && stack[stack.size - 1] != 0L) binary(stack) { a, b -> Math.floorMod(a, b) }
            }
            PietOp.NOT -> {
                val a = stack.removeLastOrNull()
                if (a != null) stack.addLast(if (a == 0L) 1L else 0L)
            }
            PietOp.GREATER -> binary(stack) { a, b -> if (a > b) 1L else 0L }
            PietOp.POINTER -> {
                val steps = stack.removeLastOrNull()
                if (steps != null) return Outcome(dpRotation = steps.toInt())
            }
            PietOp.SWITCH -> {
                val times = stack.removeLastOrNull()
                if (times != null) return Outcome(ccToggle = times.toInt())
            }
            PietOp.DUPLICATE -> stack.lastOrNull()?.let { stack.addLast(it) }
            PietOp.ROLL -> {
                val rolls = stack.removeLastOrNull()
                val depth = stack.removeLastOrNull()
                if (rolls != null && depth != null && depth in 0..stack.size.toLong()) {
                    rollStack(stack, depth.toInt(), rolls)
                }
            }
            PietOp.IN_NUMBER -> {
                val value = input.readNumber() ?: return Outcome(inputExhausted = true)
                stack.addLast(value)
            }
            PietOp.IN_CHAR -> {
                val value = input.readChar() ?: return Outcome(inputExhausted = true)
                stack.addLast(value.toLong())
            }
            PietOp.OUT_NUMBER -> stack.removeLastOrNull()?.let { output.writeNumber(it) }
            PietOp.OUT_CHAR -> stack.removeLastOrNull()?.let { output.writeChar(it.toInt()) }
        }
        return Outcome()
    }

    private inline fun binary(stack: ArrayDeque<Long>, op: (a: Long, b: Long) -> Long) {
        if (stack.size < 2) return
        val b = stack.removeLast()
        val a = stack.removeLast()
        stack.addLast(op(a, b))
    }

    private fun rollStack(stack: ArrayDeque<Long>, depth: Int, rolls: Long) {
        if (depth <= 1) return
        val start = stack.size - depth
        val segment = ArrayList<Long>(depth)
        for (i in start until stack.size) segment.add(stack[i])
        val shift = ((rolls % depth) + depth).toInt() % depth
        // A positive roll moves each item towards the top of the stack by `shift`.
        val rotated = ArrayList<Long>(depth)
        for (i in 0 until depth) rotated.add(segment[(i - shift + depth) % depth])
        for (i in 0 until depth) stack[start + i] = rotated[i]
    }

    private fun firstNonBlackCodel(grid: CodelGrid): Coord? {
        for (row in 0 until grid.height) for (col in 0 until grid.width) {
            if (grid.colorAt(col, row) !is PietColor.Black) return Coord(col, row)
        }
        return null
    }

    private fun floodFillBlock(grid: CodelGrid, start: Coord, color: PietColor): Set<Coord> {
        val seen = mutableSetOf(start)
        val stack = ArrayDeque<Coord>().apply { addLast(start) }
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            for (d in Direction.entries) {
                val nc = Coord(c.col + d.dx, c.row + d.dy)
                if (nc !in seen && grid.colorAt(nc.col, nc.row) == color) {
                    seen.add(nc)
                    stack.addLast(nc)
                }
            }
        }
        return seen
    }

    /** Finds the codel just outside the block that DP/CC selects as the exit attempt for this step. */
    private fun chooseExit(grid: CodelGrid, block: Set<Coord>, dp: Direction, cc: CodelChooser): Coord? {
        val farInDp = when (dp) {
            Direction.RIGHT -> block.maxOf { it.col }
            Direction.LEFT -> block.minOf { it.col }
            Direction.DOWN -> block.maxOf { it.row }
            Direction.UP -> block.minOf { it.row }
        }
        val edgeCoords = block.filter {
            when (dp) {
                Direction.RIGHT, Direction.LEFT -> it.col == farInDp
                Direction.DOWN, Direction.UP -> it.row == farInDp
            }
        }
        val secondary = dp.rotateClockwise(if (cc == CodelChooser.RIGHT) 1 else -1)
        val chosen = when (secondary) {
            Direction.RIGHT -> edgeCoords.maxByOrNull { it.col }
            Direction.LEFT -> edgeCoords.minByOrNull { it.col }
            Direction.DOWN -> edgeCoords.maxByOrNull { it.row }
            Direction.UP -> edgeCoords.minByOrNull { it.row }
        } ?: return null
        return Coord(chosen.col + dp.dx, chosen.row + dp.dy)
    }

    /** From a white codel, keep moving in [dp] until a non-white codel or a wall is reached. */
    private fun slideThroughWhite(grid: CodelGrid, start: Coord, dp: Direction): Coord? {
        var pos = start
        val visited = mutableSetOf<Pair<Coord, Direction>>()
        while (true) {
            val color = grid.colorAt(pos.col, pos.row) ?: return null
            if (color !is PietColor.White) return pos
            if (!visited.add(pos to dp)) return null // caught in a white loop with no exit
            val next = Coord(pos.col + dp.dx, pos.row + dp.dy)
            val nextColor = grid.colorAt(next.col, next.row)
            if (nextColor == null || nextColor is PietColor.Black) return null
            pos = next
        }
    }
}
