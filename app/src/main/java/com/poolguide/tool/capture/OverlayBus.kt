package com.poolguide.tool.capture

import com.poolguide.tool.cv.DetectionResult

/**
 * Overlay bus: [CaptureService] posts each processed frame's
 * [DetectionResult] here; [OverlayService] consumes it and draws the
 * aim lines. Same-process only (both services run in one process).
 *
 * The split exists because the two services have different foreground
 * service types: capture is mediaProjection (it holds a real
 * MediaProjection token), the overlay is specialUse (it only draws
 * windows + runs Demo mode, which has no token). One service cannot
 * be both — on Android 14+ a mediaProjection-type FGS without a token
 * throws SecurityException and crashes the process (the v1.0.0 demo
 * bug). The bus is the only coupling between them.
 */
object OverlayBus {

    /** Listener receives each DetectionResult, on the CV thread.
     * The OverlayService forwards it to the main thread before
     * touching the WindowManager. */
    @Volatile
    var listener: ((DetectionResult) -> Unit)? = null

    fun post(result: DetectionResult) {
        listener?.invoke(result)
    }
}
