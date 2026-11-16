package com.poolguide.tool.capture

import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.geometry.UiRect

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

    /** The overlay's own UI rectangles in SCREEN pixels (the
     * floating control bubble and its menu), published by the
     * overlay (v1.0.69, real-phone fix). The capture service reads
     * them before each pipeline frame and hands them to the
     * pipeline, so the detector can exclude its own UI: the old
     * control bar's white buttons were once detected as white balls
     * and one was picked as the cue ball. Same-process in-memory
     * singleton — no IPC, no persistence. Empty until the overlay
     * publishes its first rects. */
    @Volatile
    var uiRectsScreen: List<UiRect> = emptyList()
}
