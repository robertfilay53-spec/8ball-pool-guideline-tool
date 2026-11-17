package com.poolguide.tool.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.poolguide.tool.BuildConfig
import com.poolguide.tool.R
import com.poolguide.tool.capture.CaptureService
import com.poolguide.tool.capture.PreviewBus
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.data.DiagnosticsLog
import com.poolguide.tool.data.ShotHistory
import com.poolguide.tool.overlay.OverlayService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main screen: permission flow, start/stop, demo mode, calibration, and the
 * FLAG_SECURE diagnostic preview.
 *
 * Permission order (ChatGPT checklist):
 * 1. Overlay permission (SYSTEM_ALERT_WINDOW) — explicit user enablement
 *    on Android 14+ via Settings.ACTION_MANAGE_OVERLAY_PERMISSION.
 * 2. POST_NOTIFICATIONS at runtime (Android 13+).
 * 3. MediaProjection consent in the foreground Activity; the token is
 *    passed to the service IMMEDIATELY in onActivityResult — never held
 *    in the Activity.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var store: CalibrationStore
    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var previewStatus: TextView
    private lateinit var shotHistoryText: TextView
    private lateinit var previewSwitch: Switch
    private lateinit var showAllShotsSwitch: Switch

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onOverlayResult() }

    /** v1.0.71: returns from the battery-optimisation exemption
     * settings screen; continue the start flow either way (the
     * exemption is best-effort, capture still works without it). */
    private val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkNotificationsThenStart() }

    private val notificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onNotificationsResult() }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Pass the token to the capture service immediately — never
            // hold it. The overlay runs in its own service (FGS split):
            // start it alongside, it draws the lines from OverlayBus.
            val intent = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            }
            // v1.0.31: starting a foreground service can throw (seen
            // on cloud emulators where the projection token is scoped
            // to the stream). Name the service that failed, leave a
            // diagnostics breadcrumb, and fail gracefully instead of
            // crashing. The user can export diagnostics afterwards.
            var startingService = "CaptureService"
            try {
                startForegroundService(intent)
                startingService = "OverlayService"
                val overlayIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_START
                }
                startForegroundService(overlayIntent)
            } catch (e: Exception) {
                failServiceStart(startingService, e)
                return@registerForActivityResult
            }
            setStatus(getString(R.string.status_capturing))
        } else {
            setStatus(getString(R.string.status_capture_denied))
        }
    }

    /** v1.0.31: a foreground-service start threw. Log it, record a
     * diagnostics breadcrumb naming the service and the exception, and
     * tell the user the capture did not start. DiagnosticsLog.start()
     * is a no-op when a session is already active, so a retry after a
     * failed start appends to the same session. */
    private fun failServiceStart(service: String, e: Exception) {
        Log.e(TAG, "failed to start $service", e)
        DiagnosticsLog.start()
        DiagnosticsLog.record(
            DiagnosticsLog.Kind.CAPTURE_START_FAILED,
            "startForegroundService($service): " +
                "${e::class.java.simpleName}: ${e.message}"
        )
        Toast.makeText(this, R.string.capture_start_failed, Toast.LENGTH_LONG).show()
        setStatus(getString(R.string.capture_start_failed))
    }

    /** Diagnostics export (v1.0.6): the user picks where the report
     * file goes (Storage Access Framework). The log lives only on
     * this device; nothing is uploaded, and the user chooses whether
     * to share the file. A cancelled picker (null Uri) is silent. */
    private val exportDiagnosticsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val header = DiagnosticsLog.Header(
                appVersion = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                gameVersion = store.gameVersion,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
                sessionStartIso = DiagnosticsLog.sessionStartIso()
            )
            val report = DiagnosticsLog.buildReport(header, ShotHistory.snapshot())
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(report.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, R.string.diagnostics_exported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.diagnostics_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = CalibrationStore(this)
        // v1.0.35: attach the on-disk diagnostics sink first, so a
        // breadcrumb from a crashed session is read back on the next
        // launch and carried into the export (see DiagnosticsLog).
        // Attaching here also covers the failServiceStart path, which
        // records before any service has started.
        DiagnosticsLog.attachFile(File(filesDir, DiagnosticsLog.PREVIOUS_SESSION_FILE))
        // v1.0.68: surface a crash record from the last run, if any.
        // A crash before the first session left nothing in the
        // session log, so without this the export gate below still
        // said "No session yet" after a crash.
        DiagnosticsLog.attachCrashFile(File(filesDir, DiagnosticsLog.CRASH_FILE))
        DiagnosticsLog.loadPreviousCrash()

        statusText = findViewById(R.id.status_text)
        previewImage = findViewById(R.id.preview_image)
        previewStatus = findViewById(R.id.preview_status)
        shotHistoryText = findViewById(R.id.shot_history_text)
        // Shot-history strip (v1.0.17): the overlay records each
        // solved shot it draws; this refreshes the strip live. The
        // listener runs on the main thread (the overlay forwards
        // results there), runOnUiThread is belt and braces.
        ShotHistory.listener = { runOnUiThread { refreshShotHistory() } }
        refreshShotHistory()
        previewSwitch = findViewById(R.id.preview_switch)
        showAllShotsSwitch = findViewById(R.id.show_all_shots_switch)
        showAllShotsSwitch.isChecked = store.showAllShots
        showAllShotsSwitch.setOnCheckedChangeListener { _, checked ->
            store.showAllShots = checked
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener { startFlow() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { stopCapture() }
        findViewById<Button>(R.id.btn_demo).setOnClickListener { startDemo() }
        findViewById<Button>(R.id.btn_calibrate).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        findViewById<Button>(R.id.btn_practice).setOnClickListener {
            startActivity(Intent(this, PracticeActivity::class.java))
        }
        findViewById<Button>(R.id.btn_export_diagnostics).setOnClickListener {
            exportDiagnostics()
        }
        previewSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                PreviewBus.listener = { bitmap, isBlack ->
                    previewImage.setImageBitmap(bitmap)
                    previewStatus.text = if (isBlack) {
                        getString(R.string.preview_black)
                    } else {
                        getString(R.string.preview_ok)
                    }
                }
            } else {
                PreviewBus.listener = null
                previewImage.setImageDrawable(null)
                previewStatus.text = ""
            }
        }
        setStatus(
            if (DiagnosticsLog.hasPreviousCrash()) {
                getString(R.string.status_previous_crash)
            } else {
                getString(R.string.status_ready)
            }
        )
    }

    override fun onDestroy() {
        PreviewBus.listener = null
        ShotHistory.listener = null
        super.onDestroy()
    }

    /** Shot-history strip (v1.0.17): the last solved shots the
     * overlay drew, newest last, or the empty state when nothing
     * has solved yet. */
    private fun refreshShotHistory() {
        val shots = ShotHistory.snapshot()
        shotHistoryText.text = if (shots.isEmpty()) {
            getString(R.string.shot_history_empty)
        } else {
            shots.joinToString("\n") { ShotHistory.format(it) }
        }
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    /** Full start flow, with the first-run warning at the front. */
    private fun startFlow() {
        if (!store.warningSeen) {
            AlertDialog.Builder(this)
                .setTitle(R.string.warning_title)
                .setMessage(R.string.warning_body)
                .setPositiveButton(R.string.warning_understood) { _, _ ->
                    store.warningSeen = true
                    checkOverlayThenStart()
                }
                .setNegativeButton(R.string.cancel, null)
                .setCancelable(false)
                .show()
        } else {
            checkOverlayThenStart()
        }
    }

    private fun checkOverlayThenStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_needed, Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayLauncher.launch(intent)
        } else {
            checkBatteryThenStart()
        }
    }

    private fun onOverlayResult() {
        if (Settings.canDrawOverlays(this)) {
            checkBatteryThenStart()
        } else {
            setStatus(getString(R.string.status_overlay_denied))
        }
    }

    /** Battery-optimisation exemption (v1.0.71, Vivo survival):
     * Funtouch OS kills background services aggressively, and a
     * killed service is the most likely reason the bubble vanished
     * on Jawad's Vivo Y03. Asked once, before the projection
     * consent, so the exemption is in place before the session
     * starts. "Not now" continues the flow — the exemption is
     * best-effort, and the README carries the manual Vivo steps. */
    private fun checkBatteryThenStart() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= 23 &&
            !powerManager.isIgnoringBatteryOptimizations(packageName) &&
            !store.batteryOptAsked
        ) {
            store.batteryOptAsked = true
            AlertDialog.Builder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_body)
                .setPositiveButton(R.string.battery_allow) { _, _ ->
                    try {
                        batteryLauncher.launch(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {
                        checkNotificationsThenStart()
                    }
                }
                .setNegativeButton(R.string.battery_later) { _, _ ->
                    checkNotificationsThenStart()
                }
                .setCancelable(false)
                .show()
        } else {
            checkNotificationsThenStart()
        }
    }

    private fun checkNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection()
        }
    }

    private fun onNotificationsResult() {
        // POST_NOTIFICATIONS is best-effort: capture still works without it,
        // the foreground notification is just hidden on some devices.
        requestProjection()
    }

    private fun requestProjection() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    /** Demo mode needs the overlay permission too (it draws real windows).
     * Demo runs in the OverlayService alone (specialUse FGS, no token);
     * the capture service is not involved. */
    private fun startDemo() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_needed, Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayLauncher.launch(intent)
            return
        }
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_DEMO
        }
        // v1.0.35: same gap class as the projection path (v1.0.31):
        // starting a foreground service can throw on a streamed
        // device. Name the service, leave a breadcrumb, fail
        // gracefully instead of crashing.
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            failServiceStart("OverlayService", e)
            return
        }
        setStatus(getString(R.string.status_demo))
    }

    private fun stopCapture() {
        // v1.0.71: the user stopped, so no session is active — a
        // later null-intent restart must not resurrect it. (The
        // services clear the same flags on their own clean stop.)
        store.sessionActive = false
        store.sessionDemo = false
        // The two services run as a pair: stop both, so the lines never
        // outlive the capture (and vice versa).
        // v1.0.68: starting a service can throw while the app is being
        // torn down (the v1.0.35 out-of-scope item); the Stop button
        // must never crash the activity.
        val stopCapture = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        try {
            startService(stopCapture)
        } catch (e: Exception) {
            Log.e(TAG, "failed to stop capture service", e)
        }
        val stopOverlay = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        try {
            startService(stopOverlay)
        } catch (e: Exception) {
            Log.e(TAG, "failed to stop overlay service", e)
        }
        setStatus(getString(R.string.status_stopped))
    }

    /** Diagnostics export (v1.0.6): build the report from the in-memory
     * ring buffer and let the user pick the destination file. With no
     * session yet (no events at all) there is nothing to export.
     * v1.0.35: a previous session that ended without a clean stop also
     * counts. That is exactly the crashed run the user is trying to
     * export after relaunching, when the in-memory log is empty.
     * v1.0.68: a previous crash record counts too — a crash before the
     * first session left no session events at all, so without this
     * the export still said "No session yet" after the very crash the
     * user was trying to report. */
    private fun exportDiagnostics() {
        if (DiagnosticsLog.eventCount() == 0 && DiagnosticsLog.previousSessionEvents().isEmpty() &&
            !DiagnosticsLog.hasPreviousCrash()
        ) {
            Toast.makeText(this, R.string.diagnostics_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        exportDiagnosticsLauncher.launch("pool-guide-diagnostics-$stamp.txt")
    }
}
