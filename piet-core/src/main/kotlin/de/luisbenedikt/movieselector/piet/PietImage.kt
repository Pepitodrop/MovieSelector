package de.luisbenedikt.movieselector.piet

import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.imageio.ImageIO

/** Encodes/decodes a [CodelGrid] as a real PNG, one codel per pixel, using only standard Piet colors. */
object PietImage {
    const val CODEL_SIZE = 8

    fun encode(grid: CodelGrid): BufferedImage {
        val image = BufferedImage(grid.width * CODEL_SIZE, grid.height * CODEL_SIZE, BufferedImage.TYPE_INT_RGB)
        for (row in 0 until grid.height) for (col in 0 until grid.width) {
            val rgb = (grid.colorAt(col, row) ?: PietColor.White).rgb
            for (py in 0 until CODEL_SIZE) for (px in 0 until CODEL_SIZE) {
                image.setRGB(col * CODEL_SIZE + px, row * CODEL_SIZE + py, rgb)
            }
        }
        return image
    }

    fun write(grid: CodelGrid, output: OutputStream) {
        ImageIO.write(encode(grid), "png", output)
    }

    fun write(grid: CodelGrid, file: File) {
        file.outputStream().use { write(grid, it) }
    }

    /** Decodes a PNG back into a [CodelGrid], assuming a uniform codel size in pixels. */
    fun decode(input: InputStream, codelSize: Int = CODEL_SIZE): CodelGrid {
        val image = requireNotNull(ImageIO.read(input)) { "not a readable image" }
        require(image.width % codelSize == 0 && image.height % codelSize == 0) {
            "image dimensions must be a multiple of codelSize=$codelSize"
        }
        val width = image.width / codelSize
        val height = image.height / codelSize
        return CodelGrid.build(width, height) { col, row ->
            val rgb = image.getRGB(col * codelSize + codelSize / 2, row * codelSize + codelSize / 2) and 0xFFFFFF
            PietColor.fromRgb(rgb) ?: throw IllegalArgumentException(
                "pixel at codel ($col,$row) is 0x${rgb.toString(16)}, not a standard Piet color"
            )
        }
    }

    fun decode(file: File, codelSize: Int = CODEL_SIZE): CodelGrid = file.inputStream().use { decode(it, codelSize) }
}
