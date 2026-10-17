package dev.terraformist.someone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Where this phone is. The last known position is saved, so the widget and Glyph Toy
 * still have something to show when they can't get a fresh one.
 */
object Where {

    private const val PREFS = "where"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_ACC = "acc"
    private const val KEY_AT = "at"

    private val worker = Executors.newSingleThreadExecutor()

    fun hasLocation(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackground(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** The last place we know this phone was, or null if never. */
    fun last(context: Context): Location? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAT)) return null
        return Location("saved").apply {
            latitude = Double.fromBits(prefs.getLong(KEY_LAT, 0))
            longitude = Double.fromBits(prefs.getLong(KEY_LON, 0))
            accuracy = prefs.getFloat(KEY_ACC, 50f)
            time = prefs.getLong(KEY_AT, 0)
        }
    }

    fun save(context: Context, location: Location) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAT, location.latitude.toBits())
            .putLong(KEY_LON, location.longitude.toBits())
            .putFloat(KEY_ACC, if (location.hasAccuracy()) location.accuracy else 50f)
            .putLong(KEY_AT, location.time.takeIf { it > 0 } ?: System.currentTimeMillis())
            .apply()
    }

    private fun manager(context: Context) = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun bestProvider(manager: LocationManager): String? {
        val providers = manager.getProviders(true)
        return when {
            LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            else -> providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER }
        }
    }

    /**
     * Try for a fresh position, save it, then call [done] on the main thread (with the
     * fresh position, or null if there wasn't one). Works without permission: just returns null.
     */
    fun refresh(context: Context, done: (Location?) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val manager = manager(app)
        if (manager == null || !hasLocation(app)) {
            done(null)
            return
        }
        try {
            // A recent fix from any app is good enough and costs nothing.
            val recent = manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
            if (recent != null && System.currentTimeMillis() - recent.time < 30_000L) {
                save(app, recent)
                done(recent)
                return
            }
            val provider = bestProvider(manager)
            if (provider == null) {
                recent?.let { save(app, it) }
                done(recent)
                return
            }
            manager.getCurrentLocation(provider, null, worker) { fresh ->
                val found = fresh ?: recent
                found?.let { save(app, it) }
                main.post { done(found) }
            }
        } catch (e: SecurityException) {
            done(null)
        }
    }

    /**
     * Follow the phone, roughly every [everyMs]. Each new position is saved and passed to
     * [onMove] on the main thread. Call [stop] with the returned listener.
     */
    fun watch(context: Context, everyMs: Long, onMove: (Location) -> Unit): LocationListener? {
        val app = context.applicationContext
        val manager = manager(app) ?: return null
        if (!hasLocation(app)) return null
        val provider = bestProvider(manager) ?: return null
        val listener = LocationListener { location ->
            save(app, location)
            onMove(location)
        }
        val request = LocationRequest.Builder(everyMs)
            .setMinUpdateIntervalMillis(everyMs / 2)
            .setQuality(
                if (everyMs <= 60_000L) LocationRequest.QUALITY_HIGH_ACCURACY
                else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY,
            )
            .build()
        return try {
            manager.requestLocationUpdates(provider, request, app.mainExecutor, listener)
            listener
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun stop(context: Context, listener: LocationListener?) {
        if (listener == null) return
        manager(context.applicationContext)?.removeUpdates(listener)
    }
}
