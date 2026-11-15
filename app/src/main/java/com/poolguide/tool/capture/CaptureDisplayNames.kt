package com.poolguide.tool.capture

/**
 * Strings visible outside our process (v1.0.37, REA hardening R3).
 *
 * The game's display enumeration can see every virtual display's name,
 * and the foreground notification is visible in the shade, so neither
 * may carry a literal fingerprint of this tool: the virtual display
 * used to be named "PoolGuideCapture" and the notification read
 * "Capturing — aim lines active". Both are neutral now.
 *
 * Pure Kotlin, no Android dependency, so the values are pinned by a
 * JVM unit test (CaptureDisplayNamesTest) — a future cycle cannot
 * silently reintroduce a fingerprint string here.
 */
object CaptureDisplayNames {
    /** Virtual display name passed to MediaProjection
     * .createVirtualDisplay. Generic on purpose. */
    const val VIRTUAL_DISPLAY_NAME = "ScreenCapture"

    /** Foreground notification text while capture is running. Says
     * nothing about what the capture is for. */
    const val NOTIFICATION_ACTIVE_TEXT = "Guide active"
}
