package dev.terraformist.someone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app itself. Depending on where things stand it shows:
 *  - pairing: your code to show them, or a box to type theirs;
 *  - "what do you call them?" right after pairing;
 *  - the full-screen compass (tap the title for settings);
 *  - settings: share on/off, pause, rename, permissions, unpair.
 */
class SomeoneActivity : Activity() {

    private enum class Panel { SETUP, PAIR, NAME, COMPASS, SETTINGS }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var compass: CompassView
    private lateinit var directions: TextView
    private lateinit var titleTap: View
    private lateinit var setupPanel: View
    private lateinit var pairPanel: View
    private lateinit var namePanel: View
    private lateinit var settingsPanel: View

    private lateinit var heading: Heading
    private var watcher: LocationListener? = null

    private var panel: Panel? = null
    private var settingsOpen = false
    private var renaming = false
    private var resumed = false

    private val usChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> main.post { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_someone)
        compass = findViewById(R.id.compass)
        directions = findViewById(R.id.directions)
        titleTap = findViewById(R.id.title_tap)
        setupPanel = findViewById(R.id.setup_panel)
        pairPanel = findViewById(R.id.pair_panel)
        namePanel = findViewById(R.id.name_panel)
        settingsPanel = findViewById(R.id.settings_panel)

        useFonts(window.decorView)
        findViewById<DotTextView>(R.id.setup_headline).text = "NOT SET UP"
        findViewById<DotTextView>(R.id.pair_headline1).text = "PAIR WITH"
        findViewById<DotTextView>(R.id.pair_headline2).text = "YOUR SOMEONE"
        findViewById<DotTextView>(R.id.name_headline1).text = "WHAT DO YOU"
        findViewById<DotTextView>(R.id.name_headline2).text = "CALL THEM"
        findViewById<DotTextView>(R.id.code).pitch = 6.4f * resources.displayMetrics.density

        heading = Heading(this, Facing.SCREEN) {
            compass.heading = it
            compass.calibrate = heading.needsCalibrating
            compass.invalidate()
        }

        setUpCompass()
        setUpPairing()
        setUpName()
        setUpSettings()
    }

    /** Space Mono everywhere; bold on the buttons. */
    private fun useFonts(view: View) {
        if (view is TextView) {
            val bold = view.background != null && view.isClickable
            view.typeface = Art.typeface(this, bold)
        }
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) useFonts(view.getChildAt(i))
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        SharingService.appOpen = true
        Us.listen(this, usChanged)
        askForPermissions()
        SharingService.start(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        SharingService.appOpen = false
        Us.unlisten(this, usChanged)
        stopSensing()
        main.removeCallbacks(pairTick)
        main.removeCallbacks(settingsTick)
        // Tell sharing to slow down again, and leave the widget up to date.
        if (SharingService.running) SharingService.start(this)
        SomeoneWidget.refreshAll(this)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- Which screen ----------

    private fun refresh() {
        if (!resumed) return
        val next = when {
            !Cloud.ready -> Panel.SETUP
            !Us.paired(this) -> Panel.PAIR
            renaming || !Us.hasName(this) -> Panel.NAME
            settingsOpen -> Panel.SETTINGS
            else -> Panel.COMPASS
        }
        if (next != panel) {
            val previous = panel
            panel = next
            setupPanel.visibility = if (next == Panel.SETUP) View.VISIBLE else View.GONE
            pairPanel.visibility = if (next == Panel.PAIR) View.VISIBLE else View.GONE
            namePanel.visibility = if (next == Panel.NAME) View.VISIBLE else View.GONE
            settingsPanel.visibility = if (next == Panel.SETTINGS) View.VISIBLE else View.GONE
            val compassShown = next == Panel.COMPASS
            titleTap.visibility = if (compassShown) View.VISIBLE else View.GONE
            if (compassShown) startSensing() else stopSensing()
            if (next == Panel.PAIR) openPairing() else main.removeCallbacks(pairTick)
            if (next == Panel.NAME) openName()
            if (previous == Panel.PAIR && next != Panel.PAIR) SharingService.start(this)
            backHandling(next == Panel.SETTINGS || (next == Panel.NAME && renaming))
        }
        when (next) {
            Panel.COMPASS -> showCompass()
            Panel.SETTINGS -> showSettings()
            else -> directions.visibility = View.GONE
        }
    }

    // ---------- Back closes settings and renaming ----------

    private val back = OnBackInvokedCallback {
        when {
            renaming -> {
                renaming = false
                hideKeyboard()
            }
            settingsOpen -> settingsOpen = false
        }
        refresh()
    }
    private var backRegistered = false

    private fun backHandling(on: Boolean) {
        if (on && !backRegistered) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
            backRegistered = true
        } else if (!on && backRegistered) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back)
            backRegistered = false
        }
    }

    // ---------- Permissions ----------

    private var askedLocation = false
    private var askedOthers = false

    private fun askForPermissions() {
        if (!Where.hasLocation(this)) {
            if (!askedLocation) {
                askedLocation = true
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    ASK_LOCATION,
                )
            }
            return
        }
        val missing = (Near.PERMISSIONS + Manifest.permission.POST_NOTIFICATIONS).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty() && !askedOthers) {
            askedOthers = true
            requestPermissions(missing.toTypedArray(), ASK_OTHERS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ASK_LOCATION) askForPermissions()
        // Sharing may need restarting with the new permissions.
        SharingService.stop(this)
        SharingService.start(this)
        panel = null
        refresh()
    }

    // ---------- The compass ----------

    private fun setUpCompass() {
        // Keep the invisible tap area sitting exactly on the drawn title, whatever the screen size.
        compass.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val minHeight = 48f * resources.displayMetrics.density
            val width = (compass.titleWidth() + 32f * resources.displayMetrics.density).toInt()
            val params = titleTap.layoutParams as FrameLayout.LayoutParams
            val top = (compass.titleCenterY() - minHeight / 2f).toInt().coerceAtLeast(0)
            if (params.width != width || params.topMargin != top) {
                params.width = width
                params.height = minHeight.toInt()
                params.topMargin = top
                titleTap.post { titleTap.layoutParams = params }
            }
        }
        titleTap.setOnClickListener {
            settingsOpen = true
            refresh()
        }
        directions.setOnClickListener { onDirections() }
    }

    private fun startSensing() {
        if (watcher == null && Where.hasLocation(this)) {
            watcher = Where.watch(this, everyMs = 2_000L) { moved(it) }
            Where.refresh(this) { fresh -> if (fresh != null) moved(fresh) }
        }
        heading.start()
    }

    private fun stopSensing() {
        Where.stop(this, watcher)
        watcher = null
        heading.stop()
    }

    private fun moved(location: Location) {
        heading.setLocation(location)
        if (panel == Panel.COMPASS) showCompass()
    }

    private fun showCompass() {
        val snap = Snapshot.of(this)
        compass.update(snap)
        when {
            snap.scene == Scene.NO_LOCATION -> {
                directions.text = "ALLOW LOCATION"
                directions.visibility = View.VISIBLE
            }
            snap.scene == Scene.APART && snap.reading != null -> {
                directions.text = if (snap.reading.meters < WALKING_METERS) "WALKING DIRECTIONS" else "DIRECTIONS"
                directions.visibility = View.VISIBLE
            }
            else -> directions.visibility = View.GONE
        }
    }

    private fun onDirections() {
        if (!Where.hasLocation(this)) {
            // They said no before, so Android won't ask again: open this app's settings instead.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        val them = Us.them(this) ?: return
        val reading = Us.reading(this)
        var url = String.format(
            Locale.US,
            "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f",
            them.latitude,
            them.longitude,
        )
        if (reading != null && reading.meters < WALKING_METERS) url += "&travelmode=walking"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: android.content.ActivityNotFoundException) {
            // No maps app or browser: nothing sensible to do.
        }
    }

    // ---------- Pairing ----------

    private lateinit var codeView: DotTextView
    private lateinit var codeExpires: TextView
    private lateinit var pairStatus: TextView
    private lateinit var enterRow: View
    private lateinit var codeInput: EditText
    private var makingCode = false
    private var joining = false
    private var checking = false

    private fun setUpPairing() {
        codeView = findViewById(R.id.code)
        codeExpires = findViewById(R.id.code_expires)
        pairStatus = findViewById(R.id.pair_status)
        enterRow = findViewById(R.id.enter_row)
        codeInput = findViewById(R.id.code_input)

        findViewById<View>(R.id.share_code).setOnClickListener { shareCode() }
        findViewById<View>(R.id.enter_code).setOnClickListener {
            enterRow.visibility = View.VISIBLE
            codeInput.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(codeInput, 0)
        }
        findViewById<View>(R.id.join).setOnClickListener { join() }
        codeInput.setOnEditorActionListener { _, _, _ ->
            join()
            true
        }
    }

    private fun openPairing() {
        pairStatus.text = ""
        main.removeCallbacks(pairTick)
        main.post(pairTick)
    }

    /** Once a second: count the code down, and every few seconds check if they've typed it. */
    private val pairTick = object : Runnable {
        private var count = 0
        override fun run() {
            if (panel != Panel.PAIR) return
            val pending = Us.pending(this@SomeoneActivity)
            val now = System.currentTimeMillis()
            if (pending == null || pending.expiresAt <= now) {
                showCode(null)
                makeCode()
            } else {
                showCode(pending)
                if (count++ % 3 == 0) checkCode()
            }
            main.postDelayed(this, 1_000L)
        }
    }

    private fun showCode(pending: Us.Pending?) {
        if (pending == null) {
            codeView.text = "--- ---"
            codeExpires.text = if (makingCode) "MAKING A CODE" else ""
            return
        }
        codeView.text = pending.code.substring(0, 3) + " " + pending.code.substring(3)
        val left = ((pending.expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        codeExpires.text = String.format(Locale.US, "EXPIRES IN %d:%02d", left / 60, left % 60)
        codeView.contentDescription = "Your code: " + pending.code.toCharArray().joinToString(" ")
    }

    private fun makeCode() {
        if (makingCode) return
        makingCode = true
        worker.execute {
            val error = try {
                Pairing.makeCode(this)
                null
            } catch (e: Pairing.PairingException) {
                e.message
            } catch (e: Exception) {
                "NO CONNECTION. TRYING AGAIN."
            }
            main.post {
                makingCode = false
                if (error != null) {
                    pairStatus.text = error
                    // Wait a little before the next go.
                    main.removeCallbacks(pairTick)
                    main.postDelayed(pairTick, 5_000L)
                } else if (pairStatus.text.startsWith("NO CONNECTION")) {
                    pairStatus.text = ""
                }
            }
        }
    }

    private fun checkCode() {
        if (checking || makingCode) return
        checking = true
        worker.execute {
            val done = try {
                Pairing.checkCode(this)
            } catch (e: Exception) {
                false
            }
            main.post {
                checking = false
                if (done) refresh()
            }
        }
    }

    private fun shareCode() {
        val pending = Us.pending(this) ?: return
        val spaced = pending.code.substring(0, 3) + " " + pending.code.substring(3)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Pair with me on Somewhere to my Someone. My code is $spaced (it works for 10 minutes).")
        startActivity(Intent.createChooser(send, "Share your code"))
    }

    private fun join() {
        if (joining) return
        val typed = codeInput.text.toString()
        joining = true
        pairStatus.text = "PAIRING…"
        hideKeyboard()
        worker.execute {
            val error = try {
                Pairing.join(this, typed)
                null
            } catch (e: Pairing.PairingException) {
                e.message
            } catch (e: Exception) {
                "NO CONNECTION. TRY AGAIN."
            }
            main.post {
                joining = false
                pairStatus.text = error ?: ""
                if (error == null) {
                    codeInput.setText("")
                    enterRow.visibility = View.GONE
                    refresh()
                }
            }
        }
    }

    // ---------- What do you call them? ----------

    private lateinit var nameInput: EditText

    private fun setUpName() {
        nameInput = findViewById(R.id.name_input)
        findViewById<View>(R.id.save_name).setOnClickListener { saveName() }
        nameInput.setOnEditorActionListener { _, _, _ ->
            saveName()
            true
        }
    }

    private fun openName() {
        nameInput.setText(if (Us.hasName(this)) Us.name(this) else "")
        nameInput.setSelection(nameInput.text.length)
    }

    private fun saveName() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            nameInput.error = "Type a name"
            return
        }
        hideKeyboard()
        renaming = false
        Us.setName(this, name)
        SomeoneWidget.refreshAll(this)
        refresh()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    // ---------- Settings ----------

    private fun setUpSettings() {
        findViewById<View>(R.id.share_toggle).setOnClickListener {
            Us.setSharing(this, !Us.sharing(this))
            SharingService.start(this)
            refresh()
        }
        findViewById<View>(R.id.pause_button).setOnClickListener {
            if (Us.pausedUntil(this) > System.currentTimeMillis()) Us.setSharing(this, true) else Us.pauseFor(this, SharingService.PAUSE_MS)
            SharingService.start(this)
            refresh()
        }
        findViewById<View>(R.id.rename_button).setOnClickListener {
            renaming = true
            refresh()
        }
        findViewById<View>(R.id.bluetooth_button).setOnClickListener {
            // Ask once; if Android won't show the question any more, open the app's settings.
            val missing = Near.PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.any { shouldShowRequestPermissionRationale(it) } || !askedOthersEver) {
                askedOthersEver = true
                requestPermissions(missing.toTypedArray(), ASK_OTHERS)
            } else {
                openAppSettings()
            }
        }
        findViewById<View>(R.id.battery_button).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                openAppSettings()
            }
        }
        findViewById<View>(R.id.restart_button).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), ASK_BACKGROUND)
        }
        findViewById<View>(R.id.unpair).setOnClickListener { confirmUnpair() }
        findViewById<View>(R.id.settings_back).setOnClickListener {
            settingsOpen = false
            refresh()
        }
    }

    private var askedOthersEver = false

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun showSettings() {
        val name = Us.name(this)
        val now = System.currentTimeMillis()
        findViewById<TextView>(R.id.settings_name).text = name
        val since = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.UK).format(Date(Us.pairedAt(this))).uppercase()
        findViewById<TextView>(R.id.settings_since).text = "SINCE $since"

        val sharing = Us.sharing(this)
        val pausedUntil = Us.pausedUntil(this)
        val paused = pausedUntil > now
        findViewById<TextView>(R.id.share_help).text = if (sharing) "$name can see where you are" else "$name can't see where you are"
        pill(findViewById(R.id.share_toggle), if (sharing) "ON" else "OFF", filled = sharing)

        findViewById<View>(R.id.row_pause).visibility = if (sharing) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.pause_help).text = if (paused) {
            "Paused for ${((pausedUntil - now) / 60_000L) + 1} more min. $name can see that"
        } else {
            "$name will see that you've paused"
        }
        pill(findViewById(R.id.pause_button), if (paused) "RESUME" else "1 HOUR", filled = false)

        findViewById<View>(R.id.row_bluetooth).visibility = if (Near.permitted(this)) View.GONE else View.VISIBLE
        val power = getSystemService(PowerManager::class.java)
        findViewById<View>(R.id.row_battery).visibility =
            if (power?.isIgnoringBatteryOptimizations(packageName) == true) View.GONE else View.VISIBLE
        findViewById<View>(R.id.row_restart).visibility = if (Where.hasBackground(this)) View.GONE else View.VISIBLE

        val them = Us.them(this)
        val status = when {
            Us.theyPaused(this) -> "$name HAS PAUSED SHARING"
            them == null -> "WAITING TO HEAR FROM $name"
            else -> "$name LAST SHARED ${Geo.ago(them.fixAt)}"
        }
        findViewById<TextView>(R.id.their_status).text = status + "\n\n" + diagnostics()

        // Keep the numbers live while settings are open.
        main.removeCallbacks(settingsTick)
        main.postDelayed(settingsTick, 2_000L)
    }

    private val settingsTick = Runnable { if (panel == Panel.SETTINGS && resumed) showSettings() }

    /** The raw numbers, so the together check can be tuned from real readings. */
    private fun diagnostics(): String {
        val now = System.currentTimeMillis()
        val signal = SharingService.signal
        val heard = when {
            !Near.permitted(this) -> "BLUETOOTH: NOT ALLOWED"
            signal == null || now - SharingService.signalAt > 60_000L -> "BLUETOOTH: NOT HEARD"
            else -> "BLUETOOTH: $signal DBM, ${(now - SharingService.signalAt) / 1000} S AGO"
        }
        val me = Where.last(this)
        val them = Us.them(this)
        val mine = me?.let { "±${it.accuracy.toInt()} M" } ?: "?"
        val theirs = them?.let { "±${it.accuracy.toInt()} M" } ?: "?"
        val reading = Us.reading(this)
        val gps = reading?.let { "${it.meters.toInt()} M APART" } ?: "?"
        return "$heard\nGPS: YOU $mine · THEM $theirs · $gps"
    }

    private fun pill(view: TextView, text: String, filled: Boolean) {
        view.text = text
        view.setBackgroundResource(if (filled) R.drawable.button_pill else R.drawable.button_outline)
        view.setTextColor(if (filled) Art.BLACK else Art.WHITE)
        view.typeface = Art.typeface(this, true)
    }

    private fun confirmUnpair() {
        val name = Us.name(this).lowercase().replaceFirstChar { it.uppercase() }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Unpair from $name?")
            .setMessage("Sharing stops for both of you straight away. To share again, you'll need to pair again with a new code.")
            .setPositiveButton("Unpair") { _, _ -> unpair() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun unpair() {
        worker.execute {
            val ok = try {
                Pairing.unpair(this)
                true
            } catch (e: Exception) {
                false
            }
            main.post {
                if (ok) {
                    SharingService.stop(this)
                    settingsOpen = false
                    SomeoneWidget.refreshAll(this)
                    refresh()
                } else {
                    Toast.makeText(this, "Couldn't reach the internet. Try again.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private companion object {
        const val ASK_LOCATION = 1
        const val ASK_OTHERS = 2
        const val ASK_BACKGROUND = 3

        /** Closer than this, directions are for walking. */
        const val WALKING_METERS = 5_000.0
    }
}
