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
import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.cv.OpenCvInit
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.data.DiagnosticsLog
import com.poolguide.tool.overlay.OverlayService
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
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
    /** v1.0.68: created in [startCaptureAfterForeground], AFTER
     * OpenCV is loaded. These used to be `Mat()` field initialisers,
     * so constructing the service called the native Mat constructor
     * before any loadLibrary ran and threw UnsatisfiedLinkError (an
     * Error, uncatchable by catch-Exception) — killing the process
     * the moment the user granted screen capture, with no breadcrumb
     * and no diagnostics. See OpenCvLoadOrderTest. */
    private var frameMat: Mat? = null
    private var previewMat: Mat? = null
    private var previewBitmap: Bitmap? = null

    private var running = false
    private var lastNotifUpdateMs = 0L

    // Diagnostics (v1.0.6): previous frame's state, for edge-triggered
    // events (lock gained/lost, fallback entered/left, black frames).
    private var lastWasLocked = false
    private var lastWasFallback = false
    private var lastWasBlack = false
    /** v1.0.37: whether the table was locked when the current black
     * run started, and how long the last black run lasted — the
     * context the BLACK_FRAME_PERSISTENT and BLACK_FRAME_CLEARED
     * events carry, so the next test can map WHEN FLAG_SECURE is on. */
    private var lockedBeforeBlackRun = false
    private var lastBlackRunFrames = 0

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
        // v1.0.35: attach the on-disk diagnostics sink BEFORE the
        // session starts, so every breadcrumb from this launch is
        // mirrored to disk the moment it is recorded and survives a
        // hard crash (see DiagnosticsLog).
        DiagnosticsLog.attachFile(File(filesDir, DiagnosticsLog.PREVIOUS_SESSION_FILE))
        // 1. Notification + startForeground FIRST (5s timeout on Android 14+).
        // Live capture always holds a real MediaProjection token, so the
        // mediaProjection type is correct here (and only here).
        //
        // v1.0.35: this prelude used to run outside any try/catch, so
        // a throw in startForeground (the mediaProjection type is
        // rejected on some streamed devices) killed the process with
        // no breadcrumb at all, the exact shape of the UptoPlay
        // crash, where the v1.0.31 breadcrumb never appeared because
        // the throw happened before the wrapped section. The steps
        // carry their own names, MainActivity's v1.0.31 pattern.
        var step = "CalibrationStore"
        try {
            store = CalibrationStore(this)
            // Diagnostics session (v1.0.6): a second start while a session
            // is active is a no-op, so the overlay service starting at
            // the same time cannot wipe these first events.
            DiagnosticsLog.start()
            step = "createNotificationChannel"
            createNotificationChannel()
            step = "buildNotification"
            val notif = buildNotification("Starting capture…")
            step = "startForeground"
            startForeground(
                NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } catch (e: Exception) {
            failCaptureStart(step, e)
            return
        }

        // 2. Heavy work only AFTER startForeground. v1.0.31: the whole
        // post-confirm stretch is wrapped. On cloud emulators the
        // MediaProjection token can be scoped to the stream or a
        // projection can already be held by the streaming session, so
        // getMediaProjection / createVirtualDisplay can throw, and any
        // throw here used to kill the process with no breadcrumb.
        // The precise steps have their own catches naming the failing
        // call; this outer catch is the backstop for anything else.
        try {
            startCaptureAfterForeground(intent)
        } catch (e: Exception) {
            failCaptureStart("startCapture", e)
        }
    }

    private fun startCaptureAfterForeground(intent: Intent) {
        // 2. Heavy work only AFTER startForeground.
        if (!OpenCvInit.ensureLoaded()) {
            updateNotification("OpenCV failed to load — stopping")
            Log.e(TAG, "OpenCV failed to load")
            stopSelf()
            return
        }
        // v1.0.68: the frame Mats are created here, only now that the
        // native library is loaded (see the field comment above).
        frameMat = Mat()
        previewMat = Mat()

        val metrics = try {
            currentDisplayMetrics()
        } catch (e: Exception) {
            failCaptureStart("currentDisplayMetrics", e)
            return
        }
        pipeline = try {
            CvPipeline(metrics.widthPixels, metrics.heightPixels).apply {
                tableOverrideNormalized = store.getTableCorners()
                gameProfile = store.getGameProfile()
            }
        } catch (e: Exception) {
            failCaptureStart("CvPipeline init", e)
            return
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

    /** v1.0.31: a post-confirm startup step threw. Log it, leave a
     * diagnostics breadcrumb naming the failing call (plus the
     * exception type and message), and stop gracefully instead of
     * crashing. The breadcrumb survives in the in-memory log, so the
     * user can export diagnostics afterwards and the next
     * cloud-emulator run can say exactly what threw. v1.0.35: the
     * breadcrumb is also mirrored to disk the moment it is recorded
     * (DiagnosticsLog), so it survives even a hard crash. This
     * function itself must never throw: the notification machinery
     * may be the thing that threw (buildNotification runs inside
     * updateNotification), so both calls are guarded. */
    private fun failCaptureStart(step: String, e: Exception? = null) {
        val detail = if (e == null) {
            step
        } else {
            "$step: ${e::class.java.simpleName}: ${e.message}"
        }
        Log.e(TAG, "capture start failed at $detail", e)
        DiagnosticsLog.record(DiagnosticsLog.Kind.CAPTURE_START_FAILED, detail)
        try {
            updateNotification("Capture failed to start, see diagnostics")
        } catch (_: Exception) {
        }
        try {
            stopSelf()
        } catch (_: Exception) {
        }
    }

    private fun startMediaProjection(resultCode: Int, resultData: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            // Cloud emulator: the token can be scoped to the stream or
            // a projection already held by the streaming session.
            failCaptureStart("getMediaProjection", e)
            return
        }
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // User stopped the projection from the status-bar tile.
                stopCapture()
                stopSelf()
            }
        }, Handler(mainLooper))

        val densityDpi = resources.displayMetrics.densityDpi
        imageReader = try {
            ImageReader.newInstance(VD_WIDTH, VD_HEIGHT, PixelFormat.RGBA_8888, 2)
        } catch (e: Exception) {
            failCaptureStart("ImageReader.newInstance", e)
            return
        }
        virtualDisplay = try {
            projection.createVirtualDisplay(
                CaptureDisplayNames.VIRTUAL_DISPLAY_NAME,
                VD_WIDTH, VD_HEIGHT, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
        } catch (e: Exception) {
            failCaptureStart("createVirtualDisplay", e)
            return
        }
        // Defensive check (v1.0.31): createVirtualDisplay can return
        // null when the display could not be created. Never start the
        // CV thread without a real virtual display behind it.
        if (virtualDisplay == null) {
            failCaptureStart("createVirtualDisplay returned null")
            return
        }

        cvThread = HandlerThread("poolguide-cv").apply { start() }
        cvHandler = Handler(cvThread!!.looper)
        imageReader!!.setOnImageAvailableListener({ reader ->
            // v1.0.35: acquireLatestImage throws when the reader was
            // closed concurrently (stopCapture mid-frame). An uncaught
            // throw on this handler thread would kill the process, so
            // a failed acquire just drops the frame.
            val image = try {
                reader.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: return@setOnImageAvailableListener
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
        updateNotification(CaptureDisplayNames.NOTIFICATION_ACTIVE_TEXT)
    }

    /** Copy the ImageReader RGBA frame into [frameMat], honouring rowStride. */
    private fun copyImageToMat(image: android.media.Image) {
        // v1.0.68: the Mats are nullable until startCaptureAfterForeground
        // creates them; a frame arriving before that is dropped, never
        // a null-pointer crash.
        val mat = frameMat ?: return
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (mat.rows() != height || mat.cols() != width) {
            mat.create(height, width, CvType.CV_8UC4)
        }
        val bytes = ByteArray(rowStride * height)
        buffer.get(bytes)
        // Re-pack rows (rowStride may exceed width * 4).
        if (rowStride == width * 4) {
            mat.put(0, 0, bytes)
        } else {
            val row = ByteArray(width * 4)
            for (y in 0 until height) {
                System.arraycopy(bytes, y * rowStride, row, 0, width * 4)
                mat.put(y, 0, row)
            }
        }
    }

    /** Single-slot: drop the frame if the previous one is still processing. */
    private fun runPipelineFrame() {
        // v1.0.68: the null checks come first — returning after
        // compareAndSet would leave cvBusy set and drop every later frame.
        val mat = frameMat ?: return
        if (!cvBusy.compareAndSet(false, true)) return
        val handler = cvHandler
        if (handler == null) {
            cvBusy.set(false)
            return
        }
        handler.post {
            try {
                val result = pipeline?.process(mat) ?: return@post
                OverlayBus.post(result)
                recordDiagnostics(result)
                postPreview(mat, result.isCaptureBlack)
                maybeUpdateNotification(result.status)
            } catch (e: Exception) {
                Log.e(TAG, "pipeline frame failed", e)
            } finally {
                cvBusy.set(false)
            }
        }
    }

    /** Diagnostics (v1.0.6): edge-triggered events on every frame, plus
     * a compact frame-stats line every 150 frames (~10 s at 15 FPS).
     * Runs on the CV thread; DiagnosticsLog is synchronised. */
    private fun recordDiagnostics(result: DetectionResult) {
        if (result.tableLocked && !lastWasLocked) {
            DiagnosticsLog.record(
                DiagnosticsLog.Kind.TABLE_LOCKED,
                "lock=${"%.2f".format(result.tableLockProgress)}"
            )
        } else if (!result.tableLocked && lastWasLocked) {
            DiagnosticsLog.record(DiagnosticsLog.Kind.TABLE_LOST, result.status)
        }
        if (result.tableFromFallback && !lastWasFallback) {
            DiagnosticsLog.record(DiagnosticsLog.Kind.FALLBACK_START, result.status)
        } else if (!result.tableFromFallback && lastWasFallback) {
            DiagnosticsLog.record(DiagnosticsLog.Kind.FALLBACK_END, result.status)
        }
        if (result.isCaptureBlack && !lastWasBlack) {
            lockedBeforeBlackRun = lastWasLocked
            DiagnosticsLog.record(
                DiagnosticsLog.Kind.BLACK_FRAME,
                "capture is black — FLAG_SECURE may be set"
            )
        } else if (!result.isCaptureBlack && lastWasBlack) {
            DiagnosticsLog.record(
                DiagnosticsLog.Kind.BLACK_FRAME_CLEARED,
                "black run lasted $lastBlackRunFrames frames, " +
                    "tableLockedBefore=$lockedBeforeBlackRun"
            )
        }
        // v1.0.37: a black run that persists past the threshold is a
        // first-class event, recorded exactly once per run (the run
        // length counts up by one per processed frame, so it crosses
        // the threshold on exactly one frame), with the context the
        // next phone test needs: which frame it crossed on, how long
        // it had already lasted, and whether the table was locked
        // just before the capture went black.
        if (result.blackFrameRunFrames == DetectionResult.BLACK_FRAME_PERSISTENT_AFTER_FRAMES) {
            DiagnosticsLog.record(
                DiagnosticsLog.Kind.BLACK_FRAME_PERSISTENT,
                "frame=${result.frameIndex} run=${result.blackFrameRunFrames} frames " +
                    "tableLockedBefore=$lockedBeforeBlackRun"
            )
        }
        lastWasLocked = result.tableLocked
        lastWasFallback = result.tableFromFallback
        lastWasBlack = result.isCaptureBlack
        lastBlackRunFrames = result.blackFrameRunFrames
        if (result.frameIndex % 150L == 0L) {
            DiagnosticsLog.record(
                DiagnosticsLog.Kind.FRAME_STATS,
                "frame=${result.frameIndex} balls=${result.balls.size} " +
                    "cue=${if (result.cueBall != null) "yes" else "no"} " +
                    "guideline=${result.guideline.size} " +
                    "locked=${result.tableLocked} " +
                    "fallback=${result.tableFromFallback} " +
                    "lock=${"%.2f".format(result.tableLockProgress)}"
            )
        }
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

    private fun maybeUpdateNotification(status: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifUpdateMs < 1000) return
        lastNotifUpdateMs = now
        updateNotification(status)
    }

    private fun updateScreenSize() {
        // v1.0.35: a display-change callback must never crash the main
        // thread (the emulator window resizes constantly on streamed
        // devices, so this fires often there).
        try {
            val metrics = currentDisplayMetrics()
            pipeline?.setScreenSize(metrics.widthPixels, metrics.heightPixels)
        } catch (e: Exception) {
            Log.e(TAG, "updateScreenSize failed", e)
        }
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
        DiagnosticsLog.end()
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
        // v1.0.68: the frame Mats are created per capture run now, so
        // release them here instead of leaking native memory across
        // restarts.
        frameMat?.release()
        frameMat = null
        previewMat?.release()
        previewMat = null
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        // The overlay lives in its own service: stop it too, so the
        // lines never outlive the capture. v1.0.35: starting a service
        // can throw while the app is being torn down; stopCapture runs
        // from onDestroy, so it must never throw.
        val stopOverlay = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        try {
            startService(stopOverlay)
        } catch (e: Exception) {
            Log.e(TAG, "failed to stop overlay service", e)
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
