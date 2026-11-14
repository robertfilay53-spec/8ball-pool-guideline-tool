package com.poolguide.tool

import com.poolguide.tool.cv.CvConfig
import com.poolguide.tool.data.GameProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Per-game-version calibration profiles (aimcall lead). Pure Kotlin. */
class GameProfileTest {

    @Test
    fun `defaults match the pipeline constants`() {
        val d = GameProfile.DEFAULT
        assertEquals("56.35.0", d.version)
        assertEquals(CvConfig.POCKET_INSET_FRACTION, d.pocketInsetFraction, 0.0)
        assertEquals(CvConfig.POCKET_RADIUS_FRACTION, d.pocketRadiusFraction, 0.0)
        assertEquals(CvConfig.BALL_RADIUS_PX_AT_720, d.ballRadiusPxAt720, 0.0)
    }

    @Test
    fun `forVersion stamps the defaults with the unknown version`() {
        val p = GameProfile.forVersion("57.0.0")
        assertEquals("57.0.0", p.version)
        assertEquals(GameProfile.DEFAULT.pocketInsetFraction, p.pocketInsetFraction, 0.0)
        assertEquals(GameProfile.DEFAULT.pocketRadiusFraction, p.pocketRadiusFraction, 0.0)
        assertEquals(GameProfile.DEFAULT.ballRadiusPxAt720, p.ballRadiusPxAt720, 0.0)
    }

    @Test
    fun `a stored profile keeps its own numbers`() {
        val p = GameProfile("56.40.0", 0.02, 0.025, 10.0)
        assertEquals(0.02, p.pocketInsetFraction, 0.0)
        assertEquals(0.025, p.pocketRadiusFraction, 0.0)
        assertEquals(10.0, p.ballRadiusPxAt720, 0.0)
    }

    @Test
    fun `pocket inset outside 0 to 0 point 1 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GameProfile("56.35.0", 0.5, 0.02, 9.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameProfile("56.35.0", -0.01, 0.02, 9.0)
        }
    }

    @Test
    fun `pocket radius outside 0 to 0 point 1 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GameProfile("56.35.0", 0.015, 0.5, 9.0)
        }
    }

    @Test
    fun `ball radius outside 1 to 40 px is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GameProfile("56.35.0", 0.015, 0.02, 0.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameProfile("56.35.0", 0.015, 0.02, 100.0)
        }
    }
}
