package com.poolguide.tool

import com.poolguide.tool.cv.CvConfig
import com.poolguide.tool.data.GameProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `brown window defaults match the pipeline constants`() {
        val d = GameProfile.DEFAULT
        assertEquals(CvConfig.FELT_BROWN_LOWER[0], d.brownHLo, 0.0)
        assertEquals(CvConfig.FELT_BROWN_LOWER[1], d.brownSLo, 0.0)
        assertEquals(CvConfig.FELT_BROWN_LOWER[2], d.brownVLo, 0.0)
        assertEquals(CvConfig.FELT_BROWN_UPPER[0], d.brownHHi, 0.0)
        assertEquals(CvConfig.FELT_BROWN_UPPER[1], d.brownSHi, 0.0)
        assertEquals(CvConfig.FELT_BROWN_UPPER[2], d.brownVHi, 0.0)
        assertEquals(13.0, d.brownHLo, 0.0)
        assertEquals(22.0, d.brownHHi, 0.0)
    }

    @Test
    fun `brown window with low above high is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GameProfile.DEFAULT.copy(brownHLo = 30.0, brownHHi = 20.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameProfile.DEFAULT.copy(brownSLo = 300.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameProfile.DEFAULT.copy(brownVHi = 300.0)
        }
    }

    @Test
    fun `brown window serialise and parse round trip`() {
        val raw = GameProfile.serializeBrownWindow(
            doubleArrayOf(13.0, 135.0, 105.0),
            doubleArrayOf(22.0, 255.0, 255.0)
        )
        assertEquals("13.0,135.0,105.0,22.0,255.0,255.0", raw)
        val parsed = GameProfile.parseBrownWindow(raw)!!
        assertEquals(13.0, parsed[0], 0.0)
        assertEquals(255.0, parsed[5], 0.0)
        assertNull(GameProfile.parseBrownWindow(null))
        assertNull(GameProfile.parseBrownWindow("13.0,135.0"))
        assertNull(GameProfile.parseBrownWindow("not,a,window,at,all,here"))
    }

    @Test
    fun `forVersion keeps the default brown window`() {
        val p = GameProfile.forVersion("57.0.0")
        assertEquals(GameProfile.DEFAULT.brownHLo, p.brownHLo, 0.0)
        assertEquals(GameProfile.DEFAULT.brownVHi, p.brownVHi, 0.0)
    }
}
