package dev.terraformist.someone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import java.util.concurrent.Executors

/**
 * Keeps the two phones in touch while the app is closed. Android insists on a notification
 * while it runs, which is a good thing for an app that shares where you are.
 *
 * It sends this phone's position and fetches theirs, more often the closer you are:
 * every 5 minutes when far apart, every 30 seconds within a kilometre, every 5 seconds
 * while the app is open. Within 500 m it turns on the Bluetooth check, which is what
 * decides "together" (hearts merge) and "apart" (hearts split).
 */
class SharingService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val judge = TogetherJudge()
    private lateinit var near: Near

    private var watcher: LocationListener? = null
    private var watchEvery = 0L
    private var syncing = false
    private var lastSyncAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            sync()
            main.postDelayed(this, syncEvery())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        near = Near(this) { rssi ->
            main.post {
                judge.sighting(rssi, System.currentTimeMillis())
                evaluate()
            }
        }
        if (Us.together(this)) judge.assumeNear(System.currentTimeMillis())
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Us.paired(this) || !Cloud.ready || !goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> Us.pauseFor(this, PAUSE_MS)
            ACTION_RESUME -> Us.setSharing(this, true)
        }
        evaluate()
        main.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        near.stop()
        Where.stop(this, watcher)
        watcher = null
        worker.shutdown()
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        if (!Where.hasLocation(this)) return false
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Near.permitted(this)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        return try {
            startForeground(NOTE_ID, notification(), types)
            true
        } catch (e: Exception) {
            // Not allowed to start from the background right now.
            Log.w(TAG, "Couldn't start sharing", e)
            false
        }
    }

    // ---------- Talking to the other phone ----------

    private fun sync() {
        if (syncing) return
        syncing = true
        val location = Where.last(this)
        val together = Us.together(this)
        worker.execute {
            var result = Sync.Result.OK
            try {
                result = Sync.send(this, location, together)
                if (result == Sync.Result.OK) result = Sync.fetch(this)
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed", e)
            }
            main.post {
                syncing = false
                lastSyncAt = System.currentTimeMillis()
                when (result) {
                    Sync.Result.ENDED -> ended()
                    Sync.Result.NOT_PAIRED -> stopEverything()
                    Sync.Result.OK -> evaluate()
                }
            }
        }
    }

    private fun onMoved() {
        evaluate()
        // Location updates still arrive when the phone is dozing; use them to catch up.
        if (!syncing && System.currentTimeMillis() - lastSyncAt >= syncEvery()) main.post(tick)
    }

    // ---------- Deciding what to do ----------

    private fun evaluate() {
        if (!Us.paired(this)) return
        val now = System.currentTimeMillis()
        val reading = Us.reading(this)
        val them = Us.them(this)
        val me = Where.last(this)
        val theyPaused = Us.theyPaused(this)
        val sending = Us.sendingNow(this)

        val fresh = them != null && me != null && now - them.fixAt < FRESH_MS && now - me.time < FRESH_MS
        val gpsMeters = if (fresh && !theyPaused) reading?.meters else null
        val bluetooth = Near.permitted(this) && bluetoothOn() && Us.theyHaveBluetooth(this)

        val was = Us.together(this)
        val together = sending && !theyPaused && judge.decide(now, was, bluetooth, gpsMeters)
        if (together != was) {
            Us.setTogether(this, together)
            if (!together) judge.reset()
            onTogetherChanged(together)
        }

        // Bluetooth: only when it could matter.
        val meters = if (theyPaused) null else reading?.meters
        val nearby = meters != null && meters < NEARBY_METERS
        near.set(
            advertise = when {
                !sending -> Near.Speed.OFF
                together -> Near.Speed.SLOW
                nearby -> Near.Speed.NORMAL
                else -> Near.Speed.OFF
            },
            scan = when {
                theyPaused || !sending -> Near.Speed.OFF
                together -> Near.Speed.SLOW
                nearby -> Near.Speed.NORMAL
                else -> Near.Speed.OFF
            },
        )

        // Following this phone: faster when it matters.
        val every = when {
            appOpen -> 3_000L
            together -> 120_000L
            meters == null -> 120_000L
            meters < 1_000 -> 20_000L
            meters < 5_000 -> 60_000L
            else -> 300_000L
        }
        if (every != watchEvery || watcher == null) {
            Where.stop(this, watcher)
            watcher = Where.watch(this, every) { onMoved() }
            watchEvery = every
        }

        SomeoneWidget.refreshAll(this)
        getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, notification())
    }

    private fun syncEvery(): Long {
        val meters = Us.reading(this)?.meters
        return when {
            appOpen -> 5_000L
            Us.together(this) -> 120_000L
            meters == null -> 300_000L
            meters < 1_000 -> 30_000L
            meters < 5_000 -> 120_000L
            else -> 300_000L
        }
    }

    private fun bluetoothOn(): Boolean = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    private fun onTogetherChanged(together: Boolean) {
        SomeoneWidget.animate(this, merging = together)
        if (together) heartbeat()
        // Let the other phone know straight away.
        main.post(tick)
    }

    /** Two quick taps, like a heartbeat. */
    private fun heartbeat() {
        try {
            getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 35, 110, 55), -1),
            )
        } catch (e: Exception) {
            // No vibration: fine.
        }
    }

    /** The other phone unpaired. */
    private fun ended() {
        val name = Us.name(this)
        Us.forget(this)
        getSystemService(NotificationManager::class.java)?.notify(
            ENDED_ID,
            Notification.Builder(this, CHANNEL_NEWS)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle("$name ended the pairing")
                .setContentText("Neither of you is sharing now. Open the app to pair again.")
                .setContentIntent(openApp(this))
                .setAutoCancel(true)
                .build(),
        )
        stopEverything()
    }

    private fun stopEverything() {
        near.stop()
        Where.stop(this, watcher)
        watcher = null
        SomeoneWidget.refreshAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- The notification ----------

    private fun notification(): Notification {
        val name = Us.name(this)
        val now = System.currentTimeMillis()
        val pausedUntil = Us.pausedUntil(this)
        val text = when {
            !Us.sharing(this) -> "You're not sharing your location. You can still see $name."
            pausedUntil > now -> "Paused for ${((pausedUntil - now) / 60_000L) + 1} more min."
            Us.together(this) -> "Together with $name."
            else -> "$name can see where you are."
        }
        val builder = Notification.Builder(this, CHANNEL_SHARING)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(if (Us.sendingNow(this)) "Sharing your location with $name" else "Not sharing your location")
            .setContentText(text)
            .setContentIntent(openApp(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (Us.sendingNow(this)) {
            builder.addAction(Notification.Action.Builder(null, "Pause for 1 hour", action(ACTION_PAUSE)).build())
        } else if (Us.sharing(this)) {
            builder.addAction(Notification.Action.Builder(null, "Share again", action(ACTION_RESUME)).build())
        }
        return builder.build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getForegroundService(
        this,
        name.hashCode(),
        Intent(this, SharingService::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "SomewhereToMySomeone"
        private const val NOTE_ID = 1
        private const val ENDED_ID = 2
        private const val CHANNEL_SHARING = "sharing"
        private const val CHANNEL_NEWS = "news"

        const val ACTION_PAUSE = "dev.terraformist.someone.PAUSE"
        const val ACTION_RESUME = "dev.terraformist.someone.RESUME"
        const val PAUSE_MS = 60 * 60_000L

        /** Positions older than this aren't trusted for "together". */
        private const val FRESH_MS = 10 * 60_000L

        /** Within this, the Bluetooth check switches on. */
        private const val NEARBY_METERS = 500.0

        /** Set by the app while it's on screen, so updates come quickly. */
        @Volatile var appOpen = false

        @Volatile var running = false
            private set

        /** Start (or nudge) sharing. Quietly does nothing if Android won't allow it right now. */
        fun start(context: Context) {
            if (!Us.paired(context) || !Cloud.ready || !Where.hasLocation(context)) return
            try {
                context.startForegroundService(Intent(context, SharingService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start sharing", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SharingService::class.java))
        }

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SHARING, "Sharing your location", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while your location is shared with your someone"
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_NEWS, "Pairing", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When your someone ends the pairing"
                },
            )
        }

        fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, SomeoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
