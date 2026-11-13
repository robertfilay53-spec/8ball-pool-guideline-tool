package com.poolguide.tool.capture

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

/**
 * Diagnostic preview bus: the [CaptureService] posts the latest captured
 * frame (downscaled) here so [com.poolguide.tool.ui.MainActivity] can show
 * it. This is the FLAG_SECURE diagnostic: if the game sets FLAG_SECURE the
 * posted frame is solid black, and the user sees that immediately instead
 * of wondering why no lines appear.
 *
 * Same-process only (the service and the activity run in one process).
 */
object PreviewBus {

    /** Listener receives (frame, isBlack). Called on the main thread. */
    var listener: ((Bitmap, Boolean) -> Unit)? = null

    /** Last posted frame (and whether it was black), so other screens
     * (e.g. CalibrationActivity) can read it without a live capture. */
    @Volatile
    var lastFrame: Bitmap? = null
        private set

    @Volatile
    var lastIsBlack: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPostMs = 0L

    /** Post at most once per [minIntervalMs] (default 500 ms). */
    fun post(frame: Bitmap, isBlack: Boolean, minIntervalMs: Long = 500L) {
        val now = System.currentTimeMillis()
        if (now - lastPostMs < minIntervalMs) return
        lastPostMs = now
        val copy = frame.copy(Bitmap.Config.ARGB_8888, false)
        lastFrame = copy
        lastIsBlack = isBlack
        mainHandler.post { listener?.invoke(copy, isBlack) }
    }
}
