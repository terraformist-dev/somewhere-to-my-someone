package dev.terraformist.someone

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The full-screen compass. Unlike the widget, the arrow turns with the phone: it points at
 * them from wherever you're facing. A small white dot on the ring marks north. When you come
 * together, the two hearts circle in and merge; when you part, they split again.
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var snap: Snapshot = Snapshot(Scene.WAITING, "YOUR SOMEONE", null, 0L, 0L)

    /** Which way the top of the screen faces (degrees from north), or null if unknown. */
    var heading: Double? = null

    /** Where the title sits, so a tap area can be put on it. */
    fun titleCenterY(): Float {
        val u = width / 390f
        return 40f * u + Art.capHeight(context, 11f * u) / 2f
    }

    fun titleWidth(): Float = Art.textWidth(context, TITLE, 11f * (width / 390f))

    fun update(snapshot: Snapshot) {
        snap = snapshot
        contentDescription = Art.describe(snapshot)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Art.BLACK)
        val now = System.currentTimeMillis()
        val w = width.toFloat()
        val u = w / 390f
        val cx = w / 2f
        val room = w - 48f * u

        var y = 40f * u
        Art.label(context, canvas, TITLE, cx, y, 11f * u, Art.GREY, centered = true)
        y += 11f * u + 48f * u

        // Leave room at the bottom for the button.
        val size = min(300f * u, height - y - 320f * u).coerceAtLeast(160f * u)
        val r = snap.reading
        val h = heading
        val together = snap.scene == Scene.TOGETHER
        val merge = Merge.progress(together, snap.changedAt, now)
        val beat = Merge.beat(now)
        val animating = merge != null

        if (merge != null) {
            Art.hearts(canvas, cx, y + size / 2f, size, merge, beat)
        } else if (snap.scene == Scene.APART && r != null) {
            val arrow = if (h != null) r.bearing - h else r.bearing
            val north = if (h != null) -h else 0.0
            Art.compass(canvas, cx, y + size / 2f, size, arrow, north, beat)
        } else {
            Art.compass(canvas, cx, y + size / 2f, size, arrow = null, north = h?.let { -it } ?: 0.0)
        }
        y += size + 28f * u

        when {
            together -> {
                y += Art.dotsCentered(canvas, "TOGETHER", cx, y, 7.5f * u, room) + 18f * u
                Art.label(context, canvas, "${snap.name} IS RIGHT HERE", cx, y, 13f * u, Art.GREY, maxWidth = room, centered = true)
                y += 13f * u + 14f * u
                val minutes = ((now - snap.changedAt) / 60_000L).coerceAtLeast(0L)
                val since = if (minutes < 1) "TOGETHER NOW" else "TOGETHER FOR ${minutes} MIN"
                statusLine(canvas, since, Art.RED, cx, y, u, room)
            }
            snap.scene == Scene.APART && r != null -> {
                val d = r.distance
                val unitSize = 18f * u
                val gap = 10f * u
                val unitW = Art.textWidth(context, d.unit, unitSize, isBold = true)
                val pitch = min(11f * u, (room - unitW - gap) / DotFont.LARGE.width(d.number))
                val numberW = Art.dotsWidth(DotFont.LARGE, d.number, pitch)
                val left = cx - (numberW + gap + unitW) / 2f
                Art.dots(canvas, DotFont.LARGE, d.number, left, y, pitch)
                val unitCap = Art.capHeight(context, unitSize, isBold = true)
                Art.label(context, canvas, d.unit, left + numberW + gap, y + 7 * pitch - unitCap, unitSize, Art.WHITE, isBold = true)
                y += 7 * pitch + 16f * u
                Art.label(context, canvas, "TO ${snap.name}", cx, y, 13f * u, Art.GREY, maxWidth = room, centered = true)
                y += 13f * u + 14f * u
                val parts = mutableListOf<String>()
                if (!r.far) parts += r.bearingLabel
                parts += if (snap.live) "LIVE · ${Geo.ago(snap.updatedAt)}" else "UPDATED ${Geo.ago(snap.updatedAt)}"
                Art.label(context, canvas, parts.joinToString("  /  "), cx, y, 12f * u, Art.WHITE, maxWidth = room, centered = true)
            }
            snap.scene == Scene.PAUSED -> {
                y += Art.dotsCentered(canvas, "PAUSED", cx, y, 9f * u, room) + 18f * u
                Art.label(context, canvas, "${snap.name} HAS PAUSED SHARING", cx, y, 13f * u, Art.GREY, maxWidth = room, centered = true)
            }
            else -> {
                val pitch = 11f * u
                Art.dots(canvas, DotFont.LARGE, "--", cx - Art.dotsWidth(DotFont.LARGE, "--", pitch) / 2f, y, pitch)
                y += 7 * pitch + 16f * u
                val words = when (snap.scene) {
                    Scene.NO_LOCATION -> "LOCATION IS OFF"
                    Scene.WAITING -> "WAITING FOR ${snap.name}"
                    else -> Art.waitingWords(snap)
                }
                Art.label(context, canvas, words, cx, y, 13f * u, Art.GREY, maxWidth = room, centered = true)
            }
        }

        // Keep drawing while something moves: the merge, and the heartbeat.
        if (animating || snap.scene == Scene.APART) postInvalidateOnAnimation()
    }

    private fun statusLine(canvas: Canvas, text: String, dotColor: Int, cx: Float, top: Float, u: Float, room: Float) {
        val size = 12f * u
        val dot = 6f * u
        val gap = 10f * u
        val textW = min(Art.textWidth(context, text, size), room - dot - gap)
        val left = cx - (dot + gap + textW) / 2f
        Art.statusDot(canvas, left + dot / 2f, top + Art.capHeight(context, size) / 2f, dot, dotColor)
        Art.label(context, canvas, text, left + dot + gap, top, size, Art.WHITE, maxWidth = textW)
    }

    private companion object {
        const val TITLE = "SOMEWHERE TO MY SOMEONE"
    }
}
