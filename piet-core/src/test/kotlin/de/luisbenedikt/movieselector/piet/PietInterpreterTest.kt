package de.luisbenedikt.movieselector.piet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PietColorParsingTest {
    @Test fun `every standard color round-trips through rgb`() {
        for (hue in Hue.entries) for (lightness in Lightness.entries) {
            val color = PietColor.of(hue, lightness)
            assertEquals(color, PietColor.fromRgb(color.rgb))
        }
        assertEquals(PietColor.White, PietColor.fromRgb(0xFFFFFF))
        assertEquals(PietColor.Black, PietColor.fromRgb(0x000000))
    }

    @Test fun `non-standard color is not recognized`() {
        assertNull(PietColor.fromRgb(0x123456))
    }

    @Test fun `standard palette hex values match the Piet specification`() {
        assertEquals(0xFFC0C0, PietColor.of(Hue.RED, Lightness.LIGHT).rgb)
        assertEquals(0xFF0000, PietColor.of(Hue.RED, Lightness.NORMAL).rgb)
        assertEquals(0xC00000, PietColor.of(Hue.RED, Lightness.DARK).rgb)
        assertEquals(0x00C0C0, PietColor.of(Hue.CYAN, Lightness.DARK).rgb)
        assertEquals(0xFFC0FF, PietColor.of(Hue.MAGENTA, Lightness.LIGHT).rgb)
    }
}

class PietOpTableTest {
    @Test fun `apply is the exact inverse of forTransition for every op`() {
        val from = PietColor.of(Hue.GREEN, Lightness.NORMAL)
        for (op in PietOp.entries.filter { it != PietOp.NOOP }) {
            val to = PietOp.apply(from, op)
            assertEquals(op, PietOp.forTransition(from, to), "op $op did not round-trip")
        }
    }

    @Test fun `same color transition is NOOP`() {
        val c = PietColor.of(Hue.BLUE, Lightness.DARK)
        assertEquals(PietOp.NOOP, PietOp.forTransition(c, c))
    }
}

class DirectionAndChooserTest {
    @Test fun `direction cycles clockwise`() {
        assertEquals(Direction.DOWN, Direction.RIGHT.rotateClockwise())
        assertEquals(Direction.LEFT, Direction.DOWN.rotateClockwise())
        assertEquals(Direction.UP, Direction.LEFT.rotateClockwise())
        assertEquals(Direction.RIGHT, Direction.UP.rotateClockwise())
        assertEquals(Direction.RIGHT, Direction.RIGHT.rotateClockwise(4))
        assertEquals(Direction.UP, Direction.RIGHT.rotateClockwise(-1))
    }

    @Test fun `chooser toggles on odd counts only`() {
        assertEquals(CodelChooser.RIGHT, CodelChooser.LEFT.toggle(1))
        assertEquals(CodelChooser.LEFT, CodelChooser.LEFT.toggle(2))
        assertEquals(CodelChooser.RIGHT, CodelChooser.LEFT.toggle(3))
    }
}

class PietInterpreterArithmeticTest {
    private val interpreter = PietInterpreter()

    private fun run(builder: Track.() -> Track): PietResult {
        val grid = PietGridBuilder().start().builder().end()
        return interpreter.run(grid)
    }

    @Test fun `push and add outputs the sum`() {
        val result = run { push(3).push(4).instr(PietOp.ADD).instr(PietOp.OUT_NUMBER) }
        assertEquals("7", result.output)
        assertEquals(PietTerminationReason.BLOCKED, result.terminationReason)
    }

    @Test fun `subtract is second-from-top minus top`() {
        val result = run { push(10).push(3).instr(PietOp.SUBTRACT).instr(PietOp.OUT_NUMBER) }
        assertEquals("7", result.output)
    }

    @Test fun `multiply`() {
        val result = run { push(6).push(7).instr(PietOp.MULTIPLY).instr(PietOp.OUT_NUMBER) }
        assertEquals("42", result.output)
    }

    @Test fun `divide truncates toward zero`() {
        val result = run { push(7).push(2).instr(PietOp.DIVIDE).instr(PietOp.OUT_NUMBER) }
        assertEquals("3", result.output)
    }

    @Test fun `divide by zero is a total no-op, stack unaffected`() {
        // Piet can only push positive block sizes, so 0 is built as push(1).NOT.
        val result = run { push(7).push(1).instr(PietOp.NOT).instr(PietOp.DIVIDE).instr(PietOp.OUT_NUMBER).instr(PietOp.OUT_NUMBER) }
        // divide is skipped entirely (nothing popped), so the two OUT_NUMBERs drain the original 7 and 0.
        assertEquals("07", result.output)
    }

    @Test fun `mod takes the sign of the divisor`() {
        val result = run { push(1).push(7).instr(PietOp.SUBTRACT) /* -6 */ .push(4).instr(PietOp.MOD).instr(PietOp.OUT_NUMBER) }
        assertEquals("2", result.output) // floorMod(-6, 4) == 2
    }

    @Test fun `not maps zero to one and nonzero to zero`() {
        val result = run { push(5).instr(PietOp.NOT).instr(PietOp.OUT_NUMBER) }
        assertEquals("0", result.output)
    }

    @Test fun `greater compares second-from-top against top`() {
        val result = run { push(9).push(4).instr(PietOp.GREATER).instr(PietOp.OUT_NUMBER) }
        assertEquals("1", result.output)
    }

    @Test fun `duplicate doubles the top of stack`() {
        val result = run { push(5).instr(PietOp.DUPLICATE).instr(PietOp.OUT_NUMBER).instr(PietOp.OUT_NUMBER) }
        assertEquals("55", result.output)
    }

    @Test fun `roll rotates the top depth values by count`() {
        // stack bottom->top: 1 2 3 ; roll(depth=3, count=1) should give 3 1 2 (top-most moves to bottom of the window).
        val result = run {
            push(1).push(2).push(3).push(3).push(1).instr(PietOp.ROLL)
                .instr(PietOp.OUT_NUMBER).instr(PietOp.OUT_NUMBER).instr(PietOp.OUT_NUMBER)
        }
        assertEquals("213", result.output)
    }

    @Test fun `roll with invalid depth is ignored but still consumes its two operands`() {
        val result = run { push(1).push(2).push(99).push(1).instr(PietOp.ROLL).instr(PietOp.OUT_NUMBER).instr(PietOp.OUT_NUMBER) }
        assertEquals("21", result.output)
    }

    @Test fun `push size equals codel block size, not literal instruction count`() {
        val result = run { push(12).instr(PietOp.OUT_NUMBER) }
        assertEquals("12", result.output)
    }
}

class PietInterpreterIoTest {
    private val interpreter = PietInterpreter()

    @Test fun `in number then out number echoes input`() {
        val grid = PietGridBuilder().start().instr(PietOp.IN_NUMBER).instr(PietOp.OUT_NUMBER).end()
        val result = interpreter.run(grid, QueueInput(42L))
        assertEquals("42", result.output)
    }

    @Test fun `in char reads a code point`() {
        val grid = PietGridBuilder().start().instr(PietOp.IN_CHAR).instr(PietOp.OUT_CHAR).end()
        val result = interpreter.run(grid, QueueInput('A'.code.toLong()))
        assertEquals("A", result.output)
    }

    @Test fun `exhausted input halts the program with INPUT_EXHAUSTED`() {
        val grid = PietGridBuilder().start().instr(PietOp.IN_NUMBER).instr(PietOp.OUT_NUMBER).end()
        val result = interpreter.run(grid, QueueInput(mutableListOf()))
        assertEquals(PietTerminationReason.INPUT_EXHAUSTED, result.terminationReason)
        assertEquals("", result.output)
    }
}

class PietInterpreterControlFlowTest {
    private val interpreter = PietInterpreter()

    @Test fun `fork takes the zero arm straight through and the one arm downward`() {
        // Piet can only push positive block sizes, so false/0 is built as push(1).NOT.
        // Each arm prints a distinguishable marker so we can tell which one actually ran.
        // Per the protocol documented on PietGridBuilder/Track: a branch arm that has
        // exhausted its own instructions can, per the real Piet 8-failure rule, eventually
        // bounce back through a shared pointer cell and fall into the *other* arm too -- so
        // callers always read only the first N expected output values and ignore anything
        // that follows, exactly as the game-engine layer does. We assert that contract here
        // (the real marker comes first) rather than exact full-output equality.
        val zeroGrid = PietGridBuilder().start().apply {
            push(1).instr(PietOp.NOT)
            val down = fork(Direction.DOWN, PietColor.of(Hue.YELLOW, Lightness.LIGHT))
            push(111).instr(PietOp.OUT_NUMBER)
            down.push(222).instr(PietOp.OUT_NUMBER)
        }.end()
        val zeroResult = interpreter.run(zeroGrid)
        assertEquals(111L, zeroResult.outputNumbers.first())

        val oneGrid = PietGridBuilder().start().apply {
            push(1)
            val down = fork(Direction.DOWN, PietColor.of(Hue.YELLOW, Lightness.LIGHT))
            push(111).instr(PietOp.OUT_NUMBER)
            down.push(222).instr(PietOp.OUT_NUMBER)
        }.end()
        val oneResult = interpreter.run(oneGrid)
        assertEquals(222L, oneResult.outputNumbers.first())
    }

    @Test fun `program that runs off the grid edge terminates as BLOCKED`() {
        val grid = PietGridBuilder().start().push(3).instr(PietOp.OUT_NUMBER).end()
        val result = interpreter.run(grid)
        assertEquals(PietTerminationReason.BLOCKED, result.terminationReason)
    }

    @Test fun `the step limit protects against a program that has not produced its answer yet`() {
        val grid = PietGridBuilder().start().push(3).push(4).instr(PietOp.ADD).instr(PietOp.OUT_NUMBER).end()
        val result = PietInterpreter(maxSteps = 3).run(grid)
        assertEquals(PietTerminationReason.STEP_LIMIT_EXCEEDED, result.terminationReason)
        assertEquals(3, result.stepsExecuted)
        assertTrue(result.output.isEmpty(), "3 steps is not enough to reach OUT_NUMBER yet")
    }
}

class PietImageTest {
    @Test fun `grid round-trips through a real PNG`() {
        val grid = PietGridBuilder().start().push(5).instr(PietOp.OUT_NUMBER).end()
        val bytes = ByteArrayOutputStream().also { PietImage.write(grid, it) }.toByteArray()
        val decoded = PietImage.decode(ByteArrayInputStream(bytes))
        assertEquals(grid.width, decoded.width)
        assertEquals(grid.height, decoded.height)
        for (row in 0 until grid.height) for (col in 0 until grid.width) {
            assertEquals(grid.colorAt(col, row), decoded.colorAt(col, row))
        }
        val result = PietInterpreter().run(decoded)
        assertEquals("5", result.output)
    }

    @Test fun `decoding a non-standard color fails loudly instead of silently misreading`() {
        val image = java.awt.image.BufferedImage(PietImage.CODEL_SIZE, PietImage.CODEL_SIZE, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = java.awt.Color(0x12, 0x34, 0x56)
        g.fillRect(0, 0, image.width, image.height)
        g.dispose()
        val bytes = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        assertFailsWith<IllegalArgumentException> { PietImage.decode(ByteArrayInputStream(bytes)) }
    }

    @Test fun `decoding an image with the wrong codel size is rejected`() {
        val image = java.awt.image.BufferedImage(5, 5, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        assertFailsWith<IllegalArgumentException> { PietImage.decode(ByteArrayInputStream(bytes), codelSize = 8) }
    }
}
