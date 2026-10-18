package dev.terraformist.someone

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** A distance split into the dotted number and its unit: "0.8" + "KM", "340" + "M", "5,570" + "KM". */
data class Distance(val number: String, val unit: String)

/** How far away your someone is and which way, from where you are. No Android code, so it's easy to check. */
data class Reading(
    val meters: Double,
    /** Compass direction to them, in degrees clockwise from true north. */
    val bearing: Double,
    /** How far off the two positions could be, together, in metres. */
    val spread: Double = 0.0,
) {
    /**
     * Closer than the positions can tell apart: the distance and direction would just be noise
     * (indoors, both phones often get the same Wi-Fi position). Shown as "NEAR", with no arrow.
     */
    val near: Boolean get() = meters < maxOf(spread, NEAR_MIN_METERS)

    /** The rough radius to show with "NEAR", rounded to 10 m. */
    val nearWithin: Int get() = (Math.ceil(maxOf(spread, NEAR_MIN_METERS) / 10.0) * 10).toInt()

    val far: Boolean get() = meters > FAR_METERS
    val close: Boolean get() = meters <= CLOSE_METERS
    val distance: Distance get() = Geo.format(meters)

    /** "38° NE" */
    val bearingLabel: String get() = "${bearing.roundToInt() % 360}° ${Geo.cardinal(bearing)}"

    companion object {
        /** Beyond this, the compass direction stops mattering much. */
        const val FAR_METERS = 100_000.0

        /** "NEAR" never means less than this. */
        const val NEAR_MIN_METERS = 20.0

        /** Within this, the Glyph Matrix draws a needle from the centre. */
        const val CLOSE_METERS = 2_000.0
    }
}

object Geo {

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun reading(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, spread: Double = 0.0): Reading = Reading(
        meters = distanceMeters(fromLat, fromLon, toLat, toLon),
        bearing = bearingDegrees(fromLat, fromLon, toLat, toLon),
        spread = spread,
    )

    /** Great-circle distance in metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Starting direction of the shortest path, degrees clockwise from true north (0..360). */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Metres under 1 km (to the nearest 10), one decimal under 10 km, whole kilometres with commas after that. */
    fun format(meters: Double): Distance = when {
        meters < 995 -> Distance((((meters / 10).roundToInt()) * 10).toString(), "M")
        meters < 9_950 -> Distance(String.format(Locale.US, "%.1f", meters / 1000), "KM")
        else -> Distance(NumberFormat.getIntegerInstance(Locale.US).format((meters / 1000).roundToLong()), "KM")
    }

    fun cardinal(degrees: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((degrees % 360) + 360) % 360 / 45).roundToInt() % 8]
    }

    /** "JUST NOW", "1 MIN AGO", "12 MIN AGO", "3 HR AGO", "2 DAYS AGO". */
    fun ago(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val minutes = ((nowMs - thenMs).coerceAtLeast(0L) / 60_000L)
        return when {
            minutes < 1 -> "JUST NOW"
            minutes < 60 -> "$minutes MIN AGO"
            minutes < 48 * 60 -> "${minutes / 60} HR AGO"
            else -> "${minutes / (24 * 60)} DAYS AGO"
        }
    }
}
