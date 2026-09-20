package de.luisbenedikt.movieselector.piet

/** An immutable rectangular grid of [PietColor] codels, indexed `[col, row]`. */
class CodelGrid(val width: Int, val height: Int, private val colors: Array<PietColor>) {
    init {
        require(colors.size == width * height) { "grid data size does not match width*height" }
    }

    fun colorAt(col: Int, row: Int): PietColor? {
        if (col < 0 || row < 0 || col >= width || row >= height) return null
        return colors[row * width + col]
    }

    companion object {
        fun build(width: Int, height: Int, fill: (col: Int, row: Int) -> PietColor): CodelGrid {
            val data = Array<PietColor>(width * height) { PietColor.White }
            for (row in 0 until height) for (col in 0 until width) data[row * width + col] = fill(col, row)
            return CodelGrid(width, height, data)
        }
    }
}

enum class Direction(val dx: Int, val dy: Int) {
    RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0), UP(0, -1);

    fun rotateClockwise(steps: Int = 1): Direction {
        val n = ((ordinal + steps) % entries.size + entries.size) % entries.size
        return entries[n]
    }
}

enum class CodelChooser {
    LEFT, RIGHT;

    fun toggle(times: Int = 1): CodelChooser = if (times % 2 == 0) this else if (this == LEFT) RIGHT else LEFT
}

data class Coord(val col: Int, val row: Int)
