package com.poolguide.tool.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.poolguide.tool.R
import com.poolguide.tool.capture.CaptureService
import com.poolguide.tool.capture.OverlayBus
import com.poolguide.tool.capture.PreviewBus
import com.poolguide.tool.cv.CvConfig
import com.poolguide.tool.cv.CvPipeline
import com.poolguide.tool.cv.OpenCvInit
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.data.DiagnosticsLog
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Foreground service that OWNS the overlay windows (and Demo mode).
 *
 * The FGS split (v1.0.2): this service is specialUse ONLY. It never
 * holds a MediaProjection token, so it can never hit the Android 14+
 * "mediaProjection-type FGS without a token" SecurityException that
 * crashed Demo mode in v1.0.0. Live results arrive from
 * [CaptureService] via [OverlayBus]; Demo mode runs its own pipeline
 * on the bundled synthetic table. All WindowManager work happens on
 * the main thread.
 *
 * Lifecycle: MainActivity starts this alongside CaptureService for
 * live capture, or alone for Demo mode. Stopping either service stops
 * both (the Stop button, or MainActivity's Stop, sends ACTION_STOP to
 * both), so lines never outlive the capture.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.poolguide.tool.overlay.action.START"
        const val ACTION_STOP = "com.poolguide.tool.overlay.action.STOP"
        const val ACTION_DEMO = "com.poolguide.tool.overlay.action.DEMO"

        const val NOTIF_ID = 1002
        const val CHANNEL_ID = "poolguide_overlay"
        private const val TAG = "OverlayService"
        /** Demo frame interval: the 15 FPS cap (CvConfig). */
        private const val DEMO_FRAME_MS = CvConfig.MIN_FRAME_INTERVAL_MS
    }

    private lateinit var store: CalibrationStore
    private var overlayManager: LineOverlayManager? = null
    private var pipeline: CvPipeline? = null

    private var cvThread: HandlerThread? = null
    private var cvHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val demoMat = Mat()
    private val previewMat = Mat()
    private var previewBitmap: Bitmap? = null
    private var demoRunnable: Runnable? = null

    private var running = false
    private var demoMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_DEMO -> {
                if (running) return START_STICKY
                running = true
                demoMode = intent.action == ACTION_DEMO
                startOverlay()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startOverlay() {
        store = CalibrationStore(this)
        // Diagnostics session (v1.0.6). A second start while a session
        // is active is a no-op, so CaptureService starting at the
        // same time cannot wipe these first events.
        DiagnosticsLog.start()

        // 1. Notification + startForeground FIRST (5s timeout on Android 14+).
        // specialUse on 34+ (declared in the manifest with its subtype
        // property); the typeless overload below 34, where no type check
        // exists. Never mediaProjection: this service holds no token.
        createNotificationChannel()
        val notif = buildNotification(
            if (demoMode) "Demo mode — synthetic table" else "Guide overlay active"
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        // 2. The overlay manager + the bus listener (main thread).
        overlayManager = LineOverlayManager(this, store) {
            // Stop button in the control bubble: stop BOTH services.
            stopAll()
        }
        OverlayBus.listener = { result ->
            mainHandler.post { overlayManager?.update(result) }
        }

        if (demoMode) {
            startDemoLoop()
        }
    }

    /** Stop this service AND the capture service (they run as a pair). */
    private fun stopAll() {
        val stopCapture = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        startService(stopCapture)
        stopOverlay()
        stopSelf()
    }

    // ---- Demo mode: loop the bundled synthetic table through the pipeline.
    private fun startDemoLoop() {
        // OpenCV warm-up AFTER startForeground.
        if (!OpenCvInit.ensureLoaded()) {
            Log.e(TAG, "OpenCV failed to load")
            stopSelf()
            return
        }
        try {
            assets.open("demo/demo_table_green.png").use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                    ?: throw IllegalStateException("demo image failed to decode")
                Utils.bitmapToMat(bitmap, demoMat)
            }
        } catch (e: Exception) {
            Log.e(TAG, "demo image load failed", e)
            stopSelf()
            return
        }
        val metrics = currentDisplayMetrics()
        pipeline = CvPipeline(metrics.widthPixels, metrics.heightPixels).apply {
            tableOverrideNormalized = store.getTableCorners()
            gameProfile = store.getGameProfile()
            // Demo "screen" is the real display (rotate to landscape to
            // see the lines where the table would be).
            setScreenSize(metrics.widthPixels, metrics.heightPixels)
        }
        cvThread = HandlerThread("poolguide-demo").apply { start() }
        cvHandler = Handler(cvThread!!.looper)
        val runnable = object : Runnable {
            override fun run() {
                if (!running || !demoMode) return
                try {
                    val result = pipeline?.process(demoMat) ?: return
                    // Same path as live results: main thread, via the bus.
                    mainHandler.post { overlayManager?.update(result) }
                    postPreview(demoMat, result.isCaptureBlack)
                } catch (e: Exception) {
                    Log.e(TAG, "demo frame failed", e)
                }
                cvHandler?.postDelayed(this, DEMO_FRAME_MS)
            }
        }
        demoRunnable = runnable
        cvHandler!!.post(runnable)
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

    private fun currentDisplayMetrics(): DisplayMetrics {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun stopOverlay() {
        running = false
        DiagnosticsLog.end()
        OverlayBus.listener = null
        demoRunnable?.let { cvHandler?.removeCallbacks(it) }
        demoRunnable = null
        overlayManager?.removeAll()
        overlayManager = null
        cvThread?.quitSafely()
        cvThread = null
        cvHandler = null
        pipeline = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopOverlay()
        super.onDestroy()
    }

    // ---- Notification ----
    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_overlay_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.notif_overlay_channel_desc) }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
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
}
