package com.poolguide.tool.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.poolguide.tool.R
import com.poolguide.tool.cv.CalibrationPreview
import com.poolguide.tool.cv.OpenCvInit
import com.poolguide.tool.cv.TableDetector
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.data.GameProfile
import com.poolguide.tool.geometry.Vec2
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * First-run table calibration. Shows the latest captured frame (or the
 * bundled demo table when no capture is running) with four draggable corner
 * handles. The preview uses FIT_XY so view coordinates map linearly to
 * image coordinates; the stored corners are normalised (0-1) so they
 * survive resolution changes, and are keyed per game version.
 *
 * v1.0.8: the brown felt window and the pocket/ball thresholds are
 * sliders (not typed fields), initialised from the stored profile for
 * the current game version. A "Show mask" toggle swaps the preview to
 * the inRange felt mask for the current slider values, and a status line
 * reports honestly what the preview frame shows with those values —
 * it never promises the live pipeline will lock.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var store: CalibrationStore
    private lateinit var frame: FrameLayout
    private lateinit var preview: ImageView
    private lateinit var statusView: TextView
    private lateinit var showMaskSwitch: Switch
    private val handles = mutableListOf<ImageView>()
    private var frameBitmap: Bitmap? = null
    private var maskBitmap: Bitmap? = null

    /** Brown felt window sliders (H/S/V low + high), in layout order. */
    private lateinit var brownSliders: List<SeekBar>
    private lateinit var brownLabels: List<TextView>
    private lateinit var pocketInsetSlider: SeekBar
    private lateinit var pocketRadiusSlider: SeekBar
    private lateinit var ballRadiusSlider: SeekBar
    private lateinit var pocketInsetLabel: TextView
    private lateinit var pocketRadiusLabel: TextView
    private lateinit var ballRadiusLabel: TextView

    private val statusHandler = Handler(Looper.getMainLooper())
    private var statusPending = false
    private val statusRunnable = Runnable {
        statusPending = false
        refreshPreviewStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)
        store = CalibrationStore(this)

        frame = findViewById(R.id.calib_frame)
        preview = findViewById(R.id.calib_preview)
        statusView = findViewById(R.id.calib_status)
        showMaskSwitch = findViewById(R.id.calib_show_mask)

        brownSliders = listOf(
            R.id.brown_h_lo, R.id.brown_h_hi,
            R.id.brown_s_lo, R.id.brown_s_hi,
            R.id.brown_v_lo, R.id.brown_v_hi
        ).map { findViewById<SeekBar>(it) }
        brownLabels = listOf(
            R.id.brown_h_lo_value, R.id.brown_h_hi_value,
            R.id.brown_s_lo_value, R.id.brown_s_hi_value,
            R.id.brown_v_lo_value, R.id.brown_v_hi_value
        ).map { findViewById<TextView>(it) }
        pocketInsetSlider = findViewById(R.id.pocket_inset)
        pocketRadiusSlider = findViewById(R.id.pocket_radius)
        ballRadiusSlider = findViewById(R.id.ball_radius)
        pocketInsetLabel = findViewById(R.id.pocket_inset_value)
        pocketRadiusLabel = findViewById(R.id.pocket_radius_value)
        ballRadiusLabel = findViewById(R.id.ball_radius_value)

        fillSliders(store.getGameProfile())
        val sliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateLabels()
                scheduleStatusRefresh()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        }
        (brownSliders + listOf(pocketInsetSlider, pocketRadiusSlider, ballRadiusSlider))
            .forEach { it.setOnSeekBarChangeListener(sliderListener) }

        showMaskSwitch.setOnCheckedChangeListener { _, _ -> refreshPreviewImage() }

        val handleIds = listOf(
            R.id.handle_tl, R.id.handle_tr, R.id.handle_br, R.id.handle_bl
        )
        for (id in handleIds) {
            val handle = findViewById<ImageView>(id)
            handles += handle
            makeDraggable(handle)
        }

        loadFrame()
        findViewById<Button>(R.id.btn_redetect).setOnClickListener { redetect() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_reset_defaults).setOnClickListener { resetToDefaults() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            store.clearTableCorners()
            Toast.makeText(this, R.string.calib_cleared, Toast.LENGTH_SHORT).show()
            finish()
        }

        // Position the handles once the layout is measured.
        frame.post {
            val saved = store.getTableCorners()
            if (saved != null) {
                placeHandles(saved)
            } else {
                placeHandles(defaultCorners())
            }
        }
        // Status for the initial slider values, once the frame is in.
        frame.post { refreshPreviewStatus() }
    }

    override fun onDestroy() {
        statusHandler.removeCallbacks(statusRunnable)
        super.onDestroy()
    }

    private fun loadFrame() {
        // Prefer the live capture preview; fall back to the demo table.
        val bmp = lastPreviewBitmap() ?: run {
            try {
                assets.open("demo/demo_table_green.png").use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (_: Exception) {
                null
            }
        }
        frameBitmap = bmp
        maskBitmap = null
        refreshPreviewImage()
    }

    /** The preview bus holds only the latest frame; MainActivity owns the
     * listener, so here we re-read the last frame the bus posted. */
    private fun lastPreviewBitmap(): Bitmap? =
        com.poolguide.tool.capture.PreviewBus.lastFrame

    private fun defaultCorners(): List<Vec2> = listOf(
        Vec2(0.08, 0.15), Vec2(0.92, 0.15), Vec2(0.92, 0.85), Vec2(0.08, 0.85)
    )

    private fun placeHandles(normalized: List<Vec2>) {
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        if (w <= 0 || h <= 0 || normalized.size != 4) return
        for (i in 0 until 4) {
            val handle = handles[i]
            val x = (normalized[i].x * w).toFloat()
            val y = (normalized[i].y * h).toFloat()
            val lp = handle.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = (x - handle.width / 2f).toInt().coerceAtLeast(0)
            lp.topMargin = (y - handle.height / 2f).toInt().coerceAtLeast(0)
            handle.layoutParams = lp
        }
    }

    private fun readHandles(): List<Vec2> {
        val w = frame.width.toFloat().coerceAtLeast(1f)
        val h = frame.height.toFloat().coerceAtLeast(1f)
        return handles.map { handle ->
            val lp = handle.layoutParams as FrameLayout.LayoutParams
            val cx = lp.leftMargin + handle.width / 2f
            val cy = lp.topMargin + handle.height / 2f
            Vec2((cx / w).toDouble(), (cy / h).toDouble())
        }
    }

    private fun makeDraggable(handle: View) {
        var lastX = 0f
        var lastY = 0f
        handle.setOnTouchListener { v, event ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    lastX = event.rawX
                    lastY = event.rawY
                    lp.leftMargin = (lp.leftMargin + dx)
                        .coerceIn(0, frame.width - v.width)
                    lp.topMargin = (lp.topMargin + dy)
                        .coerceIn(0, frame.height - v.height)
                    v.layoutParams = lp
                    true
                }
                else -> false
            }
        }
    }

    /** Prefill all sliders from the stored profile. */
    private fun fillSliders(profile: GameProfile) {
        val brownValues = listOf(
            profile.brownHLo to CalibrationPreview.BROWN_H_MAX,
            profile.brownHHi to CalibrationPreview.BROWN_H_MAX,
            profile.brownSLo to CalibrationPreview.BROWN_SV_MAX,
            profile.brownSHi to CalibrationPreview.BROWN_SV_MAX,
            profile.brownVLo to CalibrationPreview.BROWN_SV_MAX,
            profile.brownVHi to CalibrationPreview.BROWN_SV_MAX
        )
        for ((slider, valueAndMax) in brownSliders.zip(brownValues)) {
            slider.progress = CalibrationPreview.brownValueToSlider(
                valueAndMax.first, valueAndMax.second
            )
        }
        pocketInsetSlider.progress =
            CalibrationPreview.fractionValueToSlider(profile.pocketInsetFraction)
        pocketRadiusSlider.progress =
            CalibrationPreview.fractionValueToSlider(profile.pocketRadiusFraction)
        ballRadiusSlider.progress =
            CalibrationPreview.ballRadiusValueToSlider(profile.ballRadiusPxAt720)
        updateLabels()
    }

    /** Refresh every value label from the current slider positions,
     * each next to its default. Called immediately on any slider move. */
    private fun updateLabels() {
        val d = GameProfile.DEFAULT
        val brownDefaults = listOf(
            d.brownHLo, d.brownHHi, d.brownSLo, d.brownSHi, d.brownVLo, d.brownVHi
        )
        val labelRes = listOf(
            R.string.calib_h_lo, R.string.calib_h_hi,
            R.string.calib_s_lo, R.string.calib_s_hi,
            R.string.calib_v_lo, R.string.calib_v_hi
        )
        for (i in brownSliders.indices) {
            brownLabels[i].text = getString(
                labelRes[i], brownSliders[i].progress, brownDefaults[i].toInt()
            )
        }
        pocketInsetLabel.text = getString(
            R.string.calib_pocket_inset,
            CalibrationPreview.fractionSliderToValue(pocketInsetSlider.progress),
            d.pocketInsetFraction
        )
        pocketRadiusLabel.text = getString(
            R.string.calib_pocket_radius,
            CalibrationPreview.fractionSliderToValue(pocketRadiusSlider.progress),
            d.pocketRadiusFraction
        )
        ballRadiusLabel.text = getString(
            R.string.calib_ball_radius,
            CalibrationPreview.ballRadiusSliderToValue(ballRadiusSlider.progress),
            d.ballRadiusPxAt720
        )
    }

    /** The profile the current slider positions describe, or null when
     * they are invalid (low above high). Never persisted when null. */
    private fun currentProfile(): GameProfile? = CalibrationPreview.profileFromSliders(
        version = store.gameVersion,
        brownHLo = brownSliders[0].progress, brownHHi = brownSliders[1].progress,
        brownSLo = brownSliders[2].progress, brownSHi = brownSliders[3].progress,
        brownVLo = brownSliders[4].progress, brownVHi = brownSliders[5].progress,
        pocketInsetSlider = pocketInsetSlider.progress,
        pocketRadiusSlider = pocketRadiusSlider.progress,
        ballRadiusSlider = ballRadiusSlider.progress
    )

    private fun scheduleStatusRefresh() {
        if (!statusPending) {
            statusPending = true
            statusHandler.postDelayed(statusRunnable, STATUS_DEBOUNCE_MS)
        }
    }

    /** Show the frame, or the felt mask when the toggle is on. The mask
     * is computed from the CURRENT slider values, so it always matches
     * what the status line reports. */
    private fun refreshPreviewImage() {
        if (showMaskSwitch.isChecked) {
            val mask = computeMaskBitmap()
            if (mask != null) {
                preview.setImageBitmap(mask)
                return
            }
        }
        frameBitmap?.let { preview.setImageBitmap(it) }
    }

    /** The inRange mask for the current slider window on the current
     * frame, rendered as a bitmap, or null when there is no frame or
     * OpenCV is unavailable. */
    private fun computeMaskBitmap(): Bitmap? {
        val bmp = frameBitmap ?: return null
        val profile = currentProfile() ?: return null
        if (!OpenCvInit.ensureLoaded()) return null
        return try {
            val rgba = Mat()
            Utils.bitmapToMat(bmp, rgba)
            val bgr = Mat()
            val hsv = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            val mask = Mat()
            Core.inRange(hsv, Scalar(profile.brownFeltLower), Scalar(profile.brownFeltUpper), mask)
            val maskRgba = Mat()
            Imgproc.cvtColor(mask, maskRgba, Imgproc.COLOR_GRAY2RGBA)
            val out = Bitmap.createBitmap(mask.cols(), mask.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(maskRgba, out)
            maskBitmap = out
            out
        } catch (_: Exception) {
            null
        }
    }

    /** Recompute the mask coverage and the table detection for the
     * current slider values on the current frame, and report honestly:
     * what the preview frame shows, never a promise about the live
     * pipeline. */
    private fun refreshPreviewStatus() {
        val bmp = frameBitmap
        if (bmp == null) {
            statusView.setText(R.string.calib_status_no_frame)
            return
        }
        val profile = currentProfile()
        if (profile == null) {
            statusView.setText(R.string.calib_status_invalid)
            refreshPreviewImage()
            return
        }
        if (!OpenCvInit.ensureLoaded()) {
            statusView.setText(R.string.calib_opencv_missing)
            return
        }
        try {
            val rgba = Mat()
            Utils.bitmapToMat(bmp, rgba)
            val bgr = Mat()
            val hsv = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            val mask = Mat()
            Core.inRange(hsv, Scalar(profile.brownFeltLower), Scalar(profile.brownFeltUpper), mask)
            val coverage = CalibrationPreview.maskCoverage(
                Core.countNonZero(mask).toLong(),
                (mask.rows() * mask.cols()).toLong()
            )
            val table = TableDetector().detect(hsv, profile)
            when (CalibrationPreview.decideStatus(table != null, coverage)) {
                CalibrationPreview.PreviewStatus.FOUND ->
                    statusView.setText(R.string.calib_status_found)
                CalibrationPreview.PreviewStatus.NOT_FOUND ->
                    statusView.text = getString(
                        R.string.calib_status_not_found,
                        CalibrationPreview.formatCoveragePercent(coverage)
                    )
                CalibrationPreview.PreviewStatus.NO_FRAME ->
                    statusView.setText(R.string.calib_status_no_frame)
            }
            refreshPreviewImage()
        } catch (_: Exception) {
            statusView.setText(R.string.calib_failed)
        }
    }

    /** Run the table detector on the current frame with the CURRENT
     * slider values and move the handles. */
    private fun redetect() {
        val bmp = frameBitmap
        if (bmp == null) {
            Toast.makeText(this, R.string.calib_no_frame, Toast.LENGTH_SHORT).show()
            return
        }
        val profile = currentProfile()
        if (profile == null) {
            Toast.makeText(this, R.string.calib_brown_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (!OpenCvInit.ensureLoaded()) {
            Toast.makeText(this, R.string.calib_opencv_missing, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val rgba = Mat()
            Utils.bitmapToMat(bmp, rgba)
            val bgr = Mat()
            val hsv = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            val table = TableDetector().detect(hsv, profile)
            if (table == null) {
                Toast.makeText(this, R.string.calib_not_found, Toast.LENGTH_SHORT).show()
                refreshPreviewStatus()
                return
            }
            val norm = table.corners.map {
                Vec2(it.x / bmp.width, it.y / bmp.height)
            }
            placeHandles(norm)
            Toast.makeText(this, R.string.calib_found, Toast.LENGTH_SHORT).show()
            refreshPreviewStatus()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.calib_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun save() {
        val profile = currentProfile()
        if (profile == null) {
            Toast.makeText(this, R.string.calib_brown_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        store.setBrownFeltWindow(profile.brownFeltLower, profile.brownFeltUpper)
        store.setGameProfile(profile)
        val corners = readHandles()
        store.setTableCorners(corners)
        Toast.makeText(this, R.string.calib_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Clear brown window + game profile + table corners for the current
     * version, reset every slider to the defaults, re-run the status. */
    private fun resetToDefaults() {
        store.clearBrownFeltWindow()
        store.clearGameProfile()
        store.clearTableCorners()
        fillSliders(GameProfile.DEFAULT)
        placeHandles(defaultCorners())
        refreshPreviewStatus()
        Toast.makeText(this, R.string.calib_defaults_restored, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Debounce for the live status while a slider is dragged. */
        const val STATUS_DEBOUNCE_MS = 150L
    }
}
