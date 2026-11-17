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
import java.io.File

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
    /** v1.0.68: created in [startDemoLoop], AFTER OpenCV is loaded.
     * These used to be `Mat()` field initialisers, so constructing the
     * service called the native Mat constructor before any loadLibrary
     * ran and threw UnsatisfiedLinkError (an Error, uncatchable by
     * catch-Exception) — the Demo-mode crash, same root cause as the
     * Start-capture crash in CaptureService. See OpenCvLoadOrderTest. */
    private var demoMat: Mat? = null
    private var previewMat: Mat? = null
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
            // v1.0.71, Vivo survival: the system restarting a sticky
            // service after the process was killed passes a null
            // intent. The old code returned START_NOT_STICKY and
            // restarted nothing, so a Vivo background kill left the
            // bubble gone until the user reopened the app. A session
            // that was never cleanly stopped is recovered here; a
            // stopped one (or a fresh boot) is not.
            null -> {
                if (!::store.isInitialized) store = CalibrationStore(this)
                if (com.poolguide.tool.capture.SessionRecovery
                        .shouldRecoverSession(store.sessionActive, null)
                ) {
                    running = true
                    demoMode = store.sessionDemo
                    startOverlay(recovered = true)
                    return START_STICKY
                }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startOverlay(recovered: Boolean = false) {
        // v1.0.35: attach the on-disk diagnostics sink before the
        // session starts (same file as CaptureService; the two
        // services starting together share one session).
        DiagnosticsLog.attachFile(File(filesDir, DiagnosticsLog.PREVIOUS_SESSION_FILE))
        // 1. Notification + startForeground FIRST (5s timeout on Android 14+).
        // specialUse on 34+ (declared in the manifest with its subtype
        // property); the typeless overload below 34, where no type check
        // exists. Never mediaProjection: this service holds no token.
        //
        // v1.0.35: this prelude used to run outside any try/catch, the
        // same gap as CaptureService.startCapture: a throw in
        // startForeground (or while building the overlay manager's
        // windows) killed the process with no breadcrumb. The steps
        // carry their own names, MainActivity's v1.0.31 pattern.
        var step = "CalibrationStore"
        try {
            store = CalibrationStore(this)
            // v1.0.71: a session is active from here until a clean
            // stop clears it, so a null-intent restart after a kill
            // can tell a live session from a stopped one.
            store.sessionActive = true
            store.sessionDemo = demoMode
            // Diagnostics session (v1.0.6). A second start while a session
            // is active is a no-op, so CaptureService starting at the
            // same time cannot wipe these first events.
            DiagnosticsLog.start()
            if (recovered) {
                DiagnosticsLog.record(
                    DiagnosticsLog.Kind.SESSION_RESTARTED,
                    "OverlayService restarted after process death, " +
                        if (demoMode) "demo mode" else "live mode"
                )
            }
            step = "createNotificationChannel"
            createNotificationChannel()
            step = "buildNotification"
            val notif = buildNotification(
                when {
                    recovered -> getString(R.string.notif_overlay_restored)
                    demoMode -> "Demo mode — synthetic table"
                    else -> "Guide overlay active"
                }
            )
            step = "startForeground"
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
            step = "LineOverlayManager"
            // 2. The overlay manager + the bus listener (main thread).
            overlayManager = LineOverlayManager(this, store) {
                // Stop button in the control bubble: stop BOTH services.
                stopAll()
            }
        } catch (e: Exception) {
            failOverlayStart(step, e)
            return
        }
        OverlayBus.listener = { result ->
            mainHandler.post { overlayManager?.update(result) }
        }

        if (demoMode) {
            startDemoLoop()
        }
    }

    /** v1.0.35: an overlay-startup step threw. Same pattern as
     * CaptureService.failCaptureStart: log it, leave a diagnostics
     * breadcrumb naming the failing call, and stop gracefully instead
     * of crashing. The breadcrumb is mirrored to disk the moment it
     * is recorded, so it survives even a hard crash. */
    private fun failOverlayStart(step: String, e: Exception) {
        Log.e(TAG, "overlay start failed at $step", e)
        DiagnosticsLog.record(
            DiagnosticsLog.Kind.CAPTURE_START_FAILED,
            "$step: ${e::class.java.simpleName}: ${e.message}"
        )
        try {
            stopSelf()
        } catch (_: Exception) {
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
        // v1.0.68: the demo Mats are created here, only now that the
        // native library is loaded (see the field comment above).
        demoMat = Mat()
        previewMat = Mat()
        val mat = demoMat
        if (mat == null) {
            Log.e(TAG, "demo Mat was not created")
            stopSelf()
            return
        }
        try {
            assets.open("demo/demo_table_green.png").use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                    ?: throw IllegalStateException("demo image failed to decode")
                Utils.bitmapToMat(bitmap, mat)
            }
        } catch (e: Exception) {
            Log.e(TAG, "demo image load failed", e)
            stopSelf()
            return
        }
        // v1.0.68: the pipeline construction used to run unwrapped
        // (v1.0.35 left it out of scope). A throw here — bad metrics,
        // a native allocation failure — killed Demo mode with no
        // breadcrumb, so it gets the same failOverlayStart treatment
        // as the prelude.
        val metrics = try {
            currentDisplayMetrics()
        } catch (e: Exception) {
            failOverlayStart("currentDisplayMetrics", e)
            return
        }
        pipeline = try {
            CvPipeline(metrics.widthPixels, metrics.heightPixels).apply {
                tableOverrideNormalized = store.getTableCorners()
                gameProfile = store.getGameProfile()
                // Demo "screen" is the real display (rotate to landscape to
                // see the lines where the table would be).
                setScreenSize(metrics.widthPixels, metrics.heightPixels)
            }
        } catch (e: Exception) {
            failOverlayStart("CvPipeline init", e)
            return
        }
        cvThread = HandlerThread("poolguide-demo").apply { start() }
        cvHandler = Handler(cvThread!!.looper)
        val runnable = object : Runnable {
            override fun run() {
                if (!running || !demoMode) return
                try {
                    val demo = demoMat ?: return
                    val result = pipeline?.process(demo) ?: return
                    // Same path as live results: main thread, via the bus.
                    mainHandler.post { overlayManager?.update(result) }
                    postPreview(demo, result.isCaptureBlack)
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
        val dst = previewMat ?: return
        Imgproc.resize(source, dst, org.opencv.core.Size(360.0, 202.0))
        var bmp = previewBitmap
        if (bmp == null || bmp.width != 360 || bmp.height != 202) {
            bmp = Bitmap.createBitmap(360, 202, Bitmap.Config.ARGB_8888)
            previewBitmap = bmp
        }
        Utils.matToBitmap(dst, bmp)
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
        // v1.0.71: a clean stop clears the session, so a later
        // null-intent restart does not resurrect a stopped session.
        // Guarded: stopOverlay also runs from onDestroy on a service
        // that never started, where the store was never created. A
        // process kill never runs onDestroy, so a killed session
        // keeps its flag and is recovered.
        if (::store.isInitialized) {
            store.sessionActive = false
            store.sessionDemo = false
        }
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
        // v1.0.68: the demo Mats are created per demo run now, so
        // release them here instead of leaking native memory across
        // restarts.
        demoMat?.release()
        demoMat = null
        previewMat?.release()
        previewMat = null
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
