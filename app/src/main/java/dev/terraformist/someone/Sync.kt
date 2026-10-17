package dev.terraformist.someone

import android.content.Context
import android.location.Location
import org.json.JSONObject

/**
 * Sending this phone's position and fetching the other one's. Positions are locked with the
 * shared secret before they leave the phone; when sharing is paused or off, only "paused" is
 * sent, never a position. Blocks on the network: call it off the main thread.
 */
object Sync {

    enum class Result { OK, ENDED, NOT_PAIRED }

    /** Send where this phone is (or that it's paused). */
    fun send(context: Context, location: Location?, together: Boolean): Result {
        val pairId = Us.pairId(context) ?: return Result.NOT_PAIRED
        val secret = Us.secret(context) ?: return Result.NOT_PAIRED
        val uid = Cloud.uid(context)

        val sending = Us.sendingNow(context)
        // Sharing, but no position yet: send nothing rather than looking paused.
        if (sending && location == null) return Result.OK

        val message = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("tg", if (together) 1 else 0)
            .put("bt", if (Near.permitted(context)) 1 else 0)
        if (sending && location != null) {
            message.put("la", location.latitude)
                .put("lo", location.longitude)
                .put("ac", if (location.hasAccuracy()) location.accuracy.toDouble() else 50.0)
        } else {
            message.put("p", 1)
        }
        val entry = JSONObject().put("c", Crypto.lock(secret, message.toString())).put("t", Cloud.serverTime())
        return try {
            Cloud.put(context, "pairs/$pairId/loc/$uid", entry)
            Result.OK
        } catch (e: Cloud.CloudException) {
            // Refused: the pair has gone, so they must have unpaired.
            if ((e.code == 401 || e.code == 403) && Pairing.endedByThem(context)) Result.ENDED else throw e
        }
    }

    /** Fetch the other phone's latest message and remember it. */
    fun fetch(context: Context): Result {
        val pairId = Us.pairId(context) ?: return Result.NOT_PAIRED
        val secret = Us.secret(context) ?: return Result.NOT_PAIRED
        val pair = try {
            Cloud.get(context, "pairs/$pairId") as? JSONObject
        } catch (e: Cloud.CloudException) {
            if (e.code == 401 || e.code == 403) null else throw e
        } ?: return Result.ENDED

        val theirUid = pair.optString(Us.otherRole(context), "")
        val locked = pair.optJSONObject("loc")?.optJSONObject(theirUid)?.optString("c", "") ?: ""
        if (locked.isEmpty()) return Result.OK
        val message = Crypto.unlock(secret, locked)?.let { JSONObject(it) } ?: return Result.OK

        Us.setTheyHaveBluetooth(context, message.optInt("bt", 1) == 1)
        if (message.optInt("p", 0) == 1 || !message.has("la")) {
            Us.setTheyPaused(context, true)
        } else {
            Us.saveThem(
                context,
                Them(
                    latitude = message.getDouble("la"),
                    longitude = message.getDouble("lo"),
                    accuracy = message.optDouble("ac", 50.0).toFloat(),
                    fixAt = message.optLong("t", System.currentTimeMillis()),
                ),
            )
            Us.setTheyPaused(context, false)
        }
        return Result.OK
    }
}
