package com.poolguide.tool.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Neutral strings for anything visible outside our process (v1.0.37,
 * REA hardening R3). The virtual display name used to be the literal
 * fingerprint "PoolGuideCapture" and the notification read "Capturing
 * — aim lines active"; both are neutral now, and these tests fail if
 * a future cycle reintroduces a fingerprint string here.
 */
class CaptureDisplayNamesTest {

    @Test
    fun `virtual display name is generic`() {
        assertEquals("ScreenCapture", CaptureDisplayNames.VIRTUAL_DISPLAY_NAME)
        assertFalse(CaptureDisplayNames.VIRTUAL_DISPLAY_NAME.contains("PoolGuide"))
        assertFalse(CaptureDisplayNames.VIRTUAL_DISPLAY_NAME.contains("Pool Guide"))
    }

    @Test
    fun `notification text is neutral`() {
        assertEquals("Guide active", CaptureDisplayNames.NOTIFICATION_ACTIVE_TEXT)
        assertFalse(CaptureDisplayNames.NOTIFICATION_ACTIVE_TEXT.contains("Pool Guide"))
        assertFalse(CaptureDisplayNames.NOTIFICATION_ACTIVE_TEXT.contains("aim lines"))
        assertFalse(CaptureDisplayNames.NOTIFICATION_ACTIVE_TEXT.contains("Capturing"))
    }
}
