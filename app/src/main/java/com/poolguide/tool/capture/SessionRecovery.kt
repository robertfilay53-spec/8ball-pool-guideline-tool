package com.poolguide.tool.capture

/**
 * Session recovery decision (v1.0.71, Vivo survival).
 *
 * When Android restarts a START_STICKY service after the process was
 * killed, `onStartCommand` is called with a null intent. The old code
 * returned START_NOT_STICKY and restarted nothing, so a Vivo
 * background kill (or a low-memory kill on a 4 GB phone) left the
 * bubble gone and the capture dead until the user reopened the app.
 * A session that was never cleanly stopped is recovered; a stopped
 * one (or a fresh boot with no session) is not.
 *
 * Pure Kotlin, no Android dependency, fully unit-testable on the JVM
 * (see SessionRecoveryTest).
 */
object SessionRecovery {

    /** True when a killed service should resume its session: the
     * intent carries no action (system restart after a kill) and a
     * session was active when the process died. A null session flag
     * (store unreadable) never recovers. */
    fun shouldRecoverSession(sessionActive: Boolean?, intentAction: String?): Boolean =
        sessionActive == true && intentAction == null
}
