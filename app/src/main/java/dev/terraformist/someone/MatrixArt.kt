package dev.terraformist.someone

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the 25×25 Glyph Matrix shows. The Glyph lights are white only, so the hearts are white dots:
 *  - apart: a little heart at the edge, on the side they are, beating slowly when far and faster
 *    when close; within 2 km a needle joins it to the centre;
 *  - together: one bigger heart in the middle, beating;
 *  - coming together: two little hearts circle in and merge (and split again when you part);
 *  - near (too close for GPS to give a direction): a little heart in the middle, beating fast;
 *  - they've paused: a dim little heart in the middle;
 *  - after a long-press of the Glyph Button: the distance in numbers for a few seconds.
 */
object MatrixArt {

    private const val C = GlyphShape.CENTER.toDouble()
    private const val TIP_RADIUS = 9.0
    private const val NEEDLE_END = 6.2

    private val SMALL = Sprite(".#.#.", "#####", "#####", ".###.", "..#..")
    private val BIG = Sprite(
        ".###.###.",
        "#########",
        "#########",
        "#########",
        ".#######.",
        "..#####..",
        "...###...",
        "....#....",
    )

    /**
     * @param angle where they are, degrees clockwise from the top of the matrix.
     * @param still true for a frame that will stay on the matrix for a while: no pulsing.
     */
    fun render(snap: Snapshot, angle: Double, now: Long, showDistance: Boolean, still: Boolean, dim: Boolean): DotGrid {
        val grid = DotGrid(GlyphShape.SIZE, GlyphShape.SIZE)
        val r = snap.reading
        val merge = Merge.progress(snap.scene == Scene.TOGETHER, snap.changedAt, now)
        when {
            showDistance && snap.scene == Scene.APART && r != null -> distance(grid, r)
            merge != null -> hearts(grid, merge, now, still)
            snap.scene == Scene.APART && r != null -> pointer(grid, r, angle, now, still)
            snap.scene == Scene.NEAR -> stamp(grid, SMALL, C, C, (255 * pulse(now, 900L, still)).toInt())
            snap.scene == Scene.PAUSED -> stamp(grid, SMALL, C, C, 90)
            // Not paired, or waiting to hear from them: a lone centre dot, breathing.
            else -> grid.set(12, 12, (255 * pulse(now, 2_400L, still)).toInt())
        }
        if (dim) for (i in grid.levels.indices) grid.levels[i] = grid.levels[i] / 2
        return grid
    }

    private fun distance(grid: DotGrid, reading: Reading) {
        val d = reading.distance
        val font = DotFont.SMALL
        grid.text(font, d.number, (GlyphShape.SIZE - font.width(d.number)) / 2, 6)
        grid.text(font, d.unit, (GlyphShape.SIZE - font.width(d.unit)) / 2, 14, level = 140)
    }

    /** Two little hearts circling in and merging; [progress] 0 apart .. 1 merged. */
    private fun hearts(grid: DotGrid, progress: Float, now: Long, still: Boolean) {
        if (progress < 0.8f) {
            val q = progress / 0.8f
            val e = q * q
            val turn = Math.toRadians(540.0 * e)
            val r = 7.0 * (1 - e)
            stamp(grid, SMALL, C + r * cos(turn + PI), C + r * sin(turn + PI), 255)
            stamp(grid, SMALL, C + r * cos(turn), C + r * sin(turn), 255)
            return
        }
        val q = (progress - 0.8f) / 0.2f
        if (q < 1f) {
            // A ring of dots bursting outwards as the hearts become one.
            val ring = 7.5 + 4.0 * q
            val level = ((1f - q) * 170).toInt()
            for (y in 0 until GlyphShape.SIZE) for (x in 0 until GlyphShape.SIZE) {
                if (abs(radius(x, y) - ring) <= 0.5) grid.lift(x, y, level)
            }
            stamp(grid, BIG, C, C - 0.5, 255)
            return
        }
        val level = if (still) 255 else (150 + 105 * ((Merge.beat(now) - 1f) / 0.12f)).toInt()
        stamp(grid, BIG, C, C - 0.5, level)
    }

    private fun pointer(grid: DotGrid, reading: Reading, angle: Double, now: Long, still: Boolean) {
        val a = Math.toRadians(angle)
        val dirX = sin(a)
        val dirY = -cos(a)
        val close = reading.meters <= Reading.CLOSE_METERS
        val level = (255 * pulse(now, periodFor(reading.meters), still)).toInt()
        stamp(grid, SMALL, C + TIP_RADIUS * dirX, C + TIP_RADIUS * dirY, level)

        // The needle: dots along the line from the centre, brighter further out.
        if (close) {
            for (y in 0 until GlyphShape.SIZE) for (x in 0 until GlyphShape.SIZE) {
                val dx = x - C
                val dy = y - C
                val along = dx * dirX + dy * dirY
                val across = dx * dirY - dy * dirX
                if (along in 1.5..NEEDLE_END && abs(across) <= 0.6) grid.lift(x, y, (70 + 130 * along / NEEDLE_END).toInt())
            }
        }
        grid.lift(12, 12, if (close) 255 else 110)
    }

    /** Draw [sprite] with its middle at ([cx], [cy]). */
    private fun stamp(grid: DotGrid, sprite: Sprite, cx: Double, cy: Double, level: Int) {
        val left = (cx - (sprite.width - 1) / 2.0).roundToInt()
        val top = (cy - (sprite.height - 1) / 2.0).roundToInt()
        for (y in 0 until sprite.height) for (x in 0 until sprite.width) {
            if (sprite.lit(x, y)) grid.lift(left + x, top + y, level)
        }
    }

    private fun radius(x: Int, y: Int): Double {
        val dx = x - C
        val dy = y - C
        return sqrt(dx * dx + dy * dy)
    }

    /** Heartbeat length: 0.6 s at 100 m or closer, slowing to 3 s at 10,000 km. */
    fun periodFor(meters: Double): Long {
        val t = ((log10(meters.coerceAtLeast(1.0)) - 2.0) / 5.0).coerceIn(0.0, 1.0)
        return (600 + t * 2_400).toLong()
    }

    /** 0.3..1, smoothly, once per [period]. Always 1 for a still frame. */
    private fun pulse(now: Long, period: Long, still: Boolean): Double {
        if (still) return 1.0
        val phase = (now % period).toDouble() / period
        return 0.3 + 0.7 * (0.5 + 0.5 * cos(2 * PI * phase))
    }

    // ---------- Switch-on ----------

    /** How long the switch-on lasts. */
    const val INTRO_MS = 1_250L

    /**
     * The switch-on, played once when this toy is picked, so it's easy to tell apart from the
     * other Somewhere toys: two dots slide in from either side, meet in the middle, become a
     * big heart that beats twice (lub-dub), then fade into the normal picture ([after]).
     */
    fun intro(elapsed: Long, after: DotGrid): DotGrid {
        val grid = DotGrid(GlyphShape.SIZE, GlyphShape.SIZE)
        val t = elapsed.coerceAtLeast(0L)
        when {
            t < 380L -> {
                // Two dots racing in from the edges, each with a fading trail.
                val q = t / 380.0
                val e = q * q
                for (back in 0..2) {
                    val p = (e - back * 0.06).coerceAtLeast(0.0)
                    val step = (p * 10).roundToInt()
                    val level = 255 - back * 90
                    grid.lift(1 + step, 12, level)
                    grid.lift(23 - step, 12, level)
                }
            }
            t < 480L -> stamp(grid, SMALL, C, C, 255)
            t < 620L -> stamp(grid, BIG, C, C - 0.5, 255)
            t < 700L -> stamp(grid, BIG, C, C - 0.5, 110)
            t < 820L -> stamp(grid, BIG, C, C - 0.5, 255)
            else -> {
                // Fade the heart out while the normal picture fades in.
                val q = ((t - 820L) / (INTRO_MS - 820.0)).coerceIn(0.0, 1.0)
                stamp(grid, BIG, C, C - 0.5, (200 * (1 - q)).toInt())
                for (k in after.levels.indices) {
                    val x = k % GlyphShape.SIZE
                    val y = k / GlyphShape.SIZE
                    grid.lift(x, y, (after.levels[k] * q).toInt())
                }
            }
        }
        return grid
    }
}
