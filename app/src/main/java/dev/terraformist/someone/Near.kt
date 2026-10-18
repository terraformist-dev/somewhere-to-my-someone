package dev.terraformist.someone

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log

/**
 * The Bluetooth "are we right next to each other?" check.
 *
 * Each phone sends out a tiny signal with an eight-byte token that only its partner can
 * recognise (made from the shared secret, changing every 15 minutes), and listens for the
 * partner's. How strong the partner's signal is tells us roughly how close they are.
 * [onSighting] is called with the signal strength (RSSI, dBm) each time the partner is heard.
 */
class Near(private val context: Context, private val onSighting: (Int) -> Unit) {

    enum class Speed { OFF, SLOW, NORMAL, FAST }

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var advertisingAt: Speed = Speed.OFF
    private var advertisingWindow = -1L
    private var scanningAt: Speed = Speed.OFF

    /** Turn sending and listening on, off, or to a different speed. Safe to call often. */
    fun set(advertise: Speed, scan: Speed) {
        if (!permitted(context) || adapter?.isEnabled != true) {
            stop()
            return
        }
        val window = System.currentTimeMillis() / Crypto.BLE_WINDOW_MS
        if (advertise != advertisingAt || (advertise != Speed.OFF && window != advertisingWindow)) {
            stopAdvertising()
            if (advertise != Speed.OFF) startAdvertising(advertise, window)
        }
        if (scan != scanningAt) {
            stopScanning()
            if (scan != Speed.OFF) startScanning(scan)
        }
    }

    fun stop() {
        stopAdvertising()
        stopScanning()
    }

    // ---------- Sending ----------

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Bluetooth advertising failed: $errorCode")
            advertisingAt = Speed.OFF
        }
    }

    private fun startAdvertising(speed: Speed, window: Long) {
        val secret = Us.secret(context) ?: return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                when (speed) {
                    Speed.FAST -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                    Speed.NORMAL -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
                    else -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                },
            )
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(SERVICE, Crypto.bleToken(secret, Us.role(context), window))
            .build()
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
            advertisingAt = speed
            advertisingWindow = window
        } catch (e: SecurityException) {
            advertisingAt = Speed.OFF
        }
    }

    private fun stopAdvertising() {
        if (advertisingAt == Speed.OFF) return
        advertisingAt = Speed.OFF
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: SecurityException) {
            // Permission was taken away; it's stopped anyway.
        }
    }

    // ---------- Listening ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Bluetooth scan failed: $errorCode")
            scanningAt = Speed.OFF
        }
    }

    private fun handle(result: ScanResult) {
        val token = result.scanRecord?.getServiceData(SERVICE) ?: return
        val secret = Us.secret(context) ?: return
        val theirs = Us.otherRole(context)
        val window = System.currentTimeMillis() / Crypto.BLE_WINDOW_MS
        // Allow for the two phones' clocks being a little apart.
        for (w in (window - 1)..(window + 1)) {
            if (Crypto.bleToken(secret, theirs, w).contentEquals(token)) {
                onSighting(result.rssi)
                return
            }
        }
    }

    private fun startScanning(speed: Speed) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceData(SERVICE, ByteArray(0)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(
                when (speed) {
                    Speed.FAST -> ScanSettings.SCAN_MODE_LOW_LATENCY
                    Speed.NORMAL -> ScanSettings.SCAN_MODE_BALANCED
                    else -> ScanSettings.SCAN_MODE_LOW_POWER
                },
            )
            .setReportDelay(0L)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanningAt = speed
        } catch (e: SecurityException) {
            scanningAt = Speed.OFF
        }
    }

    private fun stopScanning() {
        if (scanningAt == Speed.OFF) return
        scanningAt = Speed.OFF
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // Permission was taken away; it's stopped anyway.
        }
    }

    companion object {
        private const val TAG = "SomewhereToMySomeone"

        /** This app's own Bluetooth label. Made up for this app; nothing else uses it. */
        val SERVICE: ParcelUuid = ParcelUuid.fromString("5d0e7a3c-8f21-4b6e-9c47-2a1f0d3e6b58")

        val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)

        fun permitted(context: Context): Boolean = PERMISSIONS.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

/**
 * Deciding "together" and "apart" from the Bluetooth sightings. Uses the middle value of the
 * last few seconds' readings (one odd reading, like a hand over the phone, doesn't count).
 *  - together: a strong signal (about arm's length) for a couple of readings, or one very
 *    strong one;
 *  - apart: the signal has been weak (another room) for [PART_MS], or not heard at all
 *    for [LOST_MS].
 * Without Bluetooth it falls back on GPS, which is much rougher. No Android code, so it's easy to check.
 */
class TogetherJudge {

    private val readings = ArrayDeque<Pair<Long, Int>>()
    private var weakSince = 0L
    private var lastHeard = 0L

    /** The latest middle value, for the diagnostics line. */
    var signal: Int? = null
        private set
    var signalAt = 0L
        private set

    fun sighting(rssi: Int, now: Long) {
        readings.addLast(now to rssi)
        while (readings.isNotEmpty() && now - readings.first().first > WINDOW_MS) readings.removeFirst()
        lastHeard = now
        val m = median(now, WINDOW_MS) ?: return
        signal = m
        signalAt = now
        if (m < PART_DBM) {
            if (weakSince == 0L) weakSince = now
        } else {
            weakSince = 0L
        }
    }

    private fun median(now: Long, within: Long): Int? {
        val recent = readings.filter { now - it.first <= within }.map { it.second }.sorted()
        if (recent.isEmpty()) return null
        return recent[recent.size / 2]
    }

    /**
     * @param together what we think now.
     * @param bluetooth whether Bluetooth is working on both phones.
     * @param gpsMeters distance between the two last known positions, if both are fresh.
     */
    fun decide(now: Long, together: Boolean, bluetooth: Boolean, gpsMeters: Double?): Boolean {
        if (bluetooth) {
            return if (!together) {
                val recent = readings.filter { now - it.first <= JOIN_WINDOW_MS }
                val m = median(now, JOIN_WINDOW_MS)
                (m != null && m >= JOIN_DBM) || recent.any { it.second >= SURE_DBM }
            } else {
                val lost = lastHeard == 0L || now - lastHeard > LOST_MS
                val weak = weakSince != 0L && now - weakSince > PART_MS
                !(lost || weak)
            }
        }
        if (gpsMeters == null) return together
        return if (together) gpsMeters < GPS_PART_METERS else gpsMeters <= GPS_JOIN_METERS
    }

    /** Starting up while already together: give the signal time to turn up before parting. */
    fun assumeNear(now: Long) {
        lastHeard = now
    }

    /** Forget the signal history (after parting, or on unpairing). */
    fun reset() {
        readings.clear()
        weakSince = 0L
    }

    companion object {
        /** About one or two metres, phone to phone (median of the last few seconds). */
        const val JOIN_DBM = -70

        /** Right next to each other: one reading is enough. */
        const val SURE_DBM = -60

        /** Weaker than this is another room. */
        const val PART_DBM = -82

        const val WINDOW_MS = 10_000L
        const val JOIN_WINDOW_MS = 10_000L
        const val PART_MS = 15_000L
        const val LOST_MS = 30_000L

        const val GPS_JOIN_METERS = 15.0
        const val GPS_PART_METERS = 40.0
    }
}
