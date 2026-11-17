package com.poolguide.tool.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session recovery decision (v1.0.71, Vivo survival).
 *
 * When Android restarts a START_STICKY service after the process was
 * killed, onStartCommand is called with a null intent. The decision
 * is: recover only when a session was active when the process died
 * and the restart carries no action. A cleanly stopped session, a
 * fresh boot, or an explicit start/stop intent never recovers.
 */
class SessionRecoveryTest {

    @Test
    fun `null intent with active session recovers`() {
        assertTrue(SessionRecovery.shouldRecoverSession(true, null))
    }

    @Test
    fun `null intent with no active session does not recover`() {
        assertFalse(SessionRecovery.shouldRecoverSession(false, null))
    }

    @Test
    fun `null intent with unknown session state does not recover`() {
        assertFalse(SessionRecovery.shouldRecoverSession(null, null))
    }

    @Test
    fun `explicit start intent never recovers`() {
        assertFalse(
            SessionRecovery.shouldRecoverSession(true, "com.poolguide.tool.ACTION_START")
        )
    }

    @Test
    fun `explicit stop intent never recovers`() {
        assertFalse(
            SessionRecovery.shouldRecoverSession(true, "com.poolguide.tool.ACTION_STOP")
        )
    }

    @Test
    fun `explicit demo intent never recovers`() {
        assertFalse(
            SessionRecovery.shouldRecoverSession(true, "com.poolguide.tool.ACTION_DEMO")
        )
    }
}
