package com.poolguide.tool.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.poolguide.tool.R
import com.poolguide.tool.cv.CvPipeline
import com.poolguide.tool.cv.OpenCvInit
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.overlay.LineOverlayManager
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that captures the screen (MediaProjection) and runs
 * the CV pipeline + per-line overlay windows.
 *
 * Per-version checklist (ChatGPT, Android 10-15) implemented here:
 * - FOREGROUND_SERVICE_MEDIA_PROJECTION declared in the manifest.
 * - startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
 *   — the 3-arg overload, mandatory on Android 14+.
 * - OpenCV warm-up happens AFTER startForeground (5-second timeout on 14+).
 * - The MediaProjection token is created in THIS process, in this service,
 *   from the resultCode/data the Activity passes immediately.
 * - MediaProjection.Callback.onStop() stops the service gracefully.
 * - The VirtualDisplay is fixed landscape 1280x720: 8 Ball Pool plays in
 *   landscape, and the pipeline re-reads the physical display size on
 *   rotation changes so the working->screen mapping stays correct.
 * - Partial screen sharing (14/15): whatever the user shares is what we
 *   get; the pipeline handles any frame size, and the diagnostic preview
 *   shows the user exactly what is being captured.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.poolguide.tool.action.START"
        const val ACTION_STOP = "com.poolguide.tool.action.STOP"
        const val ACTION_DEMO = "com.poolguide.tool.action.DEMO"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "poolguide_capture"
        private const val TAG = "CaptureService"
        /** Virtual display size (landscape; the game plays in landscape). */
        private const val VD_WIDTH = 1280
        private const val VD_HEIGHT = 720
        private const val DEMO_FRAME_MS = 100L
    }

    private lateinit var store: CalibrationStore
    private var pipeline: CvPipeline? = null
    private var overlayManager: LineOverlayManager? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var cvThread: HandlerThread? = null
    private var cvHandler: Handler? = null
    private val cvBusy = AtomicBoolean(false)
    private val frameMat = Mat()
    private val previewMat = Mat()
    private var previewBitmap: Bitmap? = null
    private val demoMat = Mat()
    private var demoRunnable: Runnable? = null

    private var running = false
    private var demoMode = false
    private var lastNotifUpdateMs = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            updateScreenSize()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_DEMO -> {
                if (running) return START_STICKY
                running = true
                demoMode = intent.action == ACTION_DEMO
                startCapture(intent)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        store = CalibrationStore(this)

        // 1. Notification + startForeground FIRST (5s timeout on Android 14+).
        createNotificationChannel()
        startForeground(
            NOTIF_ID,
            buildNotification("Starting capture…"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        // 2. Heavy work only AFTER startForeground.
        if (!OpenCvInit.ensureLoaded()) {
            updateNotification("OpenCV failed to load — stopping")
            Log.e(TAG, "OpenCV failed to load")
            stopSelf()
            return
        }

        val metrics = currentDisplayMetrics()
        pipeline = CvPipeline(metrics.widthPixels, metrics.heightPixels).apply {
            tableOverrideNormalized = store.getTableCorners()
        }
        overlayManager = LineOverlayManager(this, store) {
            stopCapture()
            stopSelf()
        }
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, Handler(mainLooper))

        if (demoMode) {
            startDemoLoop()
            updateNotification("Demo mode — synthetic table")
        } else {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (resultCode == 0 || resultData == null) {
                updateNotification("No screen-capture token — stopping")
                stopSelf()
                return
            }
            startMediaProjection(resultCode, resultData)
        }
    }

    private fun startMediaProjection(resultCode: Int, resultData: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // User stopped the projection from the status-bar tile.
                stopCapture()
                stopSelf()
            }
        }, Handler(mainLooper))

        val densityDpi = resources.displayMetrics.densityDpi
        imageReader = ImageReader.newInstance(VD_WIDTH, VD_HEIGHT, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "PoolGuideCapture",
            VD_WIDTH, VD_HEIGHT, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        cvThread = HandlerThread("poolguide-cv").apply { start() }
        cvHandler = Handler(cvThread!!.looper)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                copyImageToMat(image)
            } finally {
                image.close()
            }
            runPipelineFrame()
        }, Handler(cvThread!!.looper))
        updateNotification("Capturing — aim lines active")
    }

    /** Copy the ImageReader RGBA frame into [frameMat], honouring rowStride. */
    private fun copyImageToMat(image: android.media.Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (frameMat.rows() != height || frameMat.cols() != width) {
            frameMat.create(height, width, CvType.CV_8UC4)
        }
        val bytes = ByteArray(rowStride * height)
        buffer.get(bytes)
        // Re-pack rows (rowStride may exceed width * 4).
        if (rowStride == width * 4) {
            frameMat.put(0, 0, bytes)
        } else {
            val row = ByteArray(width * 4)
            for (y in 0 until height) {
                System.arraycopy(bytes, y * rowStride, row, 0, width * 4)
                frameMat.put(y, 0, row)
            }
        }
    }

    /** Single-slot: drop the frame if the previous one is still processing. */
    private fun runPipelineFrame() {
        if (!cvBusy.compareAndSet(false, true)) return
        val handler = cvHandler ?: return
        handler.post {
            try {
                val result = pipeline?.process(frameMat) ?: return@post
                overlayManager?.update(result)
                postPreview(frameMat, result.isCaptureBlack)
                maybeUpdateNotification(result.status)
            } catch (e: Exception) {
                Log.e(TAG, "pipeline frame failed", e)
            } finally {
                cvBusy.set(false)
            }
        }
    }

    private fun postPreview(source: Mat, isBlack: Boolean) {
        if (PreviewBus.listener == null) return
        Imgproc.resize(source, previewMat, org.opencv.core.Size(360.0, 202.0))
        var bmp = previewBitmap
        if (bmp == null || bmp.width != 360 || bmp.height != 202) {
            bmp = Bitmap.createBitmap(360, 202, Bitmap.Config.ARGB_8888)
            previewBitmap = bmp
        }
        Utils.matToBitmap(previewMat, bmp)
        PreviewBus.post(bmp, isBlack)
    }

    private fun maybeUpdateNotification(status: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifUpdateMs < 1000) return
        lastNotifUpdateMs = now
        updateNotification(status)
    }

    // ---- Demo mode: loop the bundled synthetic table through the pipeline.
    private fun startDemoLoop() {
        try {
            assets.open("demo/demo_table_green.png").use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                    ?: throw IllegalStateException("demo image failed to decode")
                Utils.bitmapToMat(bitmap, demoMat)
            }
        } catch (e: Exception) {
            Log.e(TAG, "demo image load failed", e)
            updateNotification("Demo image missing — stopping")
            stopSelf()
            return
        }
        cvThread = HandlerThread("poolguide-cv").apply { start() }
        cvHandler = Handler(cvThread!!.looper)
        // Demo "screen" is the real display (rotate to landscape to see the
        // lines where the table would be); the DisplayListener keeps the
        // mapping correct when the phone rotates.
        val demoMetrics = currentDisplayMetrics()
        pipeline?.setScreenSize(demoMetrics.widthPixels, demoMetrics.heightPixels)
        val runnable = object : Runnable {
            override fun run() {
                if (!running || !demoMode) return
                try {
                    val result = pipeline?.process(demoMat) ?: return
                    overlayManager?.update(result)
                    postPreview(demoMat, result.isCaptureBlack)
                    maybeUpdateNotification("Demo: ${result.status}")
                } catch (e: Exception) {
                    Log.e(TAG, "demo frame failed", e)
                }
                cvHandler?.postDelayed(this, DEMO_FRAME_MS)
            }
        }
        demoRunnable = runnable
        cvHandler!!.post(runnable)
    }

    private fun updateScreenSize() {
        val metrics = currentDisplayMetrics()
        pipeline?.setScreenSize(metrics.widthPixels, metrics.heightPixels)
    }

    private fun currentDisplayMetrics(): DisplayMetrics {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun stopCapture() {
        running = false
        demoRunnable?.let { cvHandler?.removeCallbacks(it) }
        demoRunnable = null
        overlayManager?.removeAll()
        overlayManager = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        imageReader?.close()
        imageReader = null
        cvThread?.quitSafely()
        cvThread = null
        cvHandler = null
        pipeline = null
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    // ---- Notification ----
    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.notif_channel_desc) }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, CaptureService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this, android.R.drawable.ic_menu_close_clear_cancel
                    ),
                    getString(R.string.stop),
                    stopPending
                ).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(text))
    }
}
