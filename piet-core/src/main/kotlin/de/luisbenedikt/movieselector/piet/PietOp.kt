package de.luisbenedikt.movieselector.piet

/**
 * One of the 18 real Piet instructions triggered by a chromatic-to-chromatic color transition,
 * plus [NOOP] for a transition that doesn't move the color (never actually dispatched, listed
 * for completeness of the transition table).
 *
 * Table position is (lightness change 0..2) x (hue change 0..5), exactly the standard Piet
 * instruction table from the language specification (esolangs.org/wiki/Piet).
 */
enum class PietOp {
    NOOP, PUSH, POP,
    ADD, SUBTRACT, MULTIPLY, DIVIDE, MOD,
    NOT, GREATER,
    POINTER, SWITCH,
    DUPLICATE, ROLL,
    IN_NUMBER, IN_CHAR, OUT_NUMBER, OUT_CHAR;

    companion object {
        // table[lightnessChange][hueChange]
        private val table: Array<Array<PietOp>> = arrayOf(
            arrayOf(NOOP, ADD, DIVIDE, GREATER, DUPLICATE, ROLL),
            arrayOf(PUSH, SUBTRACT, MOD, POINTER, SWITCH, IN_NUMBER),
            arrayOf(POP, MULTIPLY, NOT, IN_CHAR, OUT_NUMBER, OUT_CHAR),
        )

        /** The instruction triggered by moving from color [from] into color [to] (both chromatic). */
        fun forTransition(from: PietColor.Chromatic, to: PietColor.Chromatic): PietOp {
            val hueChange = (to.hue.ordinal - from.hue.ordinal + 6) % 6
            val lightnessChange = (to.lightness.ordinal - from.lightness.ordinal + 3) % 3
            return table[lightnessChange][hueChange]
        }

        /** (lightnessChange, hueChange) that produces [op] from any starting color; each real op has exactly one. */
        private val deltaByOp: Map<PietOp, Pair<Int, Int>> = buildMap {
            for (l in table.indices) for (h in table[l].indices) put(table[l][h], l to h)
        }

        /** The color reached by applying [op]'s transition on top of [from]. Inverse of [forTransition]. */
        fun apply(from: PietColor.Chromatic, op: PietOp): PietColor.Chromatic {
            val (lightnessChange, hueChange) = requireNotNull(deltaByOp[op]) { "no delta for $op" }
            val hue = Hue.entries[(from.hue.ordinal + hueChange) % 6]
            val lightness = Lightness.entries[(from.lightness.ordinal + lightnessChange) % 3]
            return PietColor.of(hue, lightness)
        }
    }
}
