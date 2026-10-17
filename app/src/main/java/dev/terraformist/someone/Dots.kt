package dev.terraformist.someone

/** A little picture drawn as text: '#' = lit, anything else = off. */
class Sprite(vararg rows: String) {
    private val rows: List<String> = rows.toList()
    val width: Int = this.rows.maxOf { it.length }
    val height: Int = this.rows.size

    fun lit(x: Int, y: Int): Boolean = rows.getOrNull(y)?.getOrNull(x) == '#'
}

/**
 * A dot-matrix typeface: one [Sprite] per character, all sitting on the same baseline
 * ([height] rows; punctuation like the comma may hang one row below it).
 * Characters are separated by one empty column.
 */
class DotFont(val height: Int, private val glyphs: Map<Char, Sprite>) {

    fun glyph(ch: Char): Sprite? = glyphs[ch] ?: glyphs[ch.uppercaseChar()]

    /** Rows needed for [text], including any letters (j, the comma) that hang below the baseline. */
    fun rows(text: String): Int = maxOf(height, text.maxOfOrNull { glyph(it)?.height ?: 0 } ?: 0)

    /** Width in dots of [text], including the gaps between characters. */
    fun width(text: String): Int {
        var w = 0
        for (ch in text) w += (glyph(ch)?.width ?: 0) + 1
        return (w - 1).coerceAtLeast(0)
    }

    /** Calls [dot] with the column and row of every lit dot in [text]. */
    fun forEachDot(text: String, dot: (Int, Int) -> Unit) {
        var x = 0
        for (ch in text) {
            val g = glyph(ch) ?: continue
            for (y in 0 until g.height) for (gx in 0 until g.width) if (g.lit(gx, y)) dot(x + gx, y)
            x += g.width + 1
        }
    }

    companion object {
        private fun g(vararg rows: String) = Sprite(*rows)

        /**
         * 5×7 numerals for distances, plus the capitals for headings like "TOGETHER" and "PAIR WITH YOUR SOMEONE".
         * The decimal point is a single dot; the comma is three dots swooping down to the left.
         */
        val LARGE = DotFont(
            7,
            mapOf(
                '0' to g(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
                '1' to g("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
                '2' to g(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
                '3' to g(".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."),
                '4' to g("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
                '5' to g("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
                '6' to g("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
                '7' to g("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
                '8' to g(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
                '9' to g(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
                '.' to g(".", ".", ".", ".", ".", ".", "#"),
                ',' to g("..", "..", "..", "..", "..", ".#", ".#", "#."),
                '-' to g("...", "...", "...", "###", "...", "...", "..."),
                '\'' to g("#", "#", ".", ".", ".", ".", "."),
                ' ' to g("..", "..", "..", "..", "..", "..", ".."),
                'A' to g(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
                'D' to g("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
                'E' to g("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
                'G' to g(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###."),
                'I' to g("###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"),
                'N' to g("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
                'P' to g("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
                'T' to g("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
                'H' to g("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
                'M' to g("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
                'O' to g(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
                'R' to g("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
                'S' to g(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
                'U' to g("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
                'W' to g("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"),
                'Y' to g("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
            ),
        )

        /** 3×5 numerals for the 25×25 Glyph Matrix. Same single-dot point and three-dot comma. */
        val SMALL = DotFont(
            5,
            mapOf(
                '0' to g("###", "#.#", "#.#", "#.#", "###"),
                '1' to g(".#.", "##.", ".#.", ".#.", "###"),
                '2' to g("##.", "..#", ".#.", "#..", "###"),
                '3' to g("##.", "..#", ".#.", "..#", "##."),
                '4' to g("#.#", "#.#", "###", "..#", "..#"),
                '5' to g("###", "#..", "##.", "..#", "##."),
                '6' to g(".##", "#..", "###", "#.#", "###"),
                '7' to g("###", "..#", ".#.", ".#.", ".#."),
                '8' to g("###", "#.#", "###", "#.#", "###"),
                '9' to g("###", "#.#", "###", "..#", "##."),
                '.' to g(".", ".", ".", ".", "#"),
                ',' to g("..", "..", "..", ".#", ".#", "#."),
                'K' to g("#.#", "#.#", "##.", "#.#", "#.#"),
                'M' to g("#.#", "###", "###", "#.#", "#.#"),
                ' ' to g("..", "..", "..", "..", ".."),
            ),
        )
    }
}

/** A grid of dots, each with a brightness 0..255. Used for the Glyph Matrix. */
class DotGrid(val width: Int, val height: Int) {

    val levels = IntArray(width * height)

    fun set(x: Int, y: Int, level: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        levels[y * width + x] = level.coerceIn(0, 255)
    }

    /** Brighten a dot, never dim it. */
    fun lift(x: Int, y: Int, level: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val i = y * width + x
        levels[i] = maxOf(levels[i], level.coerceIn(0, 255))
    }

    fun text(font: DotFont, text: String, left: Int, top: Int, level: Int = 255) {
        font.forEachDot(text) { x, y -> set(left + x, top + y, level) }
    }
}

/** The exact shape of the Phone (3) Glyph Matrix: 25 rows, each narrower toward the top and bottom. */
object GlyphShape {
    const val SIZE = 25
    const val CENTER = 12

    // How many dots each row is indented on the left (and right). From Nothing's 23112 spec sheet.
    private val INDENT = intArrayOf(9, 7, 5, 4, 3, 2, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 4, 5, 7, 9)

    fun contains(x: Int, y: Int): Boolean {
        if (y !in 0 until SIZE || x !in 0 until SIZE) return false
        val indent = INDENT[y]
        return x >= indent && x < SIZE - indent
    }
}
