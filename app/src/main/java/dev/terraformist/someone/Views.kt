package dev.terraformist.someone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/** A line of dot-matrix text, for headings like "PAIR WITH YOUR SOMEONE" and the pairing code. */
class DotTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var text: String = ""
        set(value) {
            field = value
            contentDescription = value
            requestLayout()
            invalidate()
        }

    /** Pixels between dot centres. */
    var pitch: Float = 3.4f * resources.displayMetrics.density
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var color: Int = Art.WHITE
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (DotFont.LARGE.width(text) * pitch).roundToInt() + paddingLeft + paddingRight
        val h = (DotFont.LARGE.rows(text) * pitch).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        // Shrink to fit if the text is wider than the space.
        val room = (width - paddingLeft - paddingRight).toFloat()
        val p = minOf(pitch, room / DotFont.LARGE.width(text).coerceAtLeast(1))
        val left = paddingLeft + (room - DotFont.LARGE.width(text) * p) / 2f
        Art.dots(canvas, DotFont.LARGE, text, left, paddingTop.toFloat(), p, color)
    }
}

/** The outline heart made of red dots at the top of the pairing screen. */
class PixelHeartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Art.RED }

    private val heart = Sprite(
        "..#...#..",
        ".#.#.#.#.",
        "#...#...#",
        "#.......#",
        ".#.....#.",
        "..#...#..",
        "...#.#...",
        "....#....",
    )

    override fun onDraw(canvas: Canvas) {
        val pitch = minOf(width / heart.width.toFloat(), height / heart.height.toFloat())
        val left = (width - pitch * heart.width) / 2f
        val top = (height - pitch * heart.height) / 2f
        for (y in 0 until heart.height) for (x in 0 until heart.width) {
            if (heart.lit(x, y)) canvas.drawCircle(left + (x + 0.5f) * pitch, top + (y + 0.5f) * pitch, pitch * 0.4f, paint)
        }
    }
}

/** A small solid red heart, next to their name in settings. */
class MiniHeartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    override fun onDraw(canvas: Canvas) {
        val half = minOf(width, height) / 2f * 0.9f
        Art.heart(canvas, width / 2f, height / 2f + half * 0.07f, half)
    }
}
