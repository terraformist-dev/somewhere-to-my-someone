package dev.terraformist.someone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What there is to show, in order of what matters most. */
enum class Scene { NOT_SET_UP, NOT_PAIRED, NO_LOCATION, WAITING, PAUSED, NEAR, APART, TOGETHER }

/** Everything the screens need, read in one go. */
data class Snapshot(
    val scene: Scene,
    /** What you call them, in capitals. */
    val name: String,
    val reading: Reading?,
    /** When their phone last sent its position. */
    val updatedAt: Long,
    /** When you last came together or parted. */
    val changedAt: Long,
) {
    /** Heard from them in the last few minutes. */
    val live: Boolean get() = System.currentTimeMillis() - updatedAt < LIVE_MS

    companion object {
        const val LIVE_MS = 3 * 60_000L

        fun of(context: Context): Snapshot {
            val them = Us.them(context)
            val reading = Us.reading(context)
            val scene = when {
                !Cloud.ready -> Scene.NOT_SET_UP
                !Us.paired(context) -> Scene.NOT_PAIRED
                !Where.hasLocation(context) -> Scene.NO_LOCATION
                Us.together(context) -> Scene.TOGETHER
                Us.theyPaused(context) -> Scene.PAUSED
                reading == null -> Scene.WAITING
                reading.near -> Scene.NEAR
                else -> Scene.APART
            }
            return Snapshot(scene, Us.name(context), reading, them?.fixAt ?: 0L, Us.changedAt(context))
        }
    }
}

/** The timing of the two hearts coming together and parting. */
object Merge {
    const val MERGE_MS = 3_000L
    const val PART_MS = 2_000L

    /**
     * 0 = two little hearts apart, 1 = one bigger heart. Null when they're apart and
     * nothing is moving, so the compass shows instead.
     */
    fun progress(together: Boolean, changedAt: Long, now: Long): Float? {
        val since = (now - changedAt).coerceAtLeast(0L)
        return when {
            together -> (since.toFloat() / MERGE_MS).coerceIn(0f, 1f)
            since < PART_MS -> (1f - since.toFloat() / PART_MS).coerceIn(0f, 1f)
            else -> null
        }
    }

    /** A heartbeat: a strong beat then a softer one, every 1.6 s. Returns a size, 1.0 at rest. */
    fun beat(now: Long): Float {
        val t = (now % 1_600L) / 1_600f
        val b = when {
            t < 0.12f -> t / 0.12f
            t < 0.24f -> 1f - (t - 0.12f) / 0.12f
            t < 0.34f -> 0.5f * (t - 0.24f) / 0.10f
            t < 0.46f -> 0.5f * (1f - (t - 0.34f) / 0.12f)
            else -> 0f
        }
        return 1f + 0.12f * b
    }
}

/**
 * Everything drawn on screen: the dotted compass with its red heart, the two hearts merging,
 * the dot-matrix numbers and the small Space Mono labels. Shared by the widget and the
 * full-screen compass so they always match.
 */
object Art {

    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFF2F2F2.toInt()
    const val GREY = 0xFF9A9A9A.toInt()
    const val GHOST = 0xFF333333.toInt()
    const val RING = 0xFF3A3A3A.toInt()
    const val RED = 0xFFD71921.toInt()

    private var regular: Typeface? = null
    private var bold: Typeface? = null

    fun typeface(context: Context, isBold: Boolean): Typeface {
        val cached = if (isBold) bold else regular
        if (cached != null) return cached
        val loaded = try {
            Typeface.createFromAsset(context.assets, if (isBold) "SpaceMono-Bold.ttf" else "SpaceMono-Regular.ttf")
        } catch (e: RuntimeException) {
            Typeface.create(Typeface.MONOSPACE, if (isBold) Typeface.BOLD else Typeface.NORMAL)
        }
        if (isBold) bold = loaded else regular = loaded
        return loaded
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---------- The heart ----------

    /** A heart two units wide, centred on 0,0, the same shape as the mockup. */
    private val HEART = Path().apply {
        moveTo(0f, 0.85f)
        cubicTo(-0.35f, 0.55f, -1f, 0.15f, -1f, -0.35f)
        cubicTo(-1f, -0.75f, -0.7f, -1f, -0.45f, -1f)
        cubicTo(-0.2f, -1f, -0.05f, -0.85f, 0f, -0.7f)
        cubicTo(0.05f, -0.85f, 0.2f, -1f, 0.45f, -1f)
        cubicTo(0.7f, -1f, 1f, -0.75f, 1f, -0.35f)
        cubicTo(1f, 0.15f, 0.35f, 0.55f, 0f, 0.85f)
        close()
    }

    /** A red heart centred at ([x], [y]), [halfWidth] from its middle to each side. */
    fun heart(canvas: Canvas, x: Float, y: Float, halfWidth: Float, color: Int = RED, alpha: Int = 255) {
        dotPaint.color = color
        dotPaint.alpha = alpha
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(halfWidth, halfWidth)
        canvas.drawPath(HEART, dotPaint)
        canvas.restore()
        dotPaint.alpha = 255
    }

    // ---------- The compass ----------

    // The compass is designed in a 120-unit box: a ring of 48 dots, a dotted disc, a dotted arrow.
    private val ARROW = arrayOf(
        60 to 20, 50 to 30, 60 to 30, 70 to 30, 40 to 40, 60 to 40, 80 to 40,
        30 to 50, 60 to 50, 90 to 50, 60 to 60, 60 to 70, 60 to 80, 60 to 90, 60 to 100,
    )

    private fun ringAndDisc(canvas: Canvas, north: Double?, rotate: Double?) {
        dotPaint.color = RING
        for (i in 0 until 48) {
            val a = PI * 2 * i / 48
            canvas.drawCircle((60 + 54 * cos(a)).toFloat(), (60 + 54 * sin(a)).toFloat(), 1.6f, dotPaint)
        }
        if (north != null) {
            val a = Math.toRadians(north)
            dotPaint.color = WHITE
            canvas.drawCircle((60 + 54 * sin(a)).toFloat(), (60 - 54 * cos(a)).toFloat(), 2.4f, dotPaint)
        }
        if (rotate != null) canvas.rotate(rotate.toFloat(), 60f, 60f)
        dotPaint.color = GHOST
        for (gy in 0..12) for (gx in 0..12) {
            val x = gx * 10f
            val y = gy * 10f
            val dx = x - 60f
            val dy = y - 60f
            if (sqrt(dx * dx + dy * dy) <= 44f) canvas.drawCircle(x, y, 2f, dotPaint)
        }
    }

    /**
     * The compass pointing to them.
     * @param arrow where they are, in degrees clockwise from the top; null draws just the dial.
     * @param north if set, a small white dot on the ring marks north, at this angle from the top.
     * @param beat the size of the red heart, for a heartbeat (1 = still).
     */
    fun compass(canvas: Canvas, centerX: Float, centerY: Float, size: Float, arrow: Double?, north: Double? = null, beat: Float = 1f) {
        canvas.save()
        canvas.translate(centerX - size / 2f, centerY - size / 2f)
        val scale = size / 120f
        canvas.scale(scale, scale)
        ringAndDisc(canvas, north, arrow)
        if (arrow != null) {
            dotPaint.color = WHITE
            for ((x, y) in ARROW) canvas.drawCircle(x.toFloat(), y.toFloat(), 3.4f, dotPaint)
            heart(canvas, 60f, 6f, 6f * beat)
        }
        canvas.restore()
    }

    /** Too close for a direction: the dial with no arrow and a small heart beating in the middle. */
    fun nearDial(canvas: Canvas, centerX: Float, centerY: Float, size: Float, north: Double? = null, beat: Float = 1f) {
        canvas.save()
        canvas.translate(centerX - size / 2f, centerY - size / 2f)
        val scale = size / 120f
        canvas.scale(scale, scale)
        ringAndDisc(canvas, north, rotate = null)
        heart(canvas, 60f, 61f, 8f * beat)
        canvas.restore()
    }

    /**
     * Two little hearts circling in and becoming one bigger heart.
     * @param progress 0 = apart, 1 = merged (run it backwards for parting).
     * @param beat the merged heart's heartbeat size.
     */
    fun hearts(canvas: Canvas, centerX: Float, centerY: Float, size: Float, progress: Float, beat: Float = 1f) {
        canvas.save()
        canvas.translate(centerX - size / 2f, centerY - size / 2f)
        val scale = size / 120f
        canvas.scale(scale, scale)
        ringAndDisc(canvas, north = null, rotate = null)

        if (progress < 0.8f) {
            // Circling in, faster and faster, one and a half turns.
            val q = progress / 0.8f
            val e = q * q
            val turn = Math.toRadians(540.0 * e)
            val r = 26f * (1f - e)
            heart(canvas, (60 + r * cos(turn + PI)).toFloat(), (60 + r * sin(turn + PI)).toFloat(), 7f)
            heart(canvas, (60 + r * cos(turn)).toFloat(), (60 + r * sin(turn)).toFloat(), 7f)
        } else {
            // Pop: a ring of red dots spreads out and fades as the heart settles.
            val q = (progress - 0.8f) / 0.2f
            if (q < 1f) {
                val ringR = 22f * (0.5f + 1.1f * q)
                dotPaint.color = RED
                dotPaint.alpha = ((1f - q) * 230).toInt()
                for (i in 0 until 20) {
                    val a = PI * 2 * i / 20
                    canvas.drawCircle((60 + ringR * cos(a)).toFloat(), (60 + ringR * sin(a)).toFloat(), 1.4f, dotPaint)
                }
                dotPaint.alpha = 255
            }
            val s = if (q < 1f) 11f * (1.25f - 0.25f * q) else 11f * beat
            heart(canvas, 60f, 61f, s)
        }
        canvas.restore()
    }

    // ---------- Dot-matrix text ----------

    /** Draws [text] in dots, top-left at ([left], [top]), [pitch] pixels between dot centres. Returns its width. */
    fun dots(canvas: Canvas, font: DotFont, text: String, left: Float, top: Float, pitch: Float, color: Int = WHITE): Float {
        dotPaint.color = color
        val r = pitch * 0.42f
        font.forEachDot(text) { x, y ->
            canvas.drawCircle(left + x * pitch + pitch / 2f, top + y * pitch + pitch / 2f, r, dotPaint)
        }
        return font.width(text) * pitch
    }

    fun dotsWidth(font: DotFont, text: String, pitch: Float): Float = font.width(text) * pitch

    /** Draws dotted [text] centred on [centerX], shrinking the dots to fit [maxWidth]. Returns its height. */
    fun dotsCentered(canvas: Canvas, text: String, centerX: Float, top: Float, pitch: Float, maxWidth: Float, color: Int = WHITE): Float {
        val p = min(pitch, maxWidth / DotFont.LARGE.width(text).coerceAtLeast(1))
        dots(canvas, DotFont.LARGE, text, centerX - dotsWidth(DotFont.LARGE, text, p) / 2f, top, p, color)
        return 7 * p
    }

    // ---------- Plain labels ----------

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()

    private fun setUpText(context: Context, size: Float, isBold: Boolean, color: Int) {
        textPaint.typeface = typeface(context, isBold)
        textPaint.textSize = size
        textPaint.color = color
        textPaint.letterSpacing = 0.08f
    }

    fun textWidth(context: Context, text: String, size: Float, isBold: Boolean = false): Float {
        setUpText(context, size, isBold, WHITE)
        return textPaint.measureText(text)
    }

    fun capHeight(context: Context, size: Float, isBold: Boolean = false): Float {
        setUpText(context, size, isBold, WHITE)
        textPaint.getTextBounds("H", 0, 1, bounds)
        return bounds.height().toFloat()
    }

    /** Draws [text] with its capital letters' top at [top]. Shrinks it to fit [maxWidth]. Returns the cap height. */
    fun label(
        context: Context,
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        size: Float,
        color: Int = GREY,
        isBold: Boolean = false,
        maxWidth: Float = Float.MAX_VALUE,
        centered: Boolean = false,
    ): Float {
        setUpText(context, size, isBold, color)
        var w = textPaint.measureText(text)
        if (w > maxWidth) {
            textPaint.textSize = size * maxWidth / w
            w = maxWidth
        }
        textPaint.getTextBounds("H", 0, 1, bounds)
        val cap = bounds.height().toFloat()
        val x = if (centered) left - w / 2f else left
        canvas.drawText(text, x, top + cap, textPaint)
        return cap
    }

    /** A small round dot before a status line. */
    fun statusDot(canvas: Canvas, centerX: Float, centerY: Float, size: Float, color: Int) {
        dotPaint.color = color
        canvas.drawCircle(centerX, centerY, size / 2f, dotPaint)
    }

    // ---------- Words ----------

    /** The short message shown instead of a distance. */
    fun waitingWords(snap: Snapshot): String = when (snap.scene) {
        Scene.NOT_SET_UP -> "NOT SET UP YET"
        Scene.NOT_PAIRED -> "TAP TO PAIR"
        Scene.NO_LOCATION -> "TAP TO START"
        Scene.PAUSED -> "${snap.name} PAUSED"
        else -> "WAITING FOR ${snap.name}"
    }

    /** "LIVE · 38° NE", or "UPDATED 12 MIN AGO" once it's a few minutes old. */
    fun freshness(snap: Snapshot): String {
        val r = snap.reading
        return when {
            !snap.live -> "UPDATED ${Geo.ago(snap.updatedAt)}"
            r == null || r.far -> "LIVE"
            else -> "LIVE · ${r.bearingLabel}"
        }
    }

    fun describe(snap: Snapshot): String {
        val name = snap.name.lowercase()
        return when (snap.scene) {
            Scene.TOGETHER -> "Somewhere to my Someone. You're with $name."
            Scene.NEAR -> "Somewhere to my Someone. $name is near, within about ${snap.reading?.nearWithin ?: 20} metres."
            Scene.APART -> {
                val r = snap.reading ?: return "Somewhere to my Someone."
                val d = r.distance
                val unit = if (d.unit == "KM") "kilometres" else "metres"
                "Somewhere to my Someone. $name is ${d.number} $unit away, heading ${Geo.cardinal(r.bearing)}."
            }
            else -> "Somewhere to my Someone. ${waitingWords(snap).lowercase()}."
        }
    }

    // ---------- The home-screen widget ----------

    /**
     * Draws the widget's contents at its real size in pixels. Wide widgets (4×2) put the
     * compass on the left and the details on the right; square ones (2×2) stack the compass
     * over the distance. The top of the widget compass is north, like a map, because a widget
     * can't follow the phone as it turns.
     *
     * @param merge if set, the hearts animation at this point (0 apart .. 1 merged).
     */
    fun widget(context: Context, width: Int, height: Int, snap: Snapshot, merge: Float?): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (width >= height * 1.6f) wide(context, canvas, width.toFloat(), height.toFloat(), snap, merge)
        else square(context, canvas, width.toFloat(), height.toFloat(), snap, merge)
        return bmp
    }

    private fun dial(canvas: Canvas, cx: Float, cy: Float, size: Float, snap: Snapshot, merge: Float?) {
        val r = snap.reading
        when {
            merge != null -> hearts(canvas, cx, cy, size, merge)
            snap.scene == Scene.TOGETHER -> hearts(canvas, cx, cy, size, 1f)
            snap.scene == Scene.NEAR -> nearDial(canvas, cx, cy, size, north = 0.0)
            snap.scene == Scene.APART && r != null -> compass(canvas, cx, cy, size, arrow = r.bearing, north = 0.0)
            else -> compass(canvas, cx, cy, size, arrow = null, north = 0.0)
        }
    }

    private fun wide(context: Context, canvas: Canvas, w: Float, h: Float, snap: Snapshot, merge: Float?) {
        val u = h / 170f
        val pad = 20f * u
        val size = min(h - 2 * pad, w * 0.4f)
        dial(canvas, pad + size / 2f, h / 2f, size, snap, merge)

        val left = pad + size + 18f * u
        val room = w - left - pad
        val nameSize = 12f * u
        val statusSize = 11f * u
        val r = snap.reading

        when {
            snap.scene == Scene.TOGETHER -> {
                val pitch = min(3.6f * u, room / DotFont.LARGE.width("YOUR SOMEONE"))
                val headline = 7 * pitch * 2 + pitch * 2
                val top = (h - (headline + 10f * u + nameSize + 8f * u + statusSize)) / 2f
                dots(canvas, DotFont.LARGE, "YOU'RE WITH", left, top, pitch)
                dots(canvas, DotFont.LARGE, "YOUR SOMEONE", left, top + 9 * pitch, pitch)
                val nameTop = top + headline + 10f * u
                label(context, canvas, snap.name, left, nameTop, nameSize, GREY, maxWidth = room)
                status(context, canvas, left, nameTop + nameSize + 8f * u, statusSize, u, RED, "RIGHT HERE", w - pad)
            }
            snap.scene == Scene.APART && r != null -> {
                val d = r.distance
                val unitSize = 14f * u
                val unitW = textWidth(context, d.unit, unitSize, isBold = true)
                val pitch = min(6f * u, (room - unitW - 6f * u) / DotFont.LARGE.width(d.number))
                val headline = 7 * pitch
                val top = (h - (headline + 12f * u + nameSize + 8f * u + statusSize)) / 2f
                val numberW = dots(canvas, DotFont.LARGE, d.number, left, top, pitch)
                val unitCap = capHeight(context, unitSize, isBold = true)
                label(context, canvas, d.unit, left + numberW + 6f * u, top + headline - unitCap, unitSize, WHITE, isBold = true)
                val nameTop = top + headline + 12f * u
                label(context, canvas, snap.name, left, nameTop, nameSize, GREY, maxWidth = room)
                val statusTop = nameTop + nameSize + 8f * u
                if (snap.live) {
                    status(context, canvas, left, statusTop, statusSize, u, WHITE, freshness(snap), w - pad)
                } else {
                    label(context, canvas, freshness(snap), left, statusTop, statusSize, WHITE, maxWidth = room)
                }
            }
            snap.scene == Scene.NEAR && r != null -> {
                val pitch = min(6f * u, room / DotFont.LARGE.width("NEAR"))
                val headline = 7 * pitch
                val top = (h - (headline + 12f * u + nameSize + 8f * u + statusSize)) / 2f
                dots(canvas, DotFont.LARGE, "NEAR", left, top, pitch)
                val nameTop = top + headline + 12f * u
                label(context, canvas, snap.name, left, nameTop, nameSize, GREY, maxWidth = room)
                val words = "WITHIN ${r.nearWithin} M" + if (snap.live) "" else " · ${Geo.ago(snap.updatedAt)}"
                status(context, canvas, left, nameTop + nameSize + 8f * u, statusSize, u, WHITE, words, w - pad)
            }
            snap.scene == Scene.PAUSED -> {
                val pitch = min(5f * u, room / DotFont.LARGE.width("PAUSED"))
                val headline = 7 * pitch
                val top = (h - (headline + 12f * u + nameSize + 8f * u + statusSize)) / 2f
                dots(canvas, DotFont.LARGE, "PAUSED", left, top, pitch)
                val nameTop = top + headline + 12f * u
                label(context, canvas, snap.name, left, nameTop, nameSize, GREY, maxWidth = room)
                label(context, canvas, "HAS PAUSED SHARING", left, nameTop + nameSize + 8f * u, statusSize, WHITE, maxWidth = room)
            }
            else -> {
                val pitch = 6f * u
                val block = 7 * pitch + 12f * u + nameSize
                val top = (h - block) / 2f
                dots(canvas, DotFont.LARGE, "--", left, top, pitch)
                label(context, canvas, waitingWords(snap), left, top + 7 * pitch + 12f * u, nameSize, GREY, maxWidth = room)
            }
        }
    }

    /** A status line with a small dot in front of it. */
    private fun status(context: Context, canvas: Canvas, left: Float, top: Float, size: Float, u: Float, dotColor: Int, text: String, right: Float) {
        val dotSize = 6f * u
        val cap = capHeight(context, size)
        statusDot(canvas, left + dotSize / 2f, top + cap / 2f, dotSize, dotColor)
        val textLeft = left + dotSize + 8f * u
        label(context, canvas, text, textLeft, top, size, WHITE, maxWidth = right - textLeft)
    }

    private fun square(context: Context, canvas: Canvas, w: Float, h: Float, snap: Snapshot, merge: Float?) {
        val u = min(w, h) / 170f
        val pad = 14f * u
        val size = min(92f * u, h * 0.56f)
        val compassTop = pad + 2f * u
        dial(canvas, w / 2f, compassTop + size / 2f, size, snap, merge)

        val areaTop = compassTop + size
        val areaHeight = h - pad - areaTop
        val room = w - 2 * pad
        val r = snap.reading

        when {
            snap.scene == Scene.TOGETHER || snap.scene == Scene.PAUSED || snap.scene == Scene.NEAR -> {
                val word = when (snap.scene) {
                    Scene.TOGETHER -> "TOGETHER"
                    Scene.NEAR -> "NEAR"
                    else -> "PAUSED"
                }
                val pitch = min(3.4f * u, room / DotFont.LARGE.width(word))
                dotsCentered(canvas, word, w / 2f, areaTop + (areaHeight - 7 * pitch) / 2f, pitch, room)
            }
            snap.scene == Scene.APART && r != null -> {
                val d = r.distance
                val unitSize = 12f * u
                val gap = 5f * u
                val unitW = textWidth(context, d.unit, unitSize, isBold = true)
                val pitch = min(4.2f * u, (room - unitW - gap) / DotFont.LARGE.width(d.number))
                val numberW = dotsWidth(DotFont.LARGE, d.number, pitch)
                val left = (w - (numberW + gap + unitW)) / 2f
                val top = areaTop + (areaHeight - 7 * pitch) / 2f
                dots(canvas, DotFont.LARGE, d.number, left, top, pitch)
                val unitCap = capHeight(context, unitSize, isBold = true)
                label(context, canvas, d.unit, left + numberW + gap, top + 7 * pitch - unitCap, unitSize, WHITE, isBold = true)
            }
            else -> {
                val size2 = 11f * u
                label(context, canvas, waitingWords(snap), w / 2f, areaTop + (areaHeight - size2 * 0.7f) / 2f, size2, GREY, maxWidth = room, centered = true)
            }
        }
    }
}
