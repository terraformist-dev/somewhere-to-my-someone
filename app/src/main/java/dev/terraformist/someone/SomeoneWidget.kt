package dev.terraformist.someone

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.SizeF
import android.widget.RemoteViews
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The home-screen widget, in two sizes ([Small] 2×2 and [Wide] 4×2) that share everything.
 * Both work at any size: wide shapes get the side-by-side layout, square-ish ones the stacked one.
 *
 * The sharing service redraws it whenever there's news. When you come together or part,
 * it plays the hearts as a short flip-book, because widgets can't run smooth animations.
 */
open class SomeoneWidget : AppWidgetProvider() {

    class Small : SomeoneWidget()
    class Wide : SomeoneWidget()

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAll(context)
        // Every half hour: make sure sharing is still running (Android may say no from here; that's fine).
        if (!SharingService.running) SharingService.start(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        draw(context, manager, id, null)
    }

    companion object {

        private val main = Handler(Looper.getMainLooper())
        private const val FRAMES = 14
        private val TOKEN = Any()

        /** While the flip-book plays, ordinary redraws wait so they don't interrupt it. */
        @Volatile private var animatingUntil = 0L

        /** Redraw every copy of both widgets. */
        fun refreshAll(context: Context) {
            if (android.os.SystemClock.uptimeMillis() < animatingUntil) return
            drawAll(context, null)
        }

        /** Play the hearts merging (or parting) as a flip-book, then settle. */
        fun animate(context: Context, merging: Boolean) {
            val app = context.applicationContext
            main.removeCallbacksAndMessages(TOKEN)
            val total = if (merging) Merge.MERGE_MS else Merge.PART_MS
            animatingUntil = android.os.SystemClock.uptimeMillis() + total
            for (i in 0..FRAMES) {
                val t = i.toFloat() / FRAMES
                val progress = if (merging) t else 1f - t
                main.postAtTime({ drawAll(app, if (i == FRAMES && !merging) null else progress) }, TOKEN,
                    android.os.SystemClock.uptimeMillis() + (total * t).toLong())
            }
        }

        private fun drawAll(context: Context, merge: Float?) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            for (kind in listOf(Small::class.java, Wide::class.java)) {
                for (id in manager.getAppWidgetIds(ComponentName(context, kind))) draw(context, manager, id, merge)
            }
        }

        private fun draw(context: Context, manager: AppWidgetManager, id: Int, merge: Float?) {
            val (widthDp, heightDp) = sizeDp(manager.getAppWidgetOptions(id))
            val density = context.resources.displayMetrics.density
            // Keep the picture a sensible size; it's scaled to fit anyway.
            val scale = minOf(density, MAX_SIDE_PX / max(widthDp, heightDp))
            val width = (widthDp * scale).roundToInt()
            val height = (heightDp * scale).roundToInt()

            val snap = Snapshot.of(context)
            val picture = Art.widget(context, width, height, snap, merge)

            val views = RemoteViews(context.packageName, R.layout.widget_someone)
            views.setImageViewBitmap(R.id.art, picture)
            views.setContentDescription(R.id.art, Art.describe(snap))
            views.setOnClickPendingIntent(R.id.art, SharingService.openApp(context))
            manager.updateAppWidget(id, views)
        }

        /** The widget's size on screen, in dp, as the home screen reports it (portrait). */
        private fun sizeDp(options: Bundle): Pair<Float, Float> {
            val sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            val first = sizes?.firstOrNull()
            if (first != null && first.width > 0 && first.height > 0) return first.width to first.height
            val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170)
            val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 170)
            return w.coerceAtLeast(40).toFloat() to h.coerceAtLeast(40).toFloat()
        }

        private const val MAX_SIDE_PX = 900f
    }
}
