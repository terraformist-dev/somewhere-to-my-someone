package dev.terraformist.someone

import android.content.Context
import android.content.SharedPreferences

/** Their last shared position, as unscrambled on this phone. */
data class Them(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    /** When their phone took that position. */
    val fixAt: Long,
)

/**
 * Everything this phone remembers about the pairing: who it's paired with, the shared secret,
 * whether it's sharing, the other phone's last position, and whether you're together.
 * The app, widget, Glyph Toy and the background service all read and write it here, and
 * can listen for changes.
 */
object Us {

    private const val PREFS = "us"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- The pairing ----------

    fun paired(context: Context): Boolean = prefs(context).contains("pair")

    fun pairId(context: Context): String? = prefs(context).getString("pair", null)

    /** "a" for the phone that showed the code, "b" for the one that typed it. */
    fun role(context: Context): String = prefs(context).getString("role", "a") ?: "a"

    fun otherRole(context: Context): String = if (role(context) == "a") "b" else "a"

    fun secret(context: Context): ByteArray? = prefs(context).getString("secret", null)?.let { Crypto.unb64(it) }

    fun savePair(context: Context, pairId: String, role: String, secret: ByteArray) {
        prefs(context).edit()
            .putString("pair", pairId)
            .putString("role", role)
            .putString("secret", Crypto.b64(secret))
            .putLong("paired_at", System.currentTimeMillis())
            .putBoolean("sharing", true)
            .remove("paused_until")
            .apply()
        clearPending(context)
    }

    /** Forget the pairing and everything about the other phone. Keeps nothing behind. */
    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** What you call them, in capitals for the drawn labels. */
    fun name(context: Context): String = prefs(context).getString("name", null)?.takeIf { it.isNotBlank() } ?: "YOUR SOMEONE"

    fun hasName(context: Context): Boolean = !prefs(context).getString("name", null).isNullOrBlank()

    fun setName(context: Context, name: String) {
        prefs(context).edit().putString("name", name.trim().uppercase().take(20)).apply()
    }

    fun pairedAt(context: Context): Long = prefs(context).getLong("paired_at", 0L)

    // ---------- A code waiting to be typed in on the other phone ----------

    data class Pending(val code: String, val pairId: String, val privateKey: String, val expiresAt: Long)

    fun pending(context: Context): Pending? {
        val p = prefs(context)
        val code = p.getString("pending_code", null) ?: return null
        return Pending(
            code,
            p.getString("pending_pair", "") ?: "",
            p.getString("pending_key", "") ?: "",
            p.getLong("pending_expires", 0L),
        )
    }

    fun savePending(context: Context, pending: Pending) {
        prefs(context).edit()
            .putString("pending_code", pending.code)
            .putString("pending_pair", pending.pairId)
            .putString("pending_key", pending.privateKey)
            .putLong("pending_expires", pending.expiresAt)
            .apply()
    }

    fun clearPending(context: Context) {
        prefs(context).edit()
            .remove("pending_code").remove("pending_pair").remove("pending_key").remove("pending_expires")
            .apply()
    }

    // ---------- Sharing my location ----------

    /** Off means "Share my location" is switched off. */
    fun sharing(context: Context): Boolean = prefs(context).getBoolean("sharing", true)

    fun setSharing(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("sharing", on).remove("paused_until").apply()
    }

    fun pausedUntil(context: Context): Long = prefs(context).getLong("paused_until", 0L)

    fun pauseFor(context: Context, ms: Long) {
        prefs(context).edit().putLong("paused_until", System.currentTimeMillis() + ms).apply()
    }

    /** True while this phone is sending its position. */
    fun sendingNow(context: Context): Boolean =
        sharing(context) && System.currentTimeMillis() >= pausedUntil(context)

    // ---------- Them ----------

    fun them(context: Context): Them? {
        val p = prefs(context)
        if (!p.contains("their_lat")) return null
        return Them(
            Double.fromBits(p.getLong("their_lat", 0)),
            Double.fromBits(p.getLong("their_lon", 0)),
            p.getFloat("their_acc", 50f),
            p.getLong("their_fix", 0L),
        )
    }

    fun saveThem(context: Context, them: Them) {
        prefs(context).edit()
            .putLong("their_lat", them.latitude.toBits())
            .putLong("their_lon", them.longitude.toBits())
            .putFloat("their_acc", them.accuracy)
            .putLong("their_fix", them.fixAt)
            .apply()
    }

    /** Whether they've paused or switched off sharing. Their old position is kept but not shown. */
    fun theyPaused(context: Context): Boolean = prefs(context).getBoolean("their_paused", false)

    fun setTheyPaused(context: Context, paused: Boolean) {
        if (theyPaused(context) != paused) prefs(context).edit().putBoolean("their_paused", paused).apply()
    }

    /** Whether their phone can do the Bluetooth check (if not, "together" falls back on GPS). */
    fun theyHaveBluetooth(context: Context): Boolean = prefs(context).getBoolean("their_bt", true)

    fun setTheyHaveBluetooth(context: Context, on: Boolean) {
        if (theyHaveBluetooth(context) != on) prefs(context).edit().putBoolean("their_bt", on).apply()
    }

    /** How far and which way they are from here, or null if either position is unknown. */
    fun reading(context: Context): Reading? {
        val me = Where.last(context) ?: return null
        val them = them(context) ?: return null
        return Geo.reading(me.latitude, me.longitude, them.latitude, them.longitude)
    }

    // ---------- Together ----------

    fun together(context: Context): Boolean = prefs(context).getBoolean("together", false)

    /** When you last came together or parted, for the animations. */
    fun changedAt(context: Context): Long = prefs(context).getLong("changed_at", 0L)

    fun togetherSince(context: Context): Long = if (together(context)) changedAt(context) else 0L

    fun setTogether(context: Context, together: Boolean) {
        if (together(context) == together) return
        prefs(context).edit()
            .putBoolean("together", together)
            .putLong("changed_at", System.currentTimeMillis())
            .apply()
    }

    // ---------- Listening ----------

    fun listen(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unlisten(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
