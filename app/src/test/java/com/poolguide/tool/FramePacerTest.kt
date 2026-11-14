package com.poolguide.tool

import com.poolguide.tool.capture.FramePacer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frame pacer: 15 FPS cap (Scugalug measured targets). Pure Kotlin. */
class FramePacerTest {

    @Test
    fun `first frame is always admitted`() {
        val pacer = FramePacer(66L)
        assertTrue(pacer.shouldProcess(1000L))
    }

    @Test
    fun `a frame inside the interval is dropped`() {
        val pacer = FramePacer(66L)
        assertTrue(pacer.shouldProcess(1000L))
        assertFalse(pacer.shouldProcess(1001L))
        assertFalse(pacer.shouldProcess(1065L))
    }

    @Test
    fun `a frame at exactly the interval is admitted`() {
        val pacer = FramePacer(66L)
        assertTrue(pacer.shouldProcess(1000L))
        assertTrue(pacer.shouldProcess(1066L))
    }

    @Test
    fun `a frame after the interval is admitted and re-arms the clock`() {
        val pacer = FramePacer(66L)
        assertTrue(pacer.shouldProcess(1000L))
        assertTrue(pacer.shouldProcess(1100L))
        assertFalse(pacer.shouldProcess(1101L))
        assertTrue(pacer.shouldProcess(1166L))
    }

    @Test
    fun `reset admits the next frame immediately`() {
        val pacer = FramePacer(66L)
        assertTrue(pacer.shouldProcess(1000L))
        pacer.reset()
        assertTrue(pacer.shouldProcess(1001L))
    }

    @Test
    fun `15 fps cap means a 66 ms minimum interval`() {
        // 1000 ms / 15 fps = 66.67 ms; the config rounds down to 66.
        val interval = 1000L / 15L
        assertTrue(interval == 66L)
        val pacer = FramePacer(interval)
        assertTrue(pacer.shouldProcess(0L))
        assertFalse(pacer.shouldProcess(66L - 1))
        assertTrue(pacer.shouldProcess(66L))
    }
}
