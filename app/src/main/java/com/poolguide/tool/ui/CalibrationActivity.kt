package com.poolguide.tool.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.poolguide.tool.R
import com.poolguide.tool.cv.OpenCvInit
import com.poolguide.tool.cv.TableDetector
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.geometry.Vec2
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * First-run table calibration. Shows the latest captured frame (or the
 * bundled demo table when no capture is running) with four draggable corner
 * handles. The preview uses FIT_XY so view coordinates map linearly to
 * image coordinates; the stored corners are normalised (0-1) so they
 * survive resolution changes, and are keyed per game version.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var store: CalibrationStore
    private lateinit var frame: FrameLayout
    private lateinit var preview: ImageView
    private val handles = mutableListOf<ImageView>()
    private var frameBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)
        store = CalibrationStore(this)

        frame = findViewById(R.id.calib_frame)
        preview = findViewById(R.id.calib_preview)
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
        if (bmp != null) preview.setImageBitmap(bmp)
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

    /** Run the table detector on the current frame and move the handles. */
    private fun redetect() {
        val bmp = frameBitmap
        if (bmp == null) {
            Toast.makeText(this, R.string.calib_no_frame, Toast.LENGTH_SHORT).show()
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
            val table = TableDetector().detect(hsv)
            if (table == null) {
                Toast.makeText(this, R.string.calib_not_found, Toast.LENGTH_SHORT).show()
                return
            }
            val norm = table.corners.map {
                Vec2(it.x / bmp.width, it.y / bmp.height)
            }
            placeHandles(norm)
            Toast.makeText(this, R.string.calib_found, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.calib_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun save() {
        val corners = readHandles()
        store.setTableCorners(corners)
        Toast.makeText(this, R.string.calib_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
