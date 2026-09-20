package de.luisbenedikt.movieselector.piet

/** The 6 standard Piet hues, in spec order (the order the hue cycle advances through). */
enum class Hue { RED, YELLOW, GREEN, CYAN, BLUE, MAGENTA }

/** The 3 standard Piet lightness steps, in spec order (the order the lightness cycle advances through). */
enum class Lightness { LIGHT, NORMAL, DARK }

/**
 * One of the 20 standard Piet codel colors: 18 chromatic (hue x lightness), plus white and black.
 * RGB values are the canonical Piet palette (used by piet.exe / npiet), so images this module
 * writes and reads round-trip with any spec-compliant Piet tool.
 */
sealed class PietColor(val rgb: Int) {
    class Chromatic(val hue: Hue, val lightness: Lightness) : PietColor(chromaticRgb(hue, lightness))
    object White : PietColor(0xFFFFFF)
    object Black : PietColor(0x000000)

    override fun equals(other: Any?): Boolean = other is PietColor && other.rgb == rgb
    override fun hashCode(): Int = rgb
    override fun toString(): String = when (this) {
        is White -> "white"
        is Black -> "black"
        is Chromatic -> "${lightness.name.lowercase()}-${hue.name.lowercase()}"
    }

    companion object {
        private fun chromaticRgb(hue: Hue, lightness: Lightness): Int {
            val base = when (hue) {
                Hue.RED -> Triple(0xFF, 0x00, 0x00)
                Hue.YELLOW -> Triple(0xFF, 0xFF, 0x00)
                Hue.GREEN -> Triple(0x00, 0xFF, 0x00)
                Hue.CYAN -> Triple(0x00, 0xFF, 0xFF)
                Hue.BLUE -> Triple(0x00, 0x00, 0xFF)
                Hue.MAGENTA -> Triple(0xFF, 0x00, 0xFF)
            }
            fun step(channel: Int): Int = when (lightness) {
                Lightness.NORMAL -> channel
                Lightness.LIGHT -> if (channel == 0x00) 0xC0 else 0xFF
                Lightness.DARK -> if (channel == 0xFF) 0xC0 else 0x00
            }
            return (step(base.first) shl 16) or (step(base.second) shl 8) or step(base.third)
        }

        private val byRgb: Map<Int, PietColor> = buildMap {
            for (hue in Hue.entries) for (lightness in Lightness.entries) {
                val color = Chromatic(hue, lightness)
                put(color.rgb, color)
            }
            put(White.rgb, White)
            put(Black.rgb, Black)
        }

        /** Look up the standard Piet color for an RGB pixel value, or null if it isn't one of the 20. */
        fun fromRgb(rgb: Int): PietColor? = byRgb[rgb and 0xFFFFFF]

        fun of(hue: Hue, lightness: Lightness): Chromatic = Chromatic(hue, lightness)
    }
}
