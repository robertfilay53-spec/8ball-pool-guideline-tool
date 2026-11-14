package com.poolguide.tool.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.poolguide.tool.R
import com.poolguide.tool.cv.CvConfig
import com.poolguide.tool.cv.CvPipeline
import com.poolguide.tool.cv.OpenCvInit
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.overlay.OverlayService
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that captures the screen (MediaProjection) and runs
 * the CV pipeline. MediaProjection ONLY — the overlay windows live in
 * [OverlayService] (specialUse) since v1.0.2; the two services talk via
 * [OverlayBus]. This service never draws a window and never runs Demo
 * mode, so it can never hit the Android 14+ "mediaProjection-type FGS
 * without a token" crash.
 *
 * Per-version checklist (ChatGPT, Android 10-15) implemented here:
 * - FOREGROUND_SERVICE_MEDIA_PROJECTION declared in the manifest.
 * - startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
 *   — the 3-arg overload, mandatory on Android 14+.
 * - OpenCV warm-up happens AFTER startForeground (5-second timeout on 14+).
 * - The MediaProjection token is created in THIS process, in this service,
 *   from the resultCode/data the Activity passes immediately.
 * - MediaProjection.Callback.onStop() stops the service gracefully.
 * - The VirtualDisplay is 960x540 (0.75x capture scale, Scugalug
 *   measured targets): the pipeline downscales to 720 wide anyway.
 * - Frames are paced to 15 FPS (FramePacer) before the copy — the
 *   table does not move between frames, and anything faster only
 *   heats the phone.
 * - Partial screen sharing (14/15): whatever the user shares is what we
 *   get; the pipeline handles any frame size, and the diagnostic preview
 *   shows the user exactly what is being captured.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.poolguide.tool.action.START"
        const val ACTION_STOP = "com.poolguide.tool.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "poolguide_capture"
        private const val TAG = "CaptureService"
        /** Virtual display size: 1280x720 x 0.75 capture scale (Scugalug).
         * 8 Ball Pool plays in landscape. */
        private const val VD_WIDTH = 960
        private const val VD_HEIGHT = 540
    }

    private lateinit var store: CalibrationStore
    private var pipeline: CvPipeline? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var cvThread: HandlerThread? = null
    private var cvHandler: Handler? = null
    private val cvBusy = AtomicBoolean(false)
    private val framePacer = FramePacer(CvConfig.MIN_FRAME_INTERVAL_MS)
    private val frameMat = Mat()
    private val previewMat = Mat()
    private var previewBitmap: Bitmap? = null

    private var running = false
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
            ACTION_START -> {
                if (running) return START_STICKY
                running = true
                startCapture(intent)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        store = CalibrationStore(this)

        // 1. Notification + startForeground FIRST (5s timeout on Android 14+).
        // Live capture always holds a real MediaProjection token, so the
        // mediaProjection type is correct here (and only here).
        createNotificationChannel()
        val notif = buildNotification("Starting capture…")
        startForeground(
            NOTIF_ID, notif,
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
            gameProfile = store.getGameProfile()
        }
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, Handler(mainLooper))

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
                // 15 FPS cap, checked BEFORE the copy: a dropped frame
                // costs nothing but the acquire/close.
                if (!framePacer.shouldProcess(SystemClock.uptimeMillis())) {
                    return@setOnImageAvailableListener
                }
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
                OverlayBus.post(result)
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
        // The overlay lives in its own service: stop it too, so the
        // lines never outlive the capture.
        val stopOverlay = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(stopOverlay)
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
