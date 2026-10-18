package dev.terraformist.someone

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.location.LocationListener
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.util.Log
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphToy

/**
 * The Glyph Toy: a heart on the Glyph Matrix on the back of the phone that points to your someone.
 *
 * - The heart on the edge turns live as you turn, beating faster the closer they are.
 * - When you come together the hearts merge in the middle; when you part, they split.
 * - Long-press the Glyph Button: shows the distance in numbers for a few seconds.
 * - It stays live while the screen is on, and for a minute after you touch the Glyph Button
 *   or something changes. After that it holds a still frame so the phone can sleep.
 *
 * The system only keeps this running while the toy is selected.
 */
class HeartToyService : Service() {

    private var matrix: GlyphMatrixManager? = null
    private var connected = false

    private val main = Handler(Looper.getMainLooper())
    private val power by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val wakeLock by lazy {
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SomewhereToMySomeone:heart").apply {
            setReferenceCounted(false)
        }
    }

    private val heading by lazy { Heading(applicationContext, Facing.BACK) {} }
    private var watcher: LocationListener? = null
    private var sensing = false

    /** When the switch-on started (0 = not playing). */
    private var introAt = 0L

    private var liveUntil = 0L
    private var distanceUntil = 0L
    private var alwaysOn = false

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val screenOn = power.isInteractive
            if (screenOn) alwaysOn = false
            if (screenOn || now < liveUntil) {
                startSensing()
                draw(now, still = false)
                main.postDelayed(this, FRAME_MS)
            } else {
                draw(now, still = true)
                stopSensing()
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private val buttonEvents = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != GlyphToy.MSG_GLYPH_TOY) {
                super.handleMessage(msg)
                return
            }
            val now = System.currentTimeMillis()
            when (msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)) {
                GlyphToy.EVENT_CHANGE -> {
                    distanceUntil = now + DISTANCE_MS
                    liveFor(LIVE_MS)
                }
                GlyphToy.EVENT_AOD -> {
                    if (!power.isInteractive && now >= liveUntil) {
                        alwaysOn = true
                        liveFor(AOD_MS)
                    }
                }
                GlyphToy.EVENT_ACTION_DOWN, GlyphToy.EVENT_ACTION_UP -> {
                    alwaysOn = false
                    liveFor(LIVE_MS)
                }
            }
        }
    }
    private val messenger = Messenger(buttonEvents)

    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                alwaysOn = false
                liveFor(LIVE_MS)
            }
        }
    }
    private var receiverRegistered = false

    /** Coming together or parting: wake up to show the hearts. */
    private val usChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "together") main.post {
            alwaysOn = false
            liveFor(LIVE_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        val manager = GlyphMatrixManager.getInstance(applicationContext)
        matrix = manager
        manager.init(object : GlyphMatrixManager.Callback {
            override fun onServiceConnected(name: ComponentName?) {
                manager.register(Glyph.DEVICE_23112)
                connected = true
                introAt = System.currentTimeMillis()
                Where.refresh(applicationContext) { location -> location?.let { heading.setLocation(it) } }
                liveFor(LIVE_MS)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected = false
                main.removeCallbacks(tick)
                stopSensing()
            }
        })
        if (!receiverRegistered) {
            registerReceiver(screenEvents, IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED)
            Us.listen(applicationContext, usChanged)
            receiverRegistered = true
        }
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        main.removeCallbacks(tick)
        stopSensing()
        if (wakeLock.isHeld) wakeLock.release()
        if (receiverRegistered) {
            unregisterReceiver(screenEvents)
            Us.unlisten(applicationContext, usChanged)
            receiverRegistered = false
        }
        connected = false
        matrix?.let {
            try {
                it.turnOff()
            } catch (e: Exception) {
                Log.w(TAG, "turnOff failed", e)
            }
            it.unInit()
        }
        matrix = null
        return false
    }

    /** Animate for [ms], keeping the processor awake just long enough (the screen is usually off). */
    private fun liveFor(ms: Long) {
        liveUntil = maxOf(liveUntil, System.currentTimeMillis() + ms)
        wakeLock.acquire(ms + 1_000L)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun startSensing() {
        if (sensing) return
        sensing = true
        heading.start()
        watcher = Where.watch(applicationContext, everyMs = 5_000L) { heading.setLocation(it) }
    }

    private fun stopSensing() {
        if (!sensing) return
        sensing = false
        heading.stop()
        Where.stop(applicationContext, watcher)
        watcher = null
    }

    private fun draw(now: Long, still: Boolean) {
        val manager = matrix ?: return
        if (!connected) return
        val snap = Snapshot.of(applicationContext)
        val facing = heading.degrees
        // Without a compass reading yet, the top of the matrix stands for north.
        val angle = snap.reading?.let { if (facing != null) it.bearing - facing else it.bearing } ?: 0.0
        var grid = MatrixArt.render(snap, angle, now, showDistance = now < distanceUntil, still = still, dim = alwaysOn)
        if (introAt != 0L) {
            if (now - introAt < MatrixArt.INTRO_MS && !still) grid = MatrixArt.intro(now - introAt, grid) else introAt = 0L
        }
        val frame = IntArray(GlyphShape.SIZE * GlyphShape.SIZE) { i ->
            val x = i % GlyphShape.SIZE
            val y = i / GlyphShape.SIZE
            if (GlyphShape.contains(x, y)) grid.levels[i] * 4095 / 255 else 0
        }
        try {
            manager.setMatrixFrame(frame)
        } catch (e: Exception) {
            Log.w(TAG, "setMatrixFrame failed", e)
        }
    }

    private companion object {
        const val TAG = "SomewhereToMySomeone"
        const val FRAME_MS = 50L
        const val LIVE_MS = 60_000L
        const val AOD_MS = 4_000L
        const val DISTANCE_MS = 4_000L
    }
}
