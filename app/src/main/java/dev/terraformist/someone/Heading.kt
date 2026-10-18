package dev.terraformist.someone

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Which side of the phone you're looking at. */
enum class Facing { SCREEN, BACK }

/**
 * Which way "up" on the screen (or on the Glyph Matrix) is pointing, in degrees clockwise
 * from true north.
 *
 * Lying flat (face up or face down) that's the way the top of the phone points. Held upright,
 * it's the way you're facing: through the back if you're looking at the screen, through the
 * screen if you're looking at the Glyph Matrix on the back.
 */
class Heading(
    context: Context,
    private val facing: Facing,
    private val onChange: (Double) -> Unit,
) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val matrix = FloatArray(9)

    /** Magnetic north is a little off true north; this corrects for it where you are. */
    private var declination = 0.0

    // Smoothed as a direction (sine, cosine) so it doesn't jump when passing north.
    private var smoothSin = 0.0
    private var smoothCos = 1.0
    private var started = false

    /** Degrees clockwise from true north, or null until the first reading. */
    var degrees: Double? = null
        private set

    val available: Boolean get() = rotation != null

    /**
     * True when the phone says its compass is unsure (it needs waving in a figure of eight).
     * Magnets, laptops and steel nearby can upset it.
     */
    var needsCalibrating = false
        private set

    fun start() {
        if (started || rotation == null) return
        started = true
        sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!started) return
        started = false
        sensors.unregisterListener(this)
    }

    fun setLocation(location: Location) {
        declination = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis(),
        ).declination.toDouble()
    }

    override fun onSensorChanged(event: SensorEvent) {
        needsCalibrating = event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            event.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        // Columns of the matrix are the phone's axes in world terms (east, north, up).
        // Top of the phone (Y axis):
        val topEast = matrix[1].toDouble()
        val topNorth = matrix[4].toDouble()
        // Out of the screen (Z axis):
        val outEast = matrix[2].toDouble()
        val outNorth = matrix[5].toDouble()

        val flatness = sqrt(topEast * topEast + topNorth * topNorth)
        val (east, north) = if (flatness >= 0.5) {
            topEast to topNorth
        } else if (facing == Facing.SCREEN) {
            // Looking at the screen: you face out of the back.
            -outEast to -outNorth
        } else {
            // Looking at the back: you face out of the screen side.
            outEast to outNorth
        }
        val raw = Math.toRadians(Math.toDegrees(atan2(east, north)) + declination)

        if (degrees == null) {
            smoothSin = sin(raw)
            smoothCos = cos(raw)
        } else {
            smoothSin += (sin(raw) - smoothSin) * SMOOTHING
            smoothCos += (cos(raw) - smoothCos) * SMOOTHING
        }
        val value = (Math.toDegrees(atan2(smoothSin, smoothCos)) + 360.0) % 360.0
        degrees = value
        onChange(value)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val SMOOTHING = 0.15
    }
}
