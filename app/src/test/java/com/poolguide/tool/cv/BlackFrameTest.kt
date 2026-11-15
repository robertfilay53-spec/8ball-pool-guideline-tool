package com.poolguide.tool.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Black-frame run length (v1.0.37, REA hardening R2): the pipeline
 * counts consecutive black frames into [DetectionResult.blackFrameRunFrames];
 * a run past the threshold is a persistent block, not a blip. The
 * threshold itself (30 frames, about 2 seconds at the 15 FPS cap) is
 * a judgement call — the tests pin the boundary behaviour.
 */
class BlackFrameTest {

    @Test
    fun `threshold is thirty frames`() {
        assertEquals(30, DetectionResult.BLACK_FRAME_PERSISTENT_AFTER_FRAMES)
    }

    @Test
    fun `runs below the threshold are not persistent`() {
        assertFalse(DetectionResult.isPersistentBlackRun(0))
        assertFalse(DetectionResult.isPersistentBlackRun(1))
        assertFalse(DetectionResult.isPersistentBlackRun(29))
    }

    @Test
    fun `runs at and past the threshold are persistent`() {
        assertTrue(DetectionResult.isPersistentBlackRun(30))
        assertTrue(DetectionResult.isPersistentBlackRun(31))
        assertTrue(DetectionResult.isPersistentBlackRun(1000))
    }

    @Test
    fun `blackFrame factory carries the run length`() {
        val result = DetectionResult.blackFrame(7, 720, 405, 1.0, 1.0, blackRunFrames = 42)
        assertTrue(result.isCaptureBlack)
        assertEquals(42, result.blackFrameRunFrames)
        assertEquals(7L, result.frameIndex)
        assertTrue(DetectionResult.isPersistentBlackRun(result.blackFrameRunFrames))
    }

    @Test
    fun `blackFrame factory defaults to a zero-length run`() {
        val result = DetectionResult.blackFrame(7, 720, 405, 1.0, 1.0)
        assertEquals(0, result.blackFrameRunFrames)
        assertFalse(DetectionResult.isPersistentBlackRun(result.blackFrameRunFrames))
    }
}
