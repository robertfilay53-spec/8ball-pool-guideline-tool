package com.poolguide.tool.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.poolguide.tool.R
import com.poolguide.tool.capture.CaptureService
import com.poolguide.tool.capture.PreviewBus
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.overlay.OverlayService

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

    private lateinit var store: CalibrationStore
    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var previewStatus: TextView
    private lateinit var previewSwitch: Switch
    private lateinit var showAllShotsSwitch: Switch

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onOverlayResult() }

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
            startForegroundService(intent)
            val overlayIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START
            }
            startForegroundService(overlayIntent)
            setStatus(getString(R.string.status_capturing))
        } else {
            setStatus(getString(R.string.status_capture_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = CalibrationStore(this)

        statusText = findViewById(R.id.status_text)
        previewImage = findViewById(R.id.preview_image)
        previewStatus = findViewById(R.id.preview_status)
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
        setStatus(getString(R.string.status_ready))
    }

    override fun onDestroy() {
        PreviewBus.listener = null
        super.onDestroy()
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
            checkNotificationsThenStart()
        }
    }

    private fun onOverlayResult() {
        if (Settings.canDrawOverlays(this)) {
            checkNotificationsThenStart()
        } else {
            setStatus(getString(R.string.status_overlay_denied))
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
        startForegroundService(intent)
        setStatus(getString(R.string.status_demo))
    }

    private fun stopCapture() {
        // The two services run as a pair: stop both, so the lines never
        // outlive the capture (and vice versa).
        val stopCapture = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        startService(stopCapture)
        val stopOverlay = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(stopOverlay)
        setStatus(getString(R.string.status_stopped))
    }
}
